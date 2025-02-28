/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <string.h>
#include <errno.h>
#include <inttypes.h>
#include "concurrent/aeron_counters_manager.h"
#include "concurrent/aeron_logbuffer_unblocker.h"
#include "aeron_ipc_publication.h"
#include "util/aeron_fileutil.h"
#include "aeron_alloc.h"
#include "protocol/aeron_udp_protocol.h"
#include "aeron_driver_conductor.h"
#include "util/aeron_error.h"

int aeron_ipc_publication_create(
    aeron_ipc_publication_t **publication,
    aeron_driver_context_t *context,
    int32_t session_id,
    int32_t stream_id,
    int64_t registration_id,
    aeron_position_t *pub_pos_position,
    aeron_position_t *pub_lmt_position,
    int32_t initial_term_id,
    aeron_uri_publication_params_t *params,
    bool is_exclusive,
    aeron_system_counters_t *system_counters)
{
    char path[AERON_MAX_PATH];
    int path_length = aeron_ipc_publication_location(
        path, sizeof(path), context->aeron_dir, session_id, stream_id, registration_id);
    aeron_ipc_publication_t *_pub = NULL;
    const uint64_t usable_fs_space = context->usable_fs_space_func(context->aeron_dir);
    const uint64_t log_length = aeron_logbuffer_compute_log_length(params->term_length, context->file_page_size);
    const int64_t now_ns = context->nano_clock();

    *publication = NULL;

    if (usable_fs_space < log_length)
    {
        aeron_set_err(
            ENOSPC,
            "Insufficient usable storage for new log of length=%" PRId64 " in %s", log_length, context->aeron_dir);
        return -1;
    }

    if (aeron_alloc((void **)&_pub, sizeof(aeron_ipc_publication_t)) < 0)
    {
        aeron_set_err(ENOMEM, "%s", "Could not allocate IPC publication");
        return -1;
    }

    _pub->log_file_name = NULL;
    if (aeron_alloc((void **)(&_pub->log_file_name), (size_t)path_length + 1) < 0)
    {
        aeron_free(_pub);
        aeron_set_err(ENOMEM, "%s", "Could not allocate IPC publication log_file_name");
        return -1;
    }

    if (context->map_raw_log_func(
        &_pub->mapped_raw_log, path, params->is_sparse, params->term_length, context->file_page_size) < 0)
    {
        aeron_free(_pub->log_file_name);
        aeron_free(_pub);
        aeron_set_err(aeron_errcode(), "error mapping IPC raw log %s: %s", path, aeron_errmsg());
        return -1;
    }
    _pub->map_raw_log_close_func = context->map_raw_log_close_func;

    strncpy(_pub->log_file_name, path, (size_t)path_length);
    _pub->log_file_name[path_length] = '\0';
    _pub->log_file_name_length = (size_t)path_length;
    _pub->log_meta_data = (aeron_logbuffer_metadata_t *)(_pub->mapped_raw_log.log_meta_data.addr);

    if (params->is_replay)
    {
        int64_t term_id = params->term_id;
        int32_t term_count = params->term_id - initial_term_id;
        size_t active_index = aeron_logbuffer_index_by_term_count(term_count);

        _pub->log_meta_data->term_tail_counters[active_index] =
            (term_id * ((int64_t)1 << 32)) | params->term_offset;

        for (int i = 1; i < AERON_LOGBUFFER_PARTITION_COUNT; i++)
        {
            int64_t expected_term_id = (term_id + i) - AERON_LOGBUFFER_PARTITION_COUNT;
            active_index = (active_index + 1) % AERON_LOGBUFFER_PARTITION_COUNT;
            _pub->log_meta_data->term_tail_counters[active_index] = expected_term_id * ((int64_t)1 << 32);
        }

        _pub->log_meta_data->active_term_count = term_count;
    }
    else
    {
        _pub->log_meta_data->term_tail_counters[0] = initial_term_id * ((int64_t)1 << 32);
        for (int i = 1; i < AERON_LOGBUFFER_PARTITION_COUNT; i++)
        {
            int64_t expected_term_id = (initial_term_id + i) - AERON_LOGBUFFER_PARTITION_COUNT;
            _pub->log_meta_data->term_tail_counters[i] = expected_term_id * ((int64_t)1 << 32);
        }

        _pub->log_meta_data->active_term_count = 0;
    }

    _pub->log_meta_data->active_term_count = 0;
    _pub->log_meta_data->initial_term_id = initial_term_id;
    _pub->log_meta_data->mtu_length = (int32_t)params->mtu_length;
    _pub->log_meta_data->term_length = (int32_t)params->term_length;
    _pub->log_meta_data->page_size = (int32_t)context->file_page_size;
    _pub->log_meta_data->correlation_id = registration_id;
    _pub->log_meta_data->is_connected = 0;
    _pub->log_meta_data->active_transport_count = 0;
    _pub->log_meta_data->end_of_stream_position = INT64_MAX;
    aeron_logbuffer_fill_default_header(
        _pub->mapped_raw_log.log_meta_data.addr, session_id, stream_id, initial_term_id);

    _pub->nano_clock = context->nano_clock;
    _pub->conductor_fields.subscribable.array = NULL;
    _pub->conductor_fields.subscribable.length = 0;
    _pub->conductor_fields.subscribable.capacity = 0;
    _pub->conductor_fields.subscribable.add_position_hook_func = aeron_ipc_publication_add_subscriber_hook;
    _pub->conductor_fields.subscribable.remove_position_hook_func = aeron_ipc_publication_remove_subscriber_hook;
    _pub->conductor_fields.subscribable.clientd = _pub;
    _pub->conductor_fields.managed_resource.registration_id = registration_id;
    _pub->conductor_fields.managed_resource.clientd = _pub;
    _pub->conductor_fields.managed_resource.incref = aeron_ipc_publication_incref;
    _pub->conductor_fields.managed_resource.decref = aeron_ipc_publication_decref;
    _pub->conductor_fields.has_reached_end_of_life = false;
    _pub->conductor_fields.trip_limit = 0;
    _pub->conductor_fields.time_of_last_consumer_position_change = now_ns;
    _pub->conductor_fields.state = AERON_IPC_PUBLICATION_STATE_ACTIVE;
    _pub->conductor_fields.refcnt = 1;
    _pub->session_id = session_id;
    _pub->stream_id = stream_id;
    _pub->pub_lmt_position.counter_id = pub_lmt_position->counter_id;
    _pub->pub_lmt_position.value_addr = pub_lmt_position->value_addr;
    _pub->pub_pos_position.counter_id = pub_pos_position->counter_id;
    _pub->pub_pos_position.value_addr = pub_pos_position->value_addr;
    _pub->initial_term_id = initial_term_id;
    _pub->position_bits_to_shift = (size_t)aeron_number_of_trailing_zeroes((int32_t)params->term_length);
    _pub->term_window_length = (int64_t)aeron_producer_window_length(
        context->ipc_publication_window_length, params->term_length);
    _pub->trip_gain = _pub->term_window_length / 8;
    _pub->unblock_timeout_ns = (int64_t)context->publication_unblock_timeout_ns;
    _pub->is_exclusive = is_exclusive;

    _pub->conductor_fields.consumer_position = aeron_ipc_publication_producer_position(_pub);
    _pub->conductor_fields.last_consumer_position = _pub->conductor_fields.consumer_position;
    _pub->conductor_fields.clean_position = _pub->conductor_fields.consumer_position;

    _pub->unblocked_publications_counter = aeron_system_counter_addr(
        system_counters, AERON_SYSTEM_COUNTER_UNBLOCKED_PUBLICATIONS);

    *publication = _pub;

    return 0;
}

void aeron_ipc_publication_close(aeron_counters_manager_t *counters_manager, aeron_ipc_publication_t *publication)
{
    aeron_subscribable_t *subscribable = &publication->conductor_fields.subscribable;

    aeron_counters_manager_free(counters_manager, publication->pub_lmt_position.counter_id);
    aeron_counters_manager_free(counters_manager, publication->pub_pos_position.counter_id);

    for (size_t i = 0, length = subscribable->length; i < length; i++)
    {
        aeron_counters_manager_free(counters_manager, subscribable->array[i].counter_id);
    }
    aeron_free(subscribable->array);

    if (NULL != publication)
    {
        publication->map_raw_log_close_func(&publication->mapped_raw_log, publication->log_file_name);
        aeron_free(publication->log_file_name);
    }

    aeron_free(publication);
}

int aeron_ipc_publication_update_pub_lmt(aeron_ipc_publication_t *publication)
{
    if (0 == publication->conductor_fields.subscribable.length)
    {
        return 0;
    }

    int work_count = 0;
    int64_t min_sub_pos = INT64_MAX;
    int64_t max_sub_pos = publication->conductor_fields.consumer_position;

    for (size_t i = 0, length = publication->conductor_fields.subscribable.length; i < length; i++)
    {
        aeron_tetherable_position_t *tetherable_position = &publication->conductor_fields.subscribable.array[i];

        if (AERON_SUBSCRIPTION_TETHER_RESTING != tetherable_position->state)
        {
            int64_t position = aeron_counter_get_volatile(tetherable_position->value_addr);

            min_sub_pos = position < min_sub_pos ? position : min_sub_pos;
            max_sub_pos = position > max_sub_pos ? position : max_sub_pos;
        }
    }

    if (0 == publication->conductor_fields.subscribable.length)
    {
        aeron_counter_set_ordered(publication->pub_lmt_position.value_addr, max_sub_pos);
        publication->conductor_fields.trip_limit = max_sub_pos;
    }
    else
    {
        int64_t proposed_limit = min_sub_pos + publication->term_window_length;
        if (proposed_limit > publication->conductor_fields.trip_limit)
        {
            aeron_ipc_publication_clean_buffer(publication, min_sub_pos);
            aeron_counter_set_ordered(publication->pub_lmt_position.value_addr, proposed_limit);
            publication->conductor_fields.trip_limit = proposed_limit + publication->trip_gain;

            work_count = 1;
        }

        publication->conductor_fields.consumer_position = max_sub_pos;
    }

    return work_count;
}

void aeron_ipc_publication_clean_buffer(aeron_ipc_publication_t *publication, int64_t position)
{
    int64_t clean_position = publication->conductor_fields.clean_position;
    if (position > clean_position)
    {
        size_t dirty_index = aeron_logbuffer_index_by_position(clean_position, publication->position_bits_to_shift);
        size_t bytes_to_clean = position - clean_position;
        size_t term_length = publication->mapped_raw_log.term_length;
        size_t term_offset = (size_t)(clean_position & (term_length - 1));
        size_t bytes_left_in_term = term_length - term_offset;
        size_t length = bytes_to_clean < bytes_left_in_term ? bytes_to_clean : bytes_left_in_term;

        memset(
            publication->mapped_raw_log.term_buffers[dirty_index].addr + term_offset + sizeof(int64_t),
            0,
            length - sizeof(int64_t));

        uint64_t *ptr = (uint64_t *)(publication->mapped_raw_log.term_buffers[dirty_index].addr + term_offset);
        AERON_PUT_ORDERED(*ptr, (uint64_t)0);

        publication->conductor_fields.clean_position = clean_position + length;
    }
}

void aeron_ipc_publication_check_untethered_subscriptions(
    aeron_driver_conductor_t *conductor, aeron_ipc_publication_t *publication, int64_t now_ns)
{
    int64_t consumer_position = publication->conductor_fields.consumer_position;
    int64_t term_window_length = publication->term_window_length;
    int64_t untethered_window_limit = (consumer_position - term_window_length) + (term_window_length / 8);

    for (size_t i = 0, length = publication->conductor_fields.subscribable.length; i < length; i++)
    {
        aeron_tetherable_position_t *tetherable_position = &publication->conductor_fields.subscribable.array[i];

        if (tetherable_position->is_tether)
        {
            tetherable_position->time_of_last_update_ns = now_ns;
        }
        else
        {
            int64_t window_limit_timeout_ns = conductor->context->untethered_window_limit_timeout_ns;
            int64_t resting_timeout_ns = conductor->context->untethered_resting_timeout_ns;

            switch (tetherable_position->state)
            {
                case AERON_SUBSCRIPTION_TETHER_ACTIVE:
                    if (aeron_counter_get_volatile(tetherable_position->value_addr) > untethered_window_limit)
                    {
                        tetherable_position->time_of_last_update_ns = now_ns;
                    }
                    else if (now_ns > (tetherable_position->time_of_last_update_ns + window_limit_timeout_ns))
                    {
                        aeron_driver_conductor_on_unavailable_image(
                            conductor,
                            publication->conductor_fields.managed_resource.registration_id,
                            tetherable_position->subscription_registration_id,
                            publication->stream_id,
                            AERON_IPC_CHANNEL,
                            AERON_IPC_CHANNEL_LEN);

                        tetherable_position->state = AERON_SUBSCRIPTION_TETHER_LINGER;
                        tetherable_position->time_of_last_update_ns = now_ns;
                    }
                    break;

                case AERON_SUBSCRIPTION_TETHER_LINGER:
                    if (now_ns > (tetherable_position->time_of_last_update_ns + window_limit_timeout_ns))
                    {
                        tetherable_position->state = AERON_SUBSCRIPTION_TETHER_RESTING;
                        tetherable_position->time_of_last_update_ns = now_ns;
                    }
                    break;

                case AERON_SUBSCRIPTION_TETHER_RESTING:
                    if (now_ns > (tetherable_position->time_of_last_update_ns + resting_timeout_ns))
                    {
                        aeron_counter_set_ordered(tetherable_position->value_addr, consumer_position);
                        aeron_driver_conductor_on_available_image(
                            conductor,
                            publication->conductor_fields.managed_resource.registration_id,
                            publication->stream_id,
                            publication->session_id,
                            publication->log_file_name,
                            publication->log_file_name_length,
                            tetherable_position->counter_id,
                            tetherable_position->subscription_registration_id,
                            AERON_IPC_CHANNEL,
                            AERON_IPC_CHANNEL_LEN);
                        tetherable_position->state = AERON_SUBSCRIPTION_TETHER_ACTIVE;
                        tetherable_position->time_of_last_update_ns = now_ns;
                    }
                    break;
            }
        }
    }
}

void aeron_ipc_publication_on_time_event(
    aeron_driver_conductor_t *conductor, aeron_ipc_publication_t *publication, int64_t now_ns, int64_t now_ms)
{
    const int64_t producer_position = aeron_ipc_publication_producer_position(publication);
    aeron_counter_set_ordered(publication->pub_pos_position.value_addr, producer_position);

    switch (publication->conductor_fields.state)
    {
        case AERON_IPC_PUBLICATION_STATE_ACTIVE:
            aeron_ipc_publication_check_untethered_subscriptions(conductor, publication, now_ns);
            if (!publication->is_exclusive)
            {
                aeron_ipc_publication_check_for_blocked_publisher(publication, producer_position, now_ns);
            }
            break;

        case AERON_IPC_PUBLICATION_STATE_INACTIVE:
            if (aeron_ipc_publication_is_drained(publication))
            {
                publication->conductor_fields.state = AERON_IPC_PUBLICATION_STATE_LINGER;
                publication->conductor_fields.managed_resource.time_of_last_state_change = now_ns;

                for (size_t i = 0, size = conductor->ipc_subscriptions.length; i < size; i++)
                {
                    aeron_subscription_link_t *link = &conductor->ipc_subscriptions.array[i];

                    if (aeron_driver_conductor_is_subscribable_linked(link, &publication->conductor_fields.subscribable))
                    {
                        aeron_driver_conductor_on_unavailable_image(
                            conductor,
                            publication->conductor_fields.managed_resource.registration_id,
                            link->registration_id,
                            publication->stream_id,
                            AERON_IPC_CHANNEL,
                            AERON_IPC_CHANNEL_LEN);
                    }
                }
            }
            else if (aeron_logbuffer_unblocker_unblock(
                publication->mapped_raw_log.term_buffers,
                publication->log_meta_data,
                publication->conductor_fields.consumer_position))
            {
                aeron_counter_ordered_increment(publication->unblocked_publications_counter, 1);
            }
            break;

        case AERON_IPC_PUBLICATION_STATE_LINGER:
            publication->conductor_fields.has_reached_end_of_life = true;
            break;

        default:
            break;
    }
}

void aeron_ipc_publication_incref(void *clientd)
{
    aeron_ipc_publication_t *publication = (aeron_ipc_publication_t *)clientd;
    publication->conductor_fields.refcnt++;
}

void aeron_ipc_publication_decref(void *clientd)
{
    aeron_ipc_publication_t *publication = (aeron_ipc_publication_t *)clientd;
    int32_t ref_count = --publication->conductor_fields.refcnt;

    if (0 == ref_count)
    {
        publication->conductor_fields.state = AERON_IPC_PUBLICATION_STATE_INACTIVE;
        int64_t producer_position = aeron_ipc_publication_producer_position(publication);

        if (aeron_counter_get(publication->pub_lmt_position.value_addr) > producer_position)
        {
            aeron_counter_set_ordered(publication->pub_lmt_position.value_addr, producer_position);
        }

        AERON_PUT_ORDERED(publication->log_meta_data->end_of_stream_position, producer_position);
    }
}

void aeron_ipc_publication_check_for_blocked_publisher(
    aeron_ipc_publication_t *publication, int64_t producer_position, int64_t now_ns)
{
    int64_t consumer_position = publication->conductor_fields.consumer_position;

    if (consumer_position == publication->conductor_fields.last_consumer_position &&
        aeron_ipc_publication_is_possibly_blocked(publication, producer_position, consumer_position))
    {
        if (now_ns >
            (publication->conductor_fields.time_of_last_consumer_position_change + publication->unblock_timeout_ns))
        {
            if (aeron_logbuffer_unblocker_unblock(
                publication->mapped_raw_log.term_buffers,
                publication->log_meta_data,
                publication->conductor_fields.consumer_position))
            {
                aeron_counter_ordered_increment(publication->unblocked_publications_counter, 1);
            }
        }
    }
    else
    {
        publication->conductor_fields.time_of_last_consumer_position_change = now_ns;
        publication->conductor_fields.last_consumer_position = publication->conductor_fields.consumer_position;
    }
}

extern void aeron_ipc_publication_add_subscriber_hook(void *clientd, int64_t *value_addr);

extern void aeron_ipc_publication_remove_subscriber_hook(void *clientd, int64_t *value_addr);

extern bool aeron_ipc_publication_is_possibly_blocked(
    aeron_ipc_publication_t *publication, int64_t producer_position, int64_t consumer_position);

extern int64_t aeron_ipc_publication_producer_position(aeron_ipc_publication_t *publication);

extern int64_t aeron_ipc_publication_joining_position(aeron_ipc_publication_t *publication);

extern bool aeron_ipc_publication_has_reached_end_of_life(aeron_ipc_publication_t *publication);

extern bool aeron_ipc_publication_is_drained(aeron_ipc_publication_t *publication);

extern size_t aeron_ipc_publication_num_subscribers(aeron_ipc_publication_t *publication);
