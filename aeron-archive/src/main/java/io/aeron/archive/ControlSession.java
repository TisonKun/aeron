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

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.archive.codecs.ControlResponseCode;
import io.aeron.archive.codecs.RecordingSignal;
import io.aeron.archive.codecs.SourceLocation;
import org.agrona.CloseHelper;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

import static io.aeron.archive.client.ArchiveException.GENERIC;
import static io.aeron.archive.codecs.ControlResponseCode.*;

/**
 * Control sessions are interacted with from the {@link ArchiveConductor}. The interaction may result in pending
 * send actions being queued for execution by the {@link ArchiveConductor}.
 */
class ControlSession implements Session
{
    private static final long RESEND_INTERVAL_MS = 200L;

    enum State
    {
        INIT, CONNECTED, ACTIVE, INACTIVE, CLOSED
    }

    private final int majorVersion;
    private final long controlSessionId;
    private final long correlationId;
    private final long connectTimeoutMs;
    private long resendDeadlineMs;
    private long activityDeadlineMs;
    private Session activeListing = null;
    private final ArchiveConductor conductor;
    private final EpochClock epochClock;
    private final ControlResponseProxy controlResponseProxy;
    private final ArrayDeque<BooleanSupplier> queuedResponses = new ArrayDeque<>(8);
    private final ControlSessionDemuxer demuxer;
    private final Publication controlPublication;
    private final String invalidVersionMessage;
    private State state = State.INIT;

    ControlSession(
        final int majorVersion,
        final long controlSessionId,
        final long correlationId,
        final long connectTimeoutMs,
        final String invalidVersionMessage,
        final ControlSessionDemuxer demuxer,
        final Publication controlPublication,
        final ArchiveConductor conductor,
        final EpochClock epochClock,
        final ControlResponseProxy controlResponseProxy)
    {
        this.majorVersion = majorVersion;
        this.controlSessionId = controlSessionId;
        this.correlationId = correlationId;
        this.connectTimeoutMs = connectTimeoutMs;
        this.invalidVersionMessage = invalidVersionMessage;
        this.demuxer = demuxer;
        this.controlPublication = controlPublication;
        this.conductor = conductor;
        this.epochClock = epochClock;
        this.controlResponseProxy = controlResponseProxy;
        this.activityDeadlineMs = epochClock.time() + connectTimeoutMs;
    }

    public int majorVersion()
    {
        return majorVersion;
    }

    public long sessionId()
    {
        return controlSessionId;
    }

    public void abort()
    {
        state(State.INACTIVE);
        if (null != activeListing)
        {
            activeListing.abort();
        }
    }

    public void close()
    {
        if (null != activeListing)
        {
            activeListing.abort();
        }

        CloseHelper.close(controlPublication);
        demuxer.removeControlSession(this);
        state(State.CLOSED);
    }

    public boolean isDone()
    {
        return state == State.INACTIVE;
    }

    public int doWork()
    {
        int workCount = 0;

        switch (state)
        {
            case INIT:
                workCount += waitForConnection();
                break;

            case CONNECTED:
                workCount += sendConnectResponse();
                break;

            case ACTIVE:
                workCount = sendQueuedResponses();
                break;
        }

        return workCount;
    }

    ArchiveConductor archiveConductor()
    {
        return conductor;
    }

    Publication controlPublication()
    {
        return controlPublication;
    }

    boolean hasActiveListing()
    {
        return null != activeListing;
    }

    void activeListing(final Session activeListing)
    {
        this.activeListing = activeListing;
    }

    void onStopRecording(final long correlationId, final int streamId, final String channel)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.stopRecording(correlationId, streamId, channel, this);
        }
    }

    void onStopRecordingSubscription(final long correlationId, final long subscriptionId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.stopRecordingSubscription(correlationId, subscriptionId, this);
        }
    }

    void onStartRecording(
        final long correlationId, final int streamId, final SourceLocation sourceLocation, final String channel)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.startRecording(correlationId, streamId, sourceLocation, channel, this);
        }
    }

    void onListRecordingsForUri(
        final long correlationId,
        final long fromRecordingId,
        final int recordCount,
        final int streamId,
        final byte[] channelFragment)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.newListRecordingsForUriSession(
                correlationId,
                fromRecordingId,
                recordCount,
                streamId,
                channelFragment,
                this);
        }
    }

    void onListRecordings(final long correlationId, final long fromRecordingId, final int recordCount)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.newListRecordingsSession(correlationId, fromRecordingId, recordCount, this);
        }
    }

    void onListRecording(final long correlationId, final long recordingId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.listRecording(correlationId, recordingId, this);
        }
    }

    void onFindLastMatchingRecording(
        final long correlationId,
        final long minRecordingId,
        final int sessionId,
        final int streamId,
        final byte[] channelFragment)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.findLastMatchingRecording(
                correlationId,
                minRecordingId,
                sessionId,
                streamId,
                channelFragment,
                this);
        }
    }

    void onStartReplay(
        final long correlationId,
        final long recordingId,
        final long position,
        final long length,
        final int replayStreamId,
        final String replayChannel)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.startReplay(
                correlationId, recordingId, position, length, replayStreamId, replayChannel, this);
        }
    }

    void onStartBoundedReplay(
        final long correlationId,
        final long recordingId,
        final long position,
        final long length,
        final int limitCounterId,
        final int replayStreamId,
        final String replayChannel)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.startBoundedReplay(
                correlationId,
                recordingId,
                position,
                length,
                limitCounterId,
                replayStreamId,
                replayChannel,
                this);
        }
    }

    void onStopReplay(final long correlationId, final long replaySessionId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.stopReplay(correlationId, replaySessionId, this);
        }
    }

    void onStopAllReplays(final long correlationId, final long recordingId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.stopAllReplays(correlationId, recordingId, this);
        }
    }

    void onExtendRecording(
        final long correlationId,
        final long recordingId,
        final int streamId,
        final SourceLocation sourceLocation,
        final String channel)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.extendRecording(correlationId, recordingId, streamId, sourceLocation, channel, this);
        }
    }

    void onGetRecordingPosition(final long correlationId, final long recordingId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.getRecordingPosition(correlationId, recordingId, this);
        }
    }

    void onTruncateRecording(final long correlationId, final long recordingId, final long position)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.truncateRecording(correlationId, recordingId, position, this);
        }
    }

    void onGetStopPosition(final long correlationId, final long recordingId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.getStopPosition(correlationId, recordingId, this);
        }
    }

    void onListRecordingSubscriptions(
        final long correlationId,
        final int pseudoIndex,
        final int subscriptionCount,
        final boolean applyStreamId,
        final int streamId,
        final String channelFragment)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.listRecordingSubscriptions(
                correlationId,
                pseudoIndex,
                subscriptionCount,
                applyStreamId,
                streamId,
                channelFragment,
                this);
        }
    }

    void onReplicate(
        final long correlationId,
        final long srcRecordingId,
        final long dstRecordingId,
        final int srcControlStreamId,
        final String srcControlChannel,
        final String liveDestination)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.replicate(
                correlationId,
                srcRecordingId,
                dstRecordingId,
                srcControlStreamId,
                srcControlChannel,
                liveDestination,
                this);
        }
    }

    void onStopReplication(final long correlationId, final long replicationId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.stopReplication(correlationId, replicationId, this);
        }
    }

    void onGetStartPosition(final long correlationId, final long recordingId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.getStartPosition(correlationId, recordingId, this);
        }
    }

    void onDetachSegments(final long correlationId, final long recordingId, final long newStartPosition)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.detachSegments(correlationId, recordingId, newStartPosition, this);
        }
    }

    void onDeleteDetachedSegments(final long correlationId, final long recordingId)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.deleteDetachedSegments(correlationId, recordingId, this);
        }
    }

    void onPurgeSegments(final long correlationId, final long recordingId, final long newStartPosition)
    {
        updateState();
        if (State.ACTIVE == state)
        {
            conductor.purgeSegments(correlationId, recordingId, newStartPosition, this);
        }
    }

    void sendOkResponse(final long correlationId, final ControlResponseProxy proxy)
    {
        sendResponse(correlationId, 0L, OK, null, proxy);
    }

    void sendOkResponse(final long correlationId, final long relevantId, final ControlResponseProxy proxy)
    {
        sendResponse(correlationId, relevantId, OK, null, proxy);
    }

    void sendErrorResponse(final long correlationId, final String errorMessage, final ControlResponseProxy proxy)
    {
        sendResponse(correlationId, 0L, ERROR, errorMessage, proxy);
    }

    void sendErrorResponse(
        final long correlationId, final long relevantId, final String errorMessage, final ControlResponseProxy proxy)
    {
        sendResponse(correlationId, relevantId, ERROR, errorMessage, proxy);
    }

    void sendRecordingUnknown(final long correlationId, final long recordingId, final ControlResponseProxy proxy)
    {
        sendResponse(correlationId, recordingId, RECORDING_UNKNOWN, null, proxy);
    }

    void sendSubscriptionUnknown(final long correlationId, final ControlResponseProxy proxy)
    {
        sendResponse(correlationId, 0L, SUBSCRIPTION_UNKNOWN, null, proxy);
    }

    void sendResponse(
        final long correlationId,
        final long relevantId,
        final ControlResponseCode code,
        final String errorMessage,
        final ControlResponseProxy proxy)
    {
        if (!proxy.sendResponse(controlSessionId, correlationId, relevantId, code, errorMessage, this))
        {
            queueResponse(correlationId, relevantId, code, errorMessage);
        }
    }

    void attemptErrorResponse(final long correlationId, final String errorMessage, final ControlResponseProxy proxy)
    {
        proxy.attemptErrorResponse(controlSessionId, correlationId, GENERIC, errorMessage, controlPublication);
    }

    void attemptErrorResponse(
        final long correlationId, final long relevantId, final String errorMessage, final ControlResponseProxy proxy)
    {
        proxy.attemptErrorResponse(controlSessionId, correlationId, relevantId, errorMessage, controlPublication);
    }

    int sendDescriptor(final long correlationId, final UnsafeBuffer descriptorBuffer, final ControlResponseProxy proxy)
    {
        return proxy.sendDescriptor(controlSessionId, correlationId, descriptorBuffer, this);
    }

    boolean sendSubscriptionDescriptor(
        final long correlationId, final Subscription subscription, final ControlResponseProxy proxy)
    {
        return proxy.sendSubscriptionDescriptor(controlSessionId, correlationId, subscription, this);
    }

    void attemptSendSignal(
        final long correlationId,
        final long recordingId,
        final long subscriptionId,
        final long position,
        final RecordingSignal recordingSignal)
    {
        controlResponseProxy.attemptSendSignal(
            controlSessionId,
            correlationId,
            recordingId,
            subscriptionId,
            position,
            recordingSignal,
            controlPublication);
    }

    int maxPayloadLength()
    {
        return controlPublication.maxPayloadLength();
    }

    private int sendQueuedResponses()
    {
        int workCount = 0;
        if (!controlPublication.isConnected())
        {
            state(State.INACTIVE);
        }
        else
        {
            if (!queuedResponses.isEmpty())
            {
                if (queuedResponses.peekFirst().getAsBoolean())
                {
                    queuedResponses.pollFirst();
                    activityDeadlineMs = Aeron.NULL_VALUE;
                    workCount++;
                }
                else if (activityDeadlineMs == Aeron.NULL_VALUE)
                {
                    activityDeadlineMs = epochClock.time() + connectTimeoutMs;
                }
                else if (hasNoActivity(epochClock.time()))
                {
                    state(State.INACTIVE);
                }
            }
        }

        return workCount;
    }

    private void queueResponse(
        final long correlationId, final long relevantId, final ControlResponseCode code, final String message)
    {
        queuedResponses.offer(() -> controlResponseProxy.sendResponse(
            controlSessionId,
            correlationId,
            relevantId,
            code,
            message,
            this));
    }

    private int waitForConnection()
    {
        int workCount = 0;

        if (controlPublication.isConnected())
        {
            state(State.CONNECTED);
            workCount += 1;
        }
        else if (hasNoActivity(epochClock.time()))
        {
            state(State.INACTIVE);
            workCount += 1;
        }

        return workCount;
    }

    private int sendConnectResponse()
    {
        int workCount = 0;

        final long nowMs = epochClock.time();
        if (hasNoActivity(nowMs))
        {
            state(State.INACTIVE);
            workCount += 1;
        }
        else if (nowMs > resendDeadlineMs)
        {
            resendDeadlineMs = nowMs + RESEND_INTERVAL_MS;
            if (null != invalidVersionMessage)
            {
                controlResponseProxy.sendResponse(
                    controlSessionId,
                    correlationId,
                    controlSessionId,
                    ERROR,
                    invalidVersionMessage,
                    this);

                workCount += 1;
            }
            else if (controlResponseProxy.sendResponse(
                controlSessionId,
                correlationId,
                controlSessionId,
                OK,
                null,
                this))
            {
                activityDeadlineMs = Aeron.NULL_VALUE;
                workCount += 1;
            }
        }

        return workCount;
    }

    private boolean hasNoActivity(final long nowMs)
    {
        return Aeron.NULL_VALUE != activityDeadlineMs & nowMs > activityDeadlineMs;
    }

    private void updateState()
    {
        if (State.CONNECTED == state && null == invalidVersionMessage)
        {
            state(State.ACTIVE);
        }
    }

    private void state(final State state)
    {
        //System.out.println(controlSessionId + ": " + this.state + " -> " + state);
        this.state = state;
    }

    public String toString()
    {
        return "ControlSession{" +
            "controlSessionId=" + controlSessionId +
            ", state=" + state +
            ", controlPublication=" + controlPublication +
            '}';
    }
}
