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

import io.aeron.archive.codecs.*;
import org.agrona.ExpandableArrayBuffer;

class ControlRequestDecoders
{
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final ConnectRequestDecoder connectRequest = new ConnectRequestDecoder();
    final CloseSessionRequestDecoder closeSessionRequest = new CloseSessionRequestDecoder();
    final StartRecordingRequestDecoder startRecordingRequest = new StartRecordingRequestDecoder();
    final StopRecordingRequestDecoder stopRecordingRequest = new StopRecordingRequestDecoder();
    final ReplayRequestDecoder replayRequest = new ReplayRequestDecoder();
    final StopReplayRequestDecoder stopReplayRequest = new StopReplayRequestDecoder();
    final ListRecordingsRequestDecoder listRecordingsRequest = new ListRecordingsRequestDecoder();
    final ListRecordingsForUriRequestDecoder listRecordingsForUriRequest = new ListRecordingsForUriRequestDecoder();
    final ListRecordingRequestDecoder listRecordingRequest = new ListRecordingRequestDecoder();
    final ExtendRecordingRequestDecoder extendRecordingRequest = new ExtendRecordingRequestDecoder();
    final RecordingPositionRequestDecoder recordingPositionRequest = new RecordingPositionRequestDecoder();
    final TruncateRecordingRequestDecoder truncateRecordingRequest = new TruncateRecordingRequestDecoder();
    final StopRecordingSubscriptionRequestDecoder stopRecordingSubscriptionRequest =
        new StopRecordingSubscriptionRequestDecoder();
    final StopPositionRequestDecoder stopPositionRequest = new StopPositionRequestDecoder();
    final FindLastMatchingRecordingRequestDecoder findLastMatchingRecordingRequest =
        new FindLastMatchingRecordingRequestDecoder();
    final ListRecordingSubscriptionsRequestDecoder listRecordingSubscriptionsRequest =
        new ListRecordingSubscriptionsRequestDecoder();
    final BoundedReplayRequestDecoder boundedReplayRequest = new BoundedReplayRequestDecoder();
    final StopAllReplaysRequestDecoder stopAllReplaysRequest = new StopAllReplaysRequestDecoder();
    final ReplicateRequestDecoder replicateRequest = new ReplicateRequestDecoder();
    final StopReplicationRequestDecoder stopReplicationRequest = new StopReplicationRequestDecoder();
    final StartPositionRequestDecoder startPositionRequestDecoder = new StartPositionRequestDecoder();
    final DetachSegmentsRequestDecoder detachSegmentsRequestDecoder = new DetachSegmentsRequestDecoder();
    final DeleteDetachedSegmentsRequestDecoder deleteDetachedSegmentsRequestDecoder =
        new DeleteDetachedSegmentsRequestDecoder();
    final PurgeSegmentsRequestDecoder purgeSegmentsRequestDecoder = new PurgeSegmentsRequestDecoder();

    final ExpandableArrayBuffer tempBuffer = new ExpandableArrayBuffer();
}
