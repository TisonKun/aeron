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

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.SystemUtil;
import org.agrona.concurrent.status.CountersReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static io.aeron.Aeron.NULL_VALUE;
import static io.aeron.archive.Common.*;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static io.aeron.archive.codecs.SourceLocation.LOCAL;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class BasicArchiveTest
{
    private static final int RECORDED_STREAM_ID = 33;
    private static final String RECORDED_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:3333")
        .termLength(Common.TERM_BUFFER_LENGTH)
        .build();

    private static final int REPLAY_STREAM_ID = 66;
    private static final String REPLAY_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:6666")
        .build();

    private ArchivingMediaDriver archivingMediaDriver;
    private Aeron aeron;
    private AeronArchive aeronArchive;

    @Before
    public void before()
    {
        final String aeronDirectoryName = CommonContext.generateRandomDirName();

        archivingMediaDriver = ArchivingMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .termBufferSparseFile(true)
                .threadingMode(ThreadingMode.SHARED)
                .errorHandler(Throwable::printStackTrace)
                .spiesSimulateConnection(false)
                .dirDeleteOnShutdown(true)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(Common.MAX_CATALOG_ENTRIES)
                .aeronDirectoryName(aeronDirectoryName)
                .deleteArchiveOnStart(true)
                .archiveDir(new File(SystemUtil.tmpDirName(), "archive"))
                .fileSyncLevel(0)
                .threadingMode(ArchiveThreadingMode.SHARED));

        aeron = Aeron.connect(
            new Aeron.Context()
                .aeronDirectoryName(aeronDirectoryName));

        aeronArchive = AeronArchive.connect(
            new AeronArchive.Context()
                .aeron(aeron));
    }

    @After
    public void after()
    {
        CloseHelper.close(aeronArchive);
        CloseHelper.close(aeron);
        CloseHelper.close(archivingMediaDriver);

        archivingMediaDriver.archive().context().deleteArchiveDirectory();
    }

    @Test(timeout = 10_000)
    public void shouldRecordThenReplayThenTruncate()
    {
        final String messagePrefix = "Message-Prefix-";
        final int messageCount = 10;
        final long stopPosition;

        final long subscriptionId = aeronArchive.startRecording(RECORDED_CHANNEL, RECORDED_STREAM_ID, LOCAL);
        final long recordingIdFromCounter;
        final int sessionId;

        try (Subscription subscription = aeron.addSubscription(RECORDED_CHANNEL, RECORDED_STREAM_ID);
            Publication publication = aeron.addPublication(RECORDED_CHANNEL, RECORDED_STREAM_ID))
        {
            sessionId = publication.sessionId();

            final CountersReader counters = aeron.countersReader();
            final int counterId = awaitRecordingCounterId(counters, sessionId);
            recordingIdFromCounter = RecordingPos.getRecordingId(counters, counterId);

            assertThat(RecordingPos.getSourceIdentity(counters, counterId), is(CommonContext.IPC_CHANNEL));

            offer(publication, messageCount, messagePrefix);
            consume(subscription, messageCount, messagePrefix);

            stopPosition = publication.position();
            awaitPosition(counters, counterId, stopPosition);

            final long joinPosition = subscription.imageBySessionId(sessionId).joinPosition();
            assertThat(aeronArchive.getStartPosition(recordingIdFromCounter), is(joinPosition));
            assertThat(aeronArchive.getRecordingPosition(recordingIdFromCounter), is(stopPosition));
            assertThat(aeronArchive.getStopPosition(recordingIdFromCounter), is((long)NULL_VALUE));
        }

        aeronArchive.stopRecording(subscriptionId);

        final long recordingId = aeronArchive.findLastMatchingRecording(
            0, "endpoint=localhost:3333", RECORDED_STREAM_ID, sessionId);

        assertEquals(recordingIdFromCounter, recordingId);
        assertThat(aeronArchive.getStopPosition(recordingIdFromCounter), is(stopPosition));

        final long position = 0L;
        final long length = stopPosition - position;

        try (Subscription subscription = aeronArchive.replay(
            recordingId, position, length, REPLAY_CHANNEL, REPLAY_STREAM_ID))
        {
            consume(subscription, messageCount, messagePrefix);
            assertEquals(stopPosition, subscription.imageAtIndex(0).position());
        }

        aeronArchive.truncateRecording(recordingId, position);

        final int count = aeronArchive.listRecording(
            recordingId,
            (controlSessionId,
            correlationId,
            recordingId1,
            startTimestamp,
            stopTimestamp,
            startPosition,
            newStopPosition,
            initialTermId,
            segmentFileLength,
            termBufferLength,
            mtuLength,
            sessionId1,
            streamId,
            strippedChannel,
            originalChannel,
            sourceIdentity) -> assertEquals(startPosition, newStopPosition));

        assertEquals(1, count);
    }

    @Test(timeout = 10_000)
    public void shouldRecordReplayAndCancelReplayEarly()
    {
        final String messagePrefix = "Message-Prefix-";
        final long stopPosition;
        final int messageCount = 10;
        final long recordingId;

        try (Subscription subscription = aeron.addSubscription(RECORDED_CHANNEL, RECORDED_STREAM_ID);
            Publication publication = aeronArchive.addRecordedPublication(RECORDED_CHANNEL, RECORDED_STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int counterId = Common.awaitRecordingCounterId(counters, publication.sessionId());
            recordingId = RecordingPos.getRecordingId(counters, counterId);

            offer(publication, messageCount, messagePrefix);
            consume(subscription, messageCount, messagePrefix);

            stopPosition = publication.position();
            awaitPosition(counters, counterId, stopPosition);

            assertThat(aeronArchive.getRecordingPosition(recordingId), is(stopPosition));

            aeronArchive.stopRecording(publication);
            while (NULL_POSITION != aeronArchive.getRecordingPosition(recordingId))
            {
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }
        }

        final long position = 0L;
        final long length = stopPosition - position;

        final long replaySessionId = aeronArchive.startReplay(
            recordingId, position, length, REPLAY_CHANNEL, REPLAY_STREAM_ID);

        aeronArchive.stopReplay(replaySessionId);
    }

    @Test(timeout = 10_000)
    public void shouldReplayRecordingFromLateJoinPosition()
    {
        final String messagePrefix = "Message-Prefix-";
        final int messageCount = 10;

        final long subscriptionId = aeronArchive.startRecording(RECORDED_CHANNEL, RECORDED_STREAM_ID, LOCAL);

        try (Subscription subscription = aeron.addSubscription(RECORDED_CHANNEL, RECORDED_STREAM_ID);
            Publication publication = aeron.addPublication(RECORDED_CHANNEL, RECORDED_STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int counterId = Common.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offer(publication, messageCount, messagePrefix);
            consume(subscription, messageCount, messagePrefix);

            final long currentPosition = publication.position();
            awaitPosition(counters, counterId, currentPosition);

            try (Subscription replaySubscription = aeronArchive.replay(
                recordingId, currentPosition, AeronArchive.NULL_LENGTH, REPLAY_CHANNEL, REPLAY_STREAM_ID))
            {
                offer(publication, messageCount, messagePrefix);
                consume(subscription, messageCount, messagePrefix);
                consume(replaySubscription, messageCount, messagePrefix);

                final long endPosition = publication.position();
                assertEquals(endPosition, replaySubscription.imageAtIndex(0).position());
            }
        }

        aeronArchive.stopRecording(subscriptionId);
    }
}
