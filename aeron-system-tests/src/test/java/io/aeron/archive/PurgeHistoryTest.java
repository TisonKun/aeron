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
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.LogBufferDescriptor;
import org.agrona.CloseHelper;
import org.agrona.SystemUtil;
import org.agrona.concurrent.status.CountersReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static io.aeron.archive.Common.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class PurgeHistoryTest
{
    private static final int TERM_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int SEGMENT_LENGTH = TERM_LENGTH * 2;
    private static final int STREAM_ID = 33;

    private final ChannelUriStringBuilder uriBuilder = new ChannelUriStringBuilder()
        .media("udp")
        .endpoint("localhost:3333")
        .termLength(Common.TERM_BUFFER_LENGTH);

    private ArchivingMediaDriver archivingMediaDriver;
    private Aeron aeron;
    private AeronArchive aeronArchive;

    @Before
    public void before()
    {
        archivingMediaDriver = ArchivingMediaDriver.launch(
            new MediaDriver.Context()
                .publicationTermBufferLength(TERM_BUFFER_LENGTH)
                .termBufferSparseFile(true)
                .threadingMode(ThreadingMode.SHARED)
                .errorHandler(Throwable::printStackTrace)
                .spiesSimulateConnection(true)
                .dirDeleteOnShutdown(true)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(Common.MAX_CATALOG_ENTRIES)
                .segmentFileLength(SEGMENT_LENGTH)
                .deleteArchiveOnStart(true)
                .archiveDir(new File(SystemUtil.tmpDirName(), "archive"))
                .fileSyncLevel(0)
                .threadingMode(ArchiveThreadingMode.SHARED));

        aeron = Aeron.connect();

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
    public void shouldPurgeForStreamJoinedAtTheBeginning()
    {
        final String messagePrefix = "Message-Prefix-";
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;

        try (Publication publication = aeronArchive.addRecordedPublication(uriBuilder.build(), STREAM_ID))
        {
            final CountersReader counters = aeron.countersReader();
            final int counterId = Common.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            awaitPosition(counters, counterId, publication.position());

            aeronArchive.purgeSegments(recordingId, SEGMENT_LENGTH * 2L);
            assertThat(aeronArchive.getStartPosition(recordingId), is(SEGMENT_LENGTH * 2L));

            aeronArchive.stopRecording(publication);
        }
    }

    @Test(timeout = 10_000)
    public void shouldPurgeForLateJoinedStream()
    {
        final String messagePrefix = "Message-Prefix-";
        final int initialTermId = 7;
        final long targetPosition = (SEGMENT_LENGTH * 3L) + 1;
        final long initialPosition = (long)TERM_LENGTH + (FrameDescriptor.FRAME_ALIGNMENT * 2);
        uriBuilder.initialPosition(initialPosition, initialTermId, TERM_LENGTH);

        try (Publication publication = aeronArchive.addRecordedExclusivePublication(uriBuilder.build(), STREAM_ID))
        {
            assertThat(publication.position(), is(initialPosition));

            final CountersReader counters = aeron.countersReader();
            final int counterId = Common.awaitRecordingCounterId(counters, publication.sessionId());
            final long recordingId = RecordingPos.getRecordingId(counters, counterId);

            offerToPosition(publication, messagePrefix, targetPosition);
            awaitPosition(counters, counterId, publication.position());

            aeronArchive.purgeSegments(recordingId, TERM_LENGTH + (SEGMENT_LENGTH * 2L));
            assertThat(aeronArchive.getStartPosition(recordingId), is(TERM_LENGTH + (SEGMENT_LENGTH * 2L)));

            aeronArchive.stopRecording(publication);
        }
    }
}
