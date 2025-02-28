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

import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingSubscriptionDescriptorConsumer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FrameDescriptor;
import org.agrona.concurrent.ManyToOneConcurrentLinkedQueue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.aeron.archive.codecs.SourceLocation.LOCAL;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class ArchiveTest
{
    @Test
    public void shouldGenerateRecordingName()
    {
        final long recordingId = 1L;
        final long segmentPosition = 2 * 64 * 1024;
        final String expected = "1-" + (2 * 64 * 1024) + ".rec";

        final String actual = Archive.segmentFileName(recordingId, segmentPosition);

        assertThat(actual, is(expected));
    }

    @Test
    public void shouldCalculateCorrectSegmentFilePosition()
    {
        final int termLength = 64 * 1024;
        final int segmentLength = termLength * 8;

        long startPosition = 0;
        long position = 0;
        assertThat(Archive.segmentFilePosition(startPosition, position, termLength, segmentLength), is(0L));

        startPosition = 0;
        position = termLength * 2;
        assertThat(Archive.segmentFilePosition(startPosition, position, termLength, segmentLength), is(0L));

        startPosition = 0;
        position = segmentLength;
        assertThat(
            Archive.segmentFilePosition(startPosition, position, termLength, segmentLength),
            is((long)segmentLength));

        startPosition = termLength;
        position = termLength;
        assertThat(Archive.segmentFilePosition(startPosition, position, termLength, segmentLength), is(startPosition));

        startPosition = termLength * 4;
        position = termLength * 4;
        assertThat(Archive.segmentFilePosition(startPosition, position, termLength, segmentLength), is(startPosition));

        startPosition = termLength;
        position = termLength + segmentLength;
        assertThat(
            Archive.segmentFilePosition(startPosition, position, termLength, segmentLength),
            is((long)(termLength + segmentLength)));

        startPosition = termLength;
        position = termLength + segmentLength - FrameDescriptor.FRAME_ALIGNMENT;
        assertThat(
            Archive.segmentFilePosition(startPosition, position, termLength, segmentLength),
            is((long)termLength));

        startPosition = termLength + FrameDescriptor.FRAME_ALIGNMENT;
        position = termLength + segmentLength;
        assertThat(
            Archive.segmentFilePosition(startPosition, position, termLength, segmentLength),
            is((long)(termLength + segmentLength)));
    }

    @Test
    public void shouldAllowMultipleConnectionsInParallel() throws InterruptedException
    {
        final int numberOfArchiveClients = 5;
        final long connectTimeoutNs = TimeUnit.SECONDS.toNanos(10);
        final CountDownLatch latch = new CountDownLatch(numberOfArchiveClients);
        final ThreadPoolExecutor executor = (ThreadPoolExecutor)Executors.newFixedThreadPool(numberOfArchiveClients);
        final ManyToOneConcurrentLinkedQueue<AeronArchive> archiveClientQueue = new ManyToOneConcurrentLinkedQueue<>();
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .errorHandler(Throwable::printStackTrace)
            .clientLivenessTimeoutNs(connectTimeoutNs)
            .dirDeleteOnShutdown(true)
            .dirDeleteOnStart(true)
            .publicationUnblockTimeoutNs(connectTimeoutNs * 2)
            .threadingMode(ThreadingMode.SHARED);
        final Archive.Context archiveCtx = new Archive.Context()
            .threadingMode(ArchiveThreadingMode.SHARED)
            .connectTimeoutNs(connectTimeoutNs);
        executor.prestartAllCoreThreads();

        try (ArchivingMediaDriver driver = ArchivingMediaDriver.launch(driverCtx, archiveCtx))
        {
            for (int i = 0; i < numberOfArchiveClients; i++)
            {
                executor.execute(
                    () ->
                    {
                        final AeronArchive.Context ctx = new AeronArchive.Context().messageTimeoutNs(connectTimeoutNs);
                        final AeronArchive archive = AeronArchive.connect(ctx);
                        archiveClientQueue.add(archive);
                        latch.countDown();
                    });
            }

            latch.await(driver.archive().context().connectTimeoutNs() * 2, TimeUnit.NANOSECONDS);

            AeronArchive archiveClient;
            while (null != (archiveClient = archiveClientQueue.poll()))
            {
                archiveClient.close();
            }

            assertThat(latch.getCount(), is(0L));
        }
        finally
        {
            executor.shutdownNow();
            archiveCtx.deleteArchiveDirectory();
        }
    }

    @Test(timeout = 10_000L)
    public void shouldListRegisteredRecordingSubscriptions()
    {
        final int expectedStreamId = 7;
        final String channelOne = "aeron:ipc";
        final String channelTwo = "aeron:udp?endpoint=localhost:5678";
        final String channelThree = "aeron:udp?endpoint=localhost:4321";

        final ArrayList<SubscriptionDescriptor> descriptors = new ArrayList<>();
        @SuppressWarnings("Indentation") final RecordingSubscriptionDescriptorConsumer consumer =
            (controlSessionId, correlationId, subscriptionId, streamId, strippedChannel) ->
                descriptors.add(new SubscriptionDescriptor(
                    controlSessionId, correlationId, subscriptionId, streamId, strippedChannel));

        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED);
        final Archive.Context archiveCtx = new Archive.Context().threadingMode(ArchiveThreadingMode.SHARED);

        try (ArchivingMediaDriver ignore = ArchivingMediaDriver.launch(driverCtx, archiveCtx);
            AeronArchive archive = AeronArchive.connect())
        {
            final long subIdOne = archive.startRecording(channelOne, expectedStreamId, LOCAL);
            final long subIdTwo = archive.startRecording(channelTwo, expectedStreamId + 1, LOCAL);
            final long subOdThree = archive.startRecording(channelThree, expectedStreamId + 2, LOCAL);

            final int countOne = archive.listRecordingSubscriptions(
                0, 5, "ipc", expectedStreamId, true, consumer);

            assertEquals(1, descriptors.size());
            assertEquals(1, countOne);

            descriptors.clear();
            final int countTwo = archive.listRecordingSubscriptions(
                0, 5, "", expectedStreamId, false, consumer);

            assertEquals(3, descriptors.size());
            assertEquals(3, countTwo);

            archive.stopRecording(subIdTwo);

            descriptors.clear();
            final int countThree = archive.listRecordingSubscriptions(
                0, 5, "", expectedStreamId, false, consumer);

            assertEquals(2, descriptors.size());
            assertEquals(2, countThree);

            assertEquals(1L, descriptors.stream().filter((sd) -> sd.subscriptionId == subIdOne).count());
            assertEquals(1L, descriptors.stream().filter((sd) -> sd.subscriptionId == subOdThree).count());
        }
        finally
        {
            archiveCtx.deleteArchiveDirectory();
        }
    }

    static final class SubscriptionDescriptor
    {
        final long controlSessionId;
        final long correlationId;
        final long subscriptionId;
        final int streamId;
        final String strippedChannel;

        private SubscriptionDescriptor(
            final long controlSessionId,
            final long correlationId,
            final long subscriptionId,
            final int streamId,
            final String strippedChannel)
        {
            this.controlSessionId = controlSessionId;
            this.correlationId = correlationId;
            this.subscriptionId = subscriptionId;
            this.streamId = streamId;
            this.strippedChannel = strippedChannel;
        }

        public String toString()
        {
            return "SubscriptionDescriptor{" +
                "controlSessionId=" + controlSessionId +
                ", correlationId=" + correlationId +
                ", subscriptionId=" + subscriptionId +
                ", streamId=" + streamId +
                ", strippedChannel='" + strippedChannel + '\'' +
                '}';
        }
    }
}
