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
package io.aeron.archive;

import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.archive.client.ArchiveException;
import io.aeron.archive.codecs.*;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import static io.aeron.archive.codecs.RecordingDescriptorEncoder.recordingIdEncodingOffset;

class ControlResponseProxy
{
    private static final int SEND_ATTEMPTS = 3;
    private static final int MESSAGE_HEADER_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH;
    private static final int DESCRIPTOR_CONTENT_OFFSET =
        RecordingDescriptorHeaderDecoder.BLOCK_LENGTH + recordingIdEncodingOffset();

    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(1024);
    private final BufferClaim bufferClaim = new BufferClaim();

    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final ControlResponseEncoder responseEncoder = new ControlResponseEncoder();
    private final RecordingDescriptorEncoder recordingDescriptorEncoder = new RecordingDescriptorEncoder();
    private final RecordingSubscriptionDescriptorEncoder recordingSubscriptionDescriptorEncoder =
        new RecordingSubscriptionDescriptorEncoder();
    private final RecordingSignalEventEncoder recordingSignalEventEncoder = new RecordingSignalEventEncoder();

    int sendDescriptor(
        final long controlSessionId,
        final long correlationId,
        final UnsafeBuffer descriptorBuffer,
        final ControlSession session)
    {
        final int messageLength = Catalog.descriptorLength(descriptorBuffer) + MESSAGE_HEADER_LENGTH;
        final int contentLength = messageLength - recordingIdEncodingOffset() - MESSAGE_HEADER_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = session.controlPublication().tryClaim(messageLength, bufferClaim);
            if (result > 0)
            {
                final MutableDirectBuffer buffer = bufferClaim.buffer();
                final int bufferOffset = bufferClaim.offset();

                recordingDescriptorEncoder
                    .wrapAndApplyHeader(buffer, bufferOffset, messageHeaderEncoder)
                    .controlSessionId(controlSessionId)
                    .correlationId(correlationId);

                final int contentOffset = bufferOffset + MESSAGE_HEADER_LENGTH + recordingIdEncodingOffset();
                buffer.putBytes(contentOffset, descriptorBuffer, DESCRIPTOR_CONTENT_OFFSET, contentLength);

                bufferClaim.commit();

                return messageLength;
            }

            checkResult(session, result);
        }
        while (--attempts > 0);

        return 0;
    }

    boolean sendSubscriptionDescriptor(
        final long controlSessionId,
        final long correlationId,
        final Subscription subscription,
        final ControlSession session)
    {
        recordingSubscriptionDescriptorEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .subscriptionId(subscription.registrationId())
            .streamId(subscription.streamId())
            .strippedChannel(subscription.channel());

        final int length = MESSAGE_HEADER_LENGTH + recordingSubscriptionDescriptorEncoder.encodedLength();

        return send(session, buffer, length);
    }

    boolean sendResponse(
        final long controlSessionId,
        final long correlationId,
        final long relevantId,
        final ControlResponseCode code,
        final String errorMessage,
        final ControlSession session)
    {
        responseEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .relevantId(relevantId)
            .code(code)
            .errorMessage(errorMessage);

        return send(session, buffer, MESSAGE_HEADER_LENGTH + responseEncoder.encodedLength());
    }

    void attemptErrorResponse(
        final long controlSessionId,
        final long correlationId,
        final long relevantId,
        final String errorMessage,
        final Publication controlPublication)
    {
        responseEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .relevantId(relevantId)
            .code(ControlResponseCode.ERROR)
            .errorMessage(errorMessage);

        final int length = MESSAGE_HEADER_LENGTH + responseEncoder.encodedLength();

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = controlPublication.offer(buffer, 0, length);
            if (result > 0)
            {
                break;
            }
        }
        while (--attempts > 0);
    }

    void attemptSendSignal(
        final long controlSessionId,
        final long correlationId,
        final long recordingId,
        final long subscriptionId,
        final long position,
        final RecordingSignal recordingSignal,
        final Publication controlPublication)
    {
        final int messageLength = MESSAGE_HEADER_LENGTH + RecordingSignalEventEncoder.BLOCK_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = controlPublication.tryClaim(messageLength, bufferClaim);
            if (result > 0)
            {
                final MutableDirectBuffer buffer = bufferClaim.buffer();
                final int bufferOffset = bufferClaim.offset();

                recordingSignalEventEncoder
                    .wrapAndApplyHeader(buffer, bufferOffset, messageHeaderEncoder)
                    .controlSessionId(controlSessionId)
                    .correlationId(correlationId)
                    .recordingId(recordingId)
                    .subscriptionId(subscriptionId)
                    .position(position)
                    .signal(recordingSignal);

                bufferClaim.commit();
                break;
            }
        }
        while (--attempts > 0);
    }

    private boolean send(final ControlSession session, final DirectBuffer buffer, final int length)
    {
        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = session.controlPublication().offer(buffer, 0, length);
            if (result > 0)
            {
                return true;
            }

            checkResult(session, result);
        }
        while (--attempts > 0);

        return false;
    }

    private static void checkResult(final ControlSession session, final long result)
    {
        if (result == Publication.NOT_CONNECTED)
        {
            session.abort();
            throw new ArchiveException("response publication is not connected: " + session);
        }

        if (result == Publication.CLOSED)
        {
            session.abort();
            throw new ArchiveException("response publication is closed: " + session);
        }

        if (result == Publication.MAX_POSITION_EXCEEDED)
        {
            session.abort();
            throw new ArchiveException("response publication at max position: " + session);
        }
    }
}
