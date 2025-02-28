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
import io.aeron.archive.client.ControlEventListener;
import io.aeron.archive.client.RecordingSignalAdapter;
import io.aeron.archive.client.RecordingSignalConsumer;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.Configuration;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.SystemUtil;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.status.CountersReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

import static io.aeron.archive.Common.*;
import static io.aeron.archive.codecs.RecordingSignal.*;
import static io.aeron.archive.codecs.SourceLocation.LOCAL;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

public class ExtendRecordingTest
{
    private static final String MESSAGE_PREFIX = "Message-Prefix-";
    private static final int MTU_LENGTH = Configuration.mtuLength();

    private static final int RECORDED_STREAM_ID = 33;
    private static final String RECORDED_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:3333")
        .termLength(TERM_BUFFER_LENGTH)
        .build();

    private static final int REPLAY_STREAM_ID = 66;
    private static final String REPLAY_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:6666")
        .build();

    private static final String EXTEND_CHANNEL = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:3333")
        .build();

    private ArchivingMediaDriver archivingMediaDriver;
    private Aeron aeron;
    private File archiveDir;
    private AeronArchive aeronArchive;

    private final RecordingSignalConsumer recordingSignalConsumer = mock(RecordingSignalConsumer.class);
    private final ArrayList<String> errors = new ArrayList<>();
    private final ControlEventListener controlEventListener =
        (controlSessionId, correlationId, relevantId, code, errorMessage) -> errors.add(errorMessage);

    @Before
    public void before()
    {
        launchAeronAndArchive();
    }

    @After
    public void after()
    {
        closeDownAndCleanMediaDriver();
        archivingMediaDriver.archive().context().deleteArchiveDirectory();
    }

    @Test(timeout = 10_000)
    public void shouldExtendRecordingAndReplay()
    {
        final long controlSessionId = aeronArchive.controlSessionId();
        final RecordingSignalAdapter recordingSignalAdapter;
        final int messageCount = 10;
        final long subscriptionIdOne;
        final long subscriptionIdTwo;
        final long stopOne;
        final long stopTwo;
        final long recordingId;
        final int initialTermId;

        try (Publication publication = aeron.addPublication(RECORDED_CHANNEL, RECORDED_STREAM_ID);
            Subscription subscription = aeron.addSubscription(RECORDED_CHANNEL, RECORDED_STREAM_ID))
        {
            initialTermId = publication.initialTermId();
            recordingSignalAdapter = new RecordingSignalAdapter(
                controlSessionId,
                controlEventListener,
                recordingSignalConsumer,
                aeronArchive.controlResponsePoller().subscription(),
                FRAGMENT_LIMIT);

            subscriptionIdOne = aeronArchive.startRecording(RECORDED_CHANNEL, RECORDED_STREAM_ID, LOCAL);
            pollForSignal(recordingSignalAdapter);

            try
            {
                offer(publication, 0, messageCount, MESSAGE_PREFIX);

                final CountersReader counters = aeron.countersReader();
                final int counterId = RecordingPos.findCounterIdBySession(counters, publication.sessionId());
                recordingId = RecordingPos.getRecordingId(counters, counterId);

                consume(subscription, 0, messageCount, MESSAGE_PREFIX);

                stopOne = publication.position();
                awaitPosition(counters, counterId, stopOne);
            }
            finally
            {
                aeronArchive.stopRecording(subscriptionIdOne);
                pollForSignal(recordingSignalAdapter);
            }
        }

        final String publicationExtendChannel = new ChannelUriStringBuilder()
            .media("udp")
            .endpoint("localhost:3333")
            .initialPosition(stopOne, initialTermId, TERM_BUFFER_LENGTH)
            .mtu(MTU_LENGTH)
            .build();

        try (Publication publication = aeron.addExclusivePublication(publicationExtendChannel, RECORDED_STREAM_ID);
            Subscription subscription = aeron.addSubscription(EXTEND_CHANNEL, RECORDED_STREAM_ID))
        {
            subscriptionIdTwo = aeronArchive.extendRecording(recordingId, EXTEND_CHANNEL, RECORDED_STREAM_ID, LOCAL);
            pollForSignal(recordingSignalAdapter);

            try
            {
                offer(publication, messageCount, messageCount, MESSAGE_PREFIX);

                final CountersReader counters = aeron.countersReader();
                final int counterId = RecordingPos.findCounterIdBySession(counters, publication.sessionId());

                consume(subscription, messageCount, messageCount, MESSAGE_PREFIX);

                stopTwo = publication.position();
                awaitPosition(counters, counterId, stopTwo);
            }
            finally
            {
                aeronArchive.stopRecording(subscriptionIdTwo);
                pollForSignal(recordingSignalAdapter);
            }
        }

        replay(messageCount, stopTwo, recordingId);
        assertEquals(Collections.EMPTY_LIST, errors);

        final InOrder inOrder = Mockito.inOrder(recordingSignalConsumer);
        inOrder.verify(recordingSignalConsumer).onSignal(
            eq(controlSessionId), anyLong(), eq(recordingId), eq(subscriptionIdOne), eq(0L), eq(START));
        inOrder.verify(recordingSignalConsumer).onSignal(
            eq(controlSessionId), anyLong(), eq(recordingId), eq(subscriptionIdOne), eq(stopOne), eq(STOP));
        inOrder.verify(recordingSignalConsumer).onSignal(
            eq(controlSessionId), anyLong(), eq(recordingId), eq(subscriptionIdTwo), eq(stopOne), eq(EXTEND));
        inOrder.verify(recordingSignalConsumer).onSignal(
            eq(controlSessionId), anyLong(), eq(recordingId), eq(subscriptionIdTwo), eq(stopTwo), eq(STOP));
    }

    private void replay(final int messageCount, final long secondStopPosition, final long recordingId)
    {
        final long fromPosition = 0L;
        final long length = secondStopPosition - fromPosition;

        try (Subscription subscription = aeronArchive.replay(
            recordingId, fromPosition, length, REPLAY_CHANNEL, REPLAY_STREAM_ID))
        {
            consume(subscription, 0, messageCount * 2, MESSAGE_PREFIX);
            assertEquals(secondStopPosition, subscription.imageAtIndex(0).position());
        }
    }

    private static void offer(
        final Publication publication, final int startIndex, final int count, final String prefix)
    {
        final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();

        for (int i = startIndex; i < (startIndex + count); i++)
        {
            final int length = buffer.putStringWithoutLengthAscii(0, prefix + i);

            while (publication.offer(buffer, 0, length) <= 0)
            {
                SystemTest.checkInterruptedStatus();
                Thread.yield();
            }
        }
    }

    private static void consume(
        final Subscription subscription, final int startIndex, final int count, final String prefix)
    {
        final MutableInteger received = new MutableInteger(startIndex);

        final FragmentHandler fragmentHandler = new FragmentAssembler(
            (buffer, offset, length, header) ->
            {
                final String expected = prefix + received.value;
                final String actual = buffer.getStringWithoutLengthAscii(offset, length);

                assertEquals(expected, actual);

                received.value++;
            });

        while (received.value < (startIndex + count))
        {
            if (0 == subscription.poll(fragmentHandler, FRAGMENT_LIMIT))
            {
                SystemTest.checkInterruptedStatus();
                Thread.yield();
            }
        }

        assertThat(received.get(), is(startIndex + count));
    }

    private void closeDownAndCleanMediaDriver()
    {
        CloseHelper.close(aeronArchive);
        CloseHelper.close(aeron);
        CloseHelper.close(archivingMediaDriver);
    }

    private void launchAeronAndArchive()
    {
        final String aeronDirectoryName = CommonContext.generateRandomDirName();

        if (null == archiveDir)
        {
            archiveDir = new File(SystemUtil.tmpDirName(), "archive");
        }

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
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .aeronDirectoryName(aeronDirectoryName)
                .archiveDir(archiveDir)
                .fileSyncLevel(0)
                .threadingMode(ArchiveThreadingMode.SHARED));

        aeron = Aeron.connect(
            new Aeron.Context()
                .aeronDirectoryName(aeronDirectoryName));

        aeronArchive = AeronArchive.connect(
            new AeronArchive.Context()
                .aeron(aeron));
    }
}
