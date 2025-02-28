/*
 *  Copyright 2014-2019 Real Logic Ltd.
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
package io.aeron.cluster;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.CommonContext;
import io.aeron.Counter;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.ClusterException;
import io.aeron.cluster.codecs.mark.ClusterComponentType;
import io.aeron.cluster.service.ClusterMarkFile;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.exceptions.ConcurrentConcludeException;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.*;
import org.agrona.concurrent.errors.DistinctErrorLog;
import org.agrona.concurrent.errors.LoggingErrorHandler;
import org.agrona.concurrent.status.AtomicCounter;

import java.io.File;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Supplier;

import static io.aeron.CommonContext.ENDPOINT_PARAM_NAME;
import static io.aeron.cluster.ConsensusModule.Configuration.SERVICE_ID;
import static io.aeron.driver.status.SystemCounterDescriptor.SYSTEM_COUNTER_TYPE_ID;
import static java.util.concurrent.atomic.AtomicIntegerFieldUpdater.newUpdater;
import static org.agrona.SystemUtil.getDurationInNanos;

/**
 * Backup component which can run remote from a cluster which polls for snapshots and replicates the log.
 */
public final class ClusterBackup implements AutoCloseable
{
    /**
     * The type id of the {@link Counter} used for the backup state.
     */
    static final int BACKUP_STATE_TYPE_ID = 208;

    /**
     * The type id of the {@link Counter} used for the live log position counter.
     */
    static final int LIVE_LOG_POSITION_TYPE_ID = 209;

    /**
     * The type id of the {@link Counter} used for the next query deadline counter.
     */
    static final int QUERY_DEADLINE_TYPE_ID = 210;

    enum State
    {
        CHECK_BACKUP(0),
        BACKUP_QUERY(1),
        SNAPSHOT_RETRIEVE(2),
        LIVE_LOG_REPLAY(3),
        UPDATE_RECORDING_LOG(4),
        RESET_BACKUP(5),
        BACKING_UP(6);

        static final State[] STATES;

        static
        {
            final State[] states = values();
            STATES = new State[states.length];
            for (final State state : states)
            {
                final int code = state.code();
                if (null != STATES[code])
                {
                    throw new ClusterException("code already in use: " + code);
                }

                STATES[code] = state;
            }
        }

        private final int code;

        State(final int code)
        {
            this.code = code;
        }

        public int code()
        {
            return code;
        }

        public static State get(final int code)
        {
            if (code < 0 || code > (STATES.length - 1))
            {
                throw new ClusterException("invalid state counter code: " + code);
            }

            return STATES[code];
        }
    }

    private final ClusterBackup.Context ctx;
    private final AgentInvoker clusterBackupAgentInvoker;
    private final AgentRunner clusterBackupAgentRunner;

    private ClusterBackup(final ClusterBackup.Context ctx)
    {
        this.ctx = ctx;

        try
        {
            ctx.conclude();
        }
        catch (final Throwable ex)
        {
            ctx.close();
            throw ex;
        }

        final ClusterBackupAgent clusterBackupAgent = new ClusterBackupAgent(ctx);

        if (ctx.useAgentInvoker())
        {
            clusterBackupAgentRunner = null;
            clusterBackupAgentInvoker = new AgentInvoker(ctx.errorHandler(), ctx.errorCounter(), clusterBackupAgent);
        }
        else
        {
            clusterBackupAgentRunner = new AgentRunner(
                ctx.idleStrategy(), ctx.errorHandler(), ctx.errorCounter(), clusterBackupAgent);
            clusterBackupAgentInvoker = null;
        }
    }

    private ClusterBackup start()
    {
        if (null != clusterBackupAgentRunner)
        {
            AgentRunner.startOnThread(clusterBackupAgentRunner, ctx.threadFactory());
        }
        else
        {
            clusterBackupAgentInvoker.start();
        }

        return this;
    }

    /**
     * Launch an {@link ClusterBackup} using a default configuration.
     *
     * @return a new instance of an {@link ClusterBackup}.
     */
    public static ClusterBackup launch()
    {
        return launch(new ClusterBackup.Context());
    }

    /**
     * Launch an {@link ClusterBackup} by providing a configuration context.
     *
     * @param ctx for the configuration parameters.
     * @return a new instance of an {@link ClusterBackup}.
     */
    public static ClusterBackup launch(final ClusterBackup.Context ctx)
    {
        return new ClusterBackup(ctx).start();
    }

    /**
     * Get the {@link ClusterBackup.Context} that is used by this {@link ClusterBackup}.
     *
     * @return the {@link ClusterBackup.Context} that is used by this {@link ClusterBackup}.
     */
    public ClusterBackup.Context context()
    {
        return ctx;
    }

    /**
     * Get the {@link AgentInvoker} for the cluster backup.
     *
     * @return the {@link AgentInvoker} for the cluster backup.
     */
    public AgentInvoker conductorAgentInvoker()
    {
        return clusterBackupAgentInvoker;
    }

    public void close()
    {
        CloseHelper.close(clusterBackupAgentRunner);
        CloseHelper.close(clusterBackupAgentInvoker);
    }

    /**
     * Configuration options for {@link ClusterBackup} with defaults and constants for system properties lookup.
     */
    public static class Configuration
    {
        public static final String MEMBER_STATUS_CHANNEL_DEFAULT;
        public static final String TRANSFER_ENDPOINT_DEFAULT;

        /**
         * Interval at which a cluster backup will send backup queries.
         */
        public static final String CLUSTER_BACKUP_INTERVAL_PROP_NAME = "aeron.cluster.backup.interval";

        /**
         * Default interval at which a cluster backup will send backup queries.
         */
        public static final long CLUSTER_BACKUP_INTERVAL_DEFAULT_NS = TimeUnit.HOURS.toNanos(1);

        /**
         * Timeout within which a cluster backup will expect a response from a backup query.
         */
        public static final String CLUSTER_BACKUP_RESPONSE_TIMEOUT_PROP_NAME = "aeron.cluster.backup.response.timeout";

        /**
         * Default timeout within which a cluster backup will expect a response from a backup query.
         */
        public static final long CLUSTER_BACKUP_RESPONSE_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(2);

        static
        {
            final ClusterMember[] clusterMembers = ClusterMember.parse(ConsensusModule.Configuration.clusterMembers());
            final Int2ObjectHashMap<ClusterMember> clusterMemberByIdMap = new Int2ObjectHashMap<>();

            ClusterMember.addClusterMemberIds(clusterMembers, clusterMemberByIdMap);

            final ClusterMember member = ClusterMember.determineMember(
                clusterMembers,
                ConsensusModule.Configuration.clusterMemberId(),
                ConsensusModule.Configuration.memberEndpoints());

            final ChannelUri memberStatusUri = ChannelUri.parse(ConsensusModule.Configuration.memberStatusChannel());
            memberStatusUri.put(ENDPOINT_PARAM_NAME, member.memberFacingEndpoint());

            MEMBER_STATUS_CHANNEL_DEFAULT = memberStatusUri.toString();
            TRANSFER_ENDPOINT_DEFAULT = member.transferEndpoint();
        }

        /**
         * Interval at which a cluster backup will send backup queries.
         *
         * @return Interval at which a cluster backup will send backup queries.
         * @see #CLUSTER_BACKUP_INTERVAL_PROP_NAME
         */
        public static long clusterBackupIntervalNs()
        {
            return getDurationInNanos(CLUSTER_BACKUP_INTERVAL_PROP_NAME, CLUSTER_BACKUP_INTERVAL_DEFAULT_NS);
        }

        /**
         * Timeout within which a cluster backup will expect a response from a backup query.
         *
         * @return timeout within which a cluster backup wil;l expect a response from a backup query.
         * @see #CLUSTER_BACKUP_RESPONSE_TIMEOUT_PROP_NAME
         */
        public static long clusterBackupResponseTimeoutNs()
        {
            return getDurationInNanos(
                CLUSTER_BACKUP_RESPONSE_TIMEOUT_PROP_NAME, CLUSTER_BACKUP_RESPONSE_TIMEOUT_DEFAULT_NS);
        }
    }

    /**
     * Context for overriding default configuration for {@link ClusterBackup}.
     */
    public static class Context
    {
        private static final AtomicIntegerFieldUpdater<Context> IS_CONCLUDED_UPDATER = newUpdater(
            Context.class, "isConcluded");
        private volatile int isConcluded;

        private boolean ownsAeronClient = false;
        private String aeronDirectoryName = CommonContext.getAeronDirectoryName();
        private Aeron aeron;

        private String memberStatusChannel = Configuration.MEMBER_STATUS_CHANNEL_DEFAULT;
        private int memberStatusStreamId = ConsensusModule.Configuration.memberStatusStreamId();
        private int replayStreamId = ClusteredServiceContainer.Configuration.replayStreamId();
        private String transferEndpoint = Configuration.TRANSFER_ENDPOINT_DEFAULT;

        private long clusterBackupIntervalNs = Configuration.clusterBackupIntervalNs();
        private long clusterBackupResponseTimeoutNs = Configuration.clusterBackupResponseTimeoutNs();
        private int errorBufferLength = ConsensusModule.Configuration.errorBufferLength();

        private boolean deleteDirOnStart = false;
        private boolean useAgentInvoker = false;
        private String clusterDirectoryName = ClusteredServiceContainer.Configuration.clusterDirName();
        private File clusterDir;
        private ClusterMarkFile markFile;
        private String clusterMembersStatusEndpoints = ConsensusModule.Configuration.clusterMembersStatusEndpoints();
        private ThreadFactory threadFactory;
        private EpochClock epochClock;
        private Supplier<IdleStrategy> idleStrategySupplier;

        private DistinctErrorLog errorLog;
        private ErrorHandler errorHandler;
        private AtomicCounter errorCounter;
        private CountedErrorHandler countedErrorHandler;
        private Counter stateCounter;
        private Counter liveLogPositionCounter;
        private Counter nextQueryDeadlineMsCounter;

        private AeronArchive.Context archiveContext;
        private ShutdownSignalBarrier shutdownSignalBarrier;
        private Runnable terminationHook;
        private ClusterBackupEventsListener eventsListener;

        /**
         * Perform a shallow copy of the object.
         *
         * @return a shallow copy of the object.
         */
        public Context clone()
        {
            try
            {
                return (Context)super.clone();
            }
            catch (final CloneNotSupportedException ex)
            {
                throw new RuntimeException(ex);
            }
        }

        @SuppressWarnings("MethodLength")
        public void conclude()
        {
            if (0 != IS_CONCLUDED_UPDATER.getAndSet(this, 1))
            {
                throw new ConcurrentConcludeException();
            }

            if (null == clusterDir)
            {
                clusterDir = new File(clusterDirectoryName);
            }

            if (deleteDirOnStart && clusterDir.exists())
            {
                IoUtil.delete(clusterDir, false);
            }

            if (!clusterDir.exists() && !clusterDir.mkdirs())
            {
                throw new ClusterException("failed to create cluster dir: " + clusterDir.getAbsolutePath());
            }

            if (null == epochClock)
            {
                epochClock = new SystemEpochClock();
            }

            if (null == markFile)
            {
                markFile = new ClusterMarkFile(
                    new File(clusterDir, ClusterMarkFile.FILENAME),
                    ClusterComponentType.BACKUP,
                    errorBufferLength,
                    epochClock,
                    0);
            }

            if (null == errorLog)
            {
                errorLog = new DistinctErrorLog(markFile.errorBuffer(), epochClock);
            }

            if (null == errorHandler)
            {
                errorHandler = new LoggingErrorHandler(errorLog);
            }

            if (null == aeron)
            {
                ownsAeronClient = true;

                aeron = Aeron.connect(
                    new Aeron.Context()
                        .aeronDirectoryName(aeronDirectoryName)
                        .errorHandler(errorHandler)
                        .epochClock(epochClock)
                        .useConductorAgentInvoker(true)
                        .clientLock(NoOpLock.INSTANCE));

                if (null == errorCounter)
                {
                    errorCounter = aeron.addCounter(SYSTEM_COUNTER_TYPE_ID, "ClusterBackup errors");
                }
            }

            if (null == aeron.conductorAgentInvoker())
            {
                throw new ClusterException("Aeron client must use conductor agent invoker");
            }

            if (null == errorCounter)
            {
                throw new ClusterException("error counter must be supplied if aeron client is");
            }

            if (null == countedErrorHandler)
            {
                countedErrorHandler = new CountedErrorHandler(errorHandler, errorCounter);
                if (ownsAeronClient)
                {
                    aeron.context().errorHandler(countedErrorHandler);
                }
            }

            if (null == stateCounter)
            {
                stateCounter = aeron.addCounter(BACKUP_STATE_TYPE_ID, "Backup State");
            }

            if (null == liveLogPositionCounter)
            {
                liveLogPositionCounter = aeron.addCounter(LIVE_LOG_POSITION_TYPE_ID, "Live Log Position");
            }

            if (null == nextQueryDeadlineMsCounter)
            {
                nextQueryDeadlineMsCounter = aeron.addCounter(QUERY_DEADLINE_TYPE_ID, "Next Query Deadline (ms)");
            }

            if (null == threadFactory)
            {
                threadFactory = Thread::new;
            }

            if (null == idleStrategySupplier)
            {
                idleStrategySupplier = ClusteredServiceContainer.Configuration.idleStrategySupplier(null);
            }

            if (null == archiveContext)
            {
                archiveContext = new AeronArchive.Context()
                    .controlRequestChannel(AeronArchive.Configuration.localControlChannel())
                    .controlResponseChannel(AeronArchive.Configuration.localControlChannel())
                    .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId());
            }

            archiveContext
                .aeron(aeron)
                .errorHandler(errorHandler)
                .ownsAeronClient(false)
                .lock(new NoOpLock());

            if (null == shutdownSignalBarrier)
            {
                shutdownSignalBarrier = new ShutdownSignalBarrier();
            }

            if (null == terminationHook)
            {
                terminationHook = () -> shutdownSignalBarrier.signal();
            }

            concludeMarkFile();
        }

        /**
         * {@link Aeron} client for communicating with the local Media Driver.
         * <p>
         * This client will be closed when the {@link ClusterBackup#close()} or {@link #close()} methods are called
         * if {@link #ownsAeronClient()} is true.
         *
         * @param aeron client for communicating with the local Media Driver.
         * @return this for a fluent API.
         * @see Aeron#connect()
         */
        public Context aeron(final Aeron aeron)
        {
            this.aeron = aeron;
            return this;
        }

        /**
         * {@link Aeron} client for communicating with the local Media Driver.
         * <p>
         * If not provided then a default will be established during {@link #conclude()} by calling
         * {@link Aeron#connect()}.
         *
         * @return client for communicating with the local Media Driver.
         */
        public Aeron aeron()
        {
            return aeron;
        }

        /**
         * Set the top level Aeron directory used for communication between the Aeron client and Media Driver.
         *
         * @param aeronDirectoryName the top level Aeron directory.
         * @return this for a fluent API.
         */
        public Context aeronDirectoryName(final String aeronDirectoryName)
        {
            this.aeronDirectoryName = aeronDirectoryName;
            return this;
        }

        /**
         * Get the top level Aeron directory used for communication between the Aeron client and Media Driver.
         *
         * @return The top level Aeron directory.
         */
        public String aeronDirectoryName()
        {
            return aeronDirectoryName;
        }

        /**
         * Does this context own the {@link #aeron()} client and this takes responsibility for closing it?
         *
         * @param ownsAeronClient does this context own the {@link #aeron()} client.
         * @return this for a fluent API.
         */
        public Context ownsAeronClient(final boolean ownsAeronClient)
        {
            this.ownsAeronClient = ownsAeronClient;
            return this;
        }

        /**
         * Does this context own the {@link #aeron()} client and this takes responsibility for closing it?
         *
         * @return does this context own the {@link #aeron()} client and this takes responsibility for closing it?
         */
        public boolean ownsAeronClient()
        {
            return ownsAeronClient;
        }

        /**
         * Should the consensus module attempt to immediately delete {@link #clusterDir()} on startup.
         *
         * @param deleteDirOnStart Attempt deletion.
         * @return this for a fluent API.
         */
        public Context deleteDirOnStart(final boolean deleteDirOnStart)
        {
            this.deleteDirOnStart = deleteDirOnStart;
            return this;
        }

        /**
         * Will the consensus module attempt to immediately delete {@link #clusterDir()} on startup.
         *
         * @return true when directory will be deleted, otherwise false.
         */
        public boolean deleteDirOnStart()
        {
            return deleteDirOnStart;
        }

        /**
         * Set the directory name to use for the cluster directory.
         *
         * @param clusterDirectoryName to use.
         * @return this for a fluent API.
         * @see io.aeron.cluster.service.ClusteredServiceContainer.Configuration#CLUSTER_DIR_PROP_NAME
         */
        public Context clusterDirectoryName(final String clusterDirectoryName)
        {
            this.clusterDirectoryName = clusterDirectoryName;
            return this;
        }

        /**
         * The directory name to use for the cluster directory.
         *
         * @return directory name for the cluster directory.
         * @see io.aeron.cluster.service.ClusteredServiceContainer.Configuration#CLUSTER_DIR_PROP_NAME
         */
        public String clusterDirectoryName()
        {
            return clusterDirectoryName;
        }

        /**
         * Set the directory to use for the cluster directory.
         *
         * @param clusterDir to use.
         * @return this for a fluent API.
         * @see io.aeron.cluster.service.ClusteredServiceContainer.Configuration#CLUSTER_DIR_PROP_NAME
         */
        public Context clusterDir(final File clusterDir)
        {
            this.clusterDir = clusterDir;
            return this;
        }

        /**
         * The directory used for for the cluster directory.
         *
         * @return directory for for the cluster directory.
         * @see io.aeron.cluster.service.ClusteredServiceContainer.Configuration#CLUSTER_DIR_PROP_NAME
         */
        public File clusterDir()
        {
            return clusterDir;
        }

        /**
         * Set the {@link io.aeron.archive.client.AeronArchive.Context} that should be used for communicating with the
         * local Archive.
         *
         * @param archiveContext that should be used for communicating with the local Archive.
         * @return this for a fluent API.
         */
        public Context archiveContext(final AeronArchive.Context archiveContext)
        {
            this.archiveContext = archiveContext;
            return this;
        }

        /**
         * Get the {@link io.aeron.archive.client.AeronArchive.Context} that should be used for communicating with
         * the local Archive.
         *
         * @return the {@link io.aeron.archive.client.AeronArchive.Context} that should be used for communicating
         * with the local Archive.
         */
        public AeronArchive.Context archiveContext()
        {
            return archiveContext;
        }

        /**
         * Get the thread factory used for creating threads.
         *
         * @return thread factory used for creating threads.
         */
        public ThreadFactory threadFactory()
        {
            return threadFactory;
        }

        /**
         * Set the thread factory used for creating threads.
         *
         * @param threadFactory used for creating threads
         * @return this for a fluent API.
         */
        public Context threadFactory(final ThreadFactory threadFactory)
        {
            this.threadFactory = threadFactory;
            return this;
        }

        /**
         * Provides an {@link IdleStrategy} supplier for the idle strategy for the agent duty cycle.
         *
         * @param idleStrategySupplier supplier for the idle strategy for the agent duty cycle.
         * @return this for a fluent API.
         */
        public ClusterBackup.Context idleStrategySupplier(final Supplier<IdleStrategy> idleStrategySupplier)
        {
            this.idleStrategySupplier = idleStrategySupplier;
            return this;
        }

        /**
         * Get a new {@link IdleStrategy} based on configured supplier.
         *
         * @return a new {@link IdleStrategy} based on configured supplier.
         */
        public IdleStrategy idleStrategy()
        {
            return idleStrategySupplier.get();
        }

        /**
         * Set the {@link EpochClock} to be used for tracking wall clock time.
         *
         * @param clock {@link EpochClock} to be used for tracking wall clock time.
         * @return this for a fluent API.
         */
        public Context epochClock(final EpochClock clock)
        {
            this.epochClock = clock;
            return this;
        }

        /**
         * Get the {@link EpochClock} to used for tracking wall clock time.
         *
         * @return the {@link EpochClock} to used for tracking wall clock time.
         */
        public EpochClock epochClock()
        {
            return epochClock;
        }

        /**
         * Get the {@link ErrorHandler} to be used by the Consensus Module.
         *
         * @return the {@link ErrorHandler} to be used by the Consensus Module.
         */
        public ErrorHandler errorHandler()
        {
            return errorHandler;
        }

        /**
         * Set the {@link ErrorHandler} to be used by the Cluster Backup.
         *
         * @param errorHandler the error handler to be used by the Cluster Backup.
         * @return this for a fluent API
         */
        public Context errorHandler(final ErrorHandler errorHandler)
        {
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Get the error counter that will record the number of errors observed.
         *
         * @return the error counter that will record the number of errors observed.
         */
        public AtomicCounter errorCounter()
        {
            return errorCounter;
        }

        /**
         * Set the error counter that will record the number of errors observed.
         *
         * @param errorCounter the error counter that will record the number of errors observed.
         * @return this for a fluent API.
         */
        public Context errorCounter(final AtomicCounter errorCounter)
        {
            this.errorCounter = errorCounter;
            return this;
        }

        /**
         * Non-default for context.
         *
         * @param countedErrorHandler to override the default.
         * @return this for a fluent API.
         */
        public Context countedErrorHandler(final CountedErrorHandler countedErrorHandler)
        {
            this.countedErrorHandler = countedErrorHandler;
            return this;
        }

        /**
         * The {@link #errorHandler()} that will increment {@link #errorCounter()} by default.
         *
         * @return {@link #errorHandler()} that will increment {@link #errorCounter()} by default.
         */
        public CountedErrorHandler countedErrorHandler()
        {
            return countedErrorHandler;
        }

        /**
         * Set the channel parameter for the member status communication channel.
         *
         * @param channel parameter for the member status communication channel.
         * @return this for a fluent API.
         * @see ConsensusModule.Configuration#MEMBER_STATUS_CHANNEL_PROP_NAME
         */
        public Context memberStatusChannel(final String channel)
        {
            memberStatusChannel = channel;
            return this;
        }

        /**
         * Get the channel parameter for the member status communication channel.
         *
         * @return the channel parameter for the member status communication channel.
         * @see ConsensusModule.Configuration#MEMBER_STATUS_CHANNEL_PROP_NAME
         */
        public String memberStatusChannel()
        {
            return memberStatusChannel;
        }

        /**
         * Set the stream id for the member status channel.
         *
         * @param streamId for the ingress channel.
         * @return this for a fluent API
         * @see ConsensusModule.Configuration#MEMBER_STATUS_STREAM_ID_PROP_NAME
         */
        public Context memberStatusStreamId(final int streamId)
        {
            memberStatusStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the member status channel.
         *
         * @return the stream id for the member status channel.
         * @see ConsensusModule.Configuration#MEMBER_STATUS_STREAM_ID_PROP_NAME
         */
        public int memberStatusStreamId()
        {
            return memberStatusStreamId;
        }

        /**
         * Set the stream id for the cluster log and snapshot replay channel.
         *
         * @param streamId for the cluster log replay channel.
         * @return this for a fluent API
         * @see io.aeron.cluster.service.ClusteredServiceContainer.Configuration#REPLAY_STREAM_ID_PROP_NAME
         */
        public Context replayStreamId(final int streamId)
        {
            replayStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the cluster log and snapshot replay channel.
         *
         * @return the stream id for the cluster log replay channel.
         * @see io.aeron.cluster.service.ClusteredServiceContainer.Configuration#REPLAY_STREAM_ID_PROP_NAME
         */
        public int replayStreamId()
        {
            return replayStreamId;
        }

        /**
         * Set the transfer endpoint to use for snapshot and log retrieval.
         *
         * @param transferEndpoint to use for the snapshot and log retrieval.
         * @return transfer endpoint to use for the snapshot and log retrieval.
         * @see Configuration#TRANSFER_ENDPOINT_DEFAULT
         */
        public Context transferEndpoint(final String transferEndpoint)
        {
            this.transferEndpoint = transferEndpoint;
            return this;
        }

        /**
         * Get the transfer endpoint to use for snapshot and log retrieval.
         *
         * @return transfer endpoint to use for the snapshot and log retrieval.
         * @see Configuration#TRANSFER_ENDPOINT_DEFAULT
         */
        public String transferEndpoint()
        {
            return transferEndpoint;
        }

        /**
         * Interval at which a cluster backup will send backup queries.
         *
         * @param clusterBackupIntervalNs between add cluster members and snapshot recording queries.
         * @return this for a fluent API.
         * @see Configuration#CLUSTER_BACKUP_INTERVAL_PROP_NAME
         * @see Configuration#CLUSTER_BACKUP_INTERVAL_DEFAULT_NS
         */
        public Context clusterBackupIntervalNs(final long clusterBackupIntervalNs)
        {
            this.clusterBackupIntervalNs = clusterBackupIntervalNs;
            return this;
        }

        /**
         * Interval at which a cluster backup will send backup queries.
         *
         * @return the interval at which a cluster backup will send backup queries.
         * @see Configuration#CLUSTER_BACKUP_INTERVAL_PROP_NAME
         * @see Configuration#CLUSTER_BACKUP_INTERVAL_DEFAULT_NS
         */
        public long clusterBackupIntervalNs()
        {
            return clusterBackupIntervalNs;
        }

        /**
         * Timeout within which a cluster backup will expect a response from a backup query.
         *
         * @param clusterBackupResponseTimeoutNs within which a cluster backup will expect a response.
         * @return this for a fluent API.
         * @see Configuration#CLUSTER_BACKUP_RESPONSE_TIMEOUT_PROP_NAME
         * @see Configuration#CLUSTER_BACKUP_RESPONSE_TIMEOUT_DEFAULT_NS
         */
        public Context clusterBackupResponseTimeoutNs(final long clusterBackupResponseTimeoutNs)
        {
            this.clusterBackupResponseTimeoutNs = clusterBackupResponseTimeoutNs;
            return this;
        }

        /**
         * Timeout within which a cluster backup will expect a response from a backup query.
         *
         * @return timeout within which a cluster backup will expect a response from a backup query.
         * @see Configuration#CLUSTER_BACKUP_RESPONSE_TIMEOUT_PROP_NAME
         * @see Configuration#CLUSTER_BACKUP_RESPONSE_TIMEOUT_DEFAULT_NS
         */
        public long clusterBackupResponseTimeoutNs()
        {
            return clusterBackupResponseTimeoutNs;
        }

        /**
         * String representing the cluster members member status endpoints.
         * <p>
         * {@code "endpoint,endpoint,endpoint"}
         * <p>
         *
         * @param endpoints which are to be contacted for joining the cluster.
         * @return this for a fluent API.
         */
        public Context clusterMembersStatusEndpoints(final String endpoints)
        {
            this.clusterMembersStatusEndpoints = endpoints;
            return this;
        }

        /**
         * The endpoints representing cluster members of the cluster to attempt to contact to backup from.
         *
         * @return members of the cluster to attempt to request to backup from.
         */
        public String clusterMembersStatusEndpoints()
        {
            return clusterMembersStatusEndpoints;
        }

        /**
         * Set the {@link ShutdownSignalBarrier} that can be used to shutdown a consensus module.
         *
         * @param barrier that can be used to shutdown a consensus module.
         * @return this for a fluent API.
         */
        public Context shutdownSignalBarrier(final ShutdownSignalBarrier barrier)
        {
            shutdownSignalBarrier = barrier;
            return this;
        }

        /**
         * Get the {@link ShutdownSignalBarrier} that can be used to shutdown.
         *
         * @return the {@link ShutdownSignalBarrier} that can be used to shutdown.
         */
        public ShutdownSignalBarrier shutdownSignalBarrier()
        {
            return shutdownSignalBarrier;
        }

        /**
         * Set the {@link Runnable} that is called when the {@link ClusterBackup} processes a termination action.
         *
         * @param terminationHook that can be used to terminate.
         * @return this for a fluent API.
         */
        public Context terminationHook(final Runnable terminationHook)
        {
            this.terminationHook = terminationHook;
            return this;
        }

        /**
         * Get the {@link Runnable} that is called when the {@link ClusterBackup} processes a termination action.
         * <p>
         * The default action is to call signal on the {@link #shutdownSignalBarrier()}.
         *
         * @return the {@link Runnable} that can be used to terminate.
         */
        public Runnable terminationHook()
        {
            return terminationHook;
        }

        /**
         * Set the {@link ClusterMarkFile} in use.
         *
         * @param markFile to use.
         * @return this for a fluent API.
         */
        public Context clusterMarkFile(final ClusterMarkFile markFile)
        {
            this.markFile = markFile;
            return this;
        }

        /**
         * The {@link ClusterMarkFile} in use.
         *
         * @return {@link ClusterMarkFile} in use.
         */
        public ClusterMarkFile clusterMarkFile()
        {
            return markFile;
        }

        /**
         * Set the error buffer length in bytes to use.
         *
         * @param errorBufferLength in bytes to use.
         * @return this for a fluent API.
         */
        public Context errorBufferLength(final int errorBufferLength)
        {
            this.errorBufferLength = errorBufferLength;
            return this;
        }

        /**
         * The error buffer length in bytes.
         *
         * @return error buffer length in bytes.
         */
        public int errorBufferLength()
        {
            return errorBufferLength;
        }

        /**
         * Set the {@link DistinctErrorLog} in use.
         *
         * @param errorLog to use.
         * @return this for a fluent API.
         */
        public Context errorLog(final DistinctErrorLog errorLog)
        {
            this.errorLog = errorLog;
            return this;
        }

        /**
         * The {@link DistinctErrorLog} in use.
         *
         * @return {@link DistinctErrorLog} in use.
         */
        public DistinctErrorLog errorLog()
        {
            return errorLog;
        }

        /**
         * Get the counter for the current state of the cluster backup.
         *
         * @return the counter for the current state of the cluster backup.
         * @see ClusterBackup.State
         */
        public Counter stateCounter()
        {
            return stateCounter;
        }

        /**
         * Set the counter for the current state of the cluster backup.
         *
         * @param stateCounter the counter for the current state of the cluster backup.
         * @return this for a fluent API.
         * @see ClusterBackup.State
         */
        public Context stateCounter(final Counter stateCounter)
        {
            this.stateCounter = stateCounter;
            return this;
        }

        /**
         * Get the counter for the current position of the live log.
         *
         * @return the counter for the current position of the live log.
         */
        public Counter liveLogPositionCounter()
        {
            return liveLogPositionCounter;
        }

        /**
         * Set the counter for the current position of the live log.
         *
         * @param liveLogPositionCounter the counter for the current position of the live log.
         * @return this for a fluent API.
         */
        public Context liveLogPositionCounter(final Counter liveLogPositionCounter)
        {
            this.liveLogPositionCounter = liveLogPositionCounter;
            return this;
        }

        /**
         * Get the counter for the next query deadline ms.
         *
         * @return the counter for the next query deadline ms.
         */
        public Counter nextQueryDeadlineMsCounter()
        {
            return nextQueryDeadlineMsCounter;
        }

        /**
         * Set the counter for the next query deadline ms.
         *
         * @param nextQueryDeadlineMsCounter the counter for the next query deadline ms.
         * @return this for a fluent API.
         */
        public Context nextQueryDeadlineMsCounter(final Counter nextQueryDeadlineMsCounter)
        {
            this.nextQueryDeadlineMsCounter = nextQueryDeadlineMsCounter;
            return this;
        }

        /**
         * Get the {@link ClusterBackupEventsListener} in use for the backup agent.
         *
         * @return {@link ClusterBackupEventsListener} in use for the backup agent.
         * @see ClusterBackupEventsListener
         */
        public ClusterBackupEventsListener eventsListener()
        {
            return eventsListener;
        }

        /**
         * Set the {@link ClusterBackupEventsListener} to use for the backup agent.
         *
         * @param eventsListener to use for the backup agent.
         * @return this for a fluent API.
         * @see ClusterBackupEventsListener
         */
        public Context eventsListener(final ClusterBackupEventsListener eventsListener)
        {
            this.eventsListener = eventsListener;
            return this;
        }

        /**
         * Should an {@link AgentInvoker} be used for running the {@link ClusterBackup} rather than run it on
         * a thread with a {@link AgentRunner}.
         *
         * @param useAgentInvoker use {@link AgentInvoker} be used for running the {@link ClusterBackup}?
         * @return this for a fluent API.
         */
        public Context useAgentInvoker(final boolean useAgentInvoker)
        {
            this.useAgentInvoker = useAgentInvoker;
            return this;
        }

        /**
         * Should an {@link AgentInvoker} be used for running the {@link ClusterBackup} rather than run it on
         * a thread with a {@link AgentRunner}.
         *
         * @return true if the {@link ClusterBackup} will be run with an {@link AgentInvoker} otherwise false.
         */
        public boolean useAgentInvoker()
        {
            return useAgentInvoker;
        }

        /**
         * Delete the cluster directory.
         */
        public void deleteDirectory()
        {
            if (null != clusterDir)
            {
                IoUtil.delete(clusterDir, false);
            }
        }

        /**
         * Close the context and free applicable resources.
         * <p>
         * If {@link #ownsAeronClient()} is true then the {@link #aeron()} client will be closed.
         */
        public void close()
        {
            if (ownsAeronClient)
            {
                CloseHelper.close(aeron);
            }
            else
            {
                CloseHelper.close(stateCounter);
                CloseHelper.close(liveLogPositionCounter);
            }

            CloseHelper.close(markFile);
        }

        private void concludeMarkFile()
        {
            ClusterMarkFile.checkHeaderLength(
                aeron.context().aeronDirectoryName(),
                archiveContext.controlRequestChannel(),
                "",
                "",
                null,
                "");

            markFile.encoder()
                .archiveStreamId(archiveContext.controlRequestStreamId())
                .serviceStreamId(ClusteredServiceContainer.Configuration.serviceStreamId())
                .consensusModuleStreamId(ClusteredServiceContainer.Configuration.consensusModuleStreamId())
                .ingressStreamId(AeronCluster.Configuration.ingressStreamId())
                .memberId(-1)
                .serviceId(SERVICE_ID)
                .aeronDirectory(aeron.context().aeronDirectoryName())
                .archiveChannel(archiveContext.controlRequestChannel())
                .serviceControlChannel("")
                .ingressChannel("")
                .serviceName("")
                .authenticator("");

            markFile.updateActivityTimestamp(epochClock.time());
            markFile.signalReady();
        }
    }
}
