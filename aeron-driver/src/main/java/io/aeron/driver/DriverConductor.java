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
package io.aeron.driver;

import io.aeron.ChannelUri;
import io.aeron.CommonContext;
import io.aeron.CommonContext.InferableBoolean;
import io.aeron.driver.MediaDriver.Context;
import io.aeron.driver.buffer.LogFactory;
import io.aeron.driver.buffer.RawLog;
import io.aeron.exceptions.ControlProtocolException;
import io.aeron.driver.media.*;
import io.aeron.driver.status.*;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.status.ChannelEndpointStatus;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Object2ObjectHashMap;
import org.agrona.collections.ObjectHashSet;
import org.agrona.concurrent.*;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.status.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static io.aeron.CommonContext.IPC_MEDIA;
import static io.aeron.CommonContext.InferableBoolean.FORCE_TRUE;
import static io.aeron.CommonContext.InferableBoolean.INFER;
import static io.aeron.ErrorCode.*;
import static io.aeron.driver.PublicationParams.*;
import static io.aeron.driver.status.SystemCounterDescriptor.*;
import static io.aeron.logbuffer.LogBufferDescriptor.*;
import static io.aeron.protocol.DataHeaderFlyweight.createDefaultHeader;
import static org.agrona.collections.ArrayListUtil.fastUnorderedRemove;

/**
 * Driver Conductor that takes commands from publishers and subscribers and orchestrates the media driver.
 */
public class DriverConductor implements Agent
{
    private static final long CLOCK_UPDATE_DURATION_NS = TimeUnit.MILLISECONDS.toNanos(1);

    private int nextSessionId = BitUtil.generateRandomisedId();

    private final long timerIntervalNs;
    private final long clientLivenessTimeoutNs;
    private final long statusMessageTimeoutNs;
    private long timeOfLastToDriverPositionChangeNs;
    private long lastConsumerCommandPosition;
    private long timeOfLastTimerCheckNs;
    private long clockUpdateDeadlineNs;

    private final Context ctx;
    private final LogFactory logFactory;
    private final ReceiverProxy receiverProxy;
    private final SenderProxy senderProxy;
    private final ClientProxy clientProxy;
    private final RingBuffer toDriverCommands;
    private final ClientCommandAdapter clientCommandAdapter;
    private final ManyToOneConcurrentArrayQueue<Runnable> driverCmdQueue;
    private final Object2ObjectHashMap<String, SendChannelEndpoint> sendChannelEndpointByChannelMap =
        new Object2ObjectHashMap<>();
    private final Object2ObjectHashMap<String, ReceiveChannelEndpoint> receiveChannelEndpointByChannelMap =
        new Object2ObjectHashMap<>();
    private final ArrayList<NetworkPublication> networkPublications = new ArrayList<>();
    private final ArrayList<IpcPublication> ipcPublications = new ArrayList<>();
    private final ArrayList<PublicationImage> publicationImages = new ArrayList<>();
    private final ArrayList<PublicationLink> publicationLinks = new ArrayList<>();
    private final ArrayList<SubscriptionLink> subscriptionLinks = new ArrayList<>();
    private final ArrayList<CounterLink> counterLinks = new ArrayList<>();
    private final ArrayList<AeronClient> clients = new ArrayList<>();
    private final ObjectHashSet<SessionKey> activeSessionSet = new ObjectHashSet<>();
    private final EpochClock epochClock;
    private final NanoClock nanoClock;
    private final CachedEpochClock cachedEpochClock;
    private final CachedNanoClock cachedNanoClock;
    private final CountersManager countersManager;
    private final NetworkPublicationThreadLocals networkPublicationThreadLocals = new NetworkPublicationThreadLocals();
    private final MutableDirectBuffer tempBuffer;
    private final DataHeaderFlyweight defaultDataHeader = new DataHeaderFlyweight(createDefaultHeader(0, 0, 0));

    public DriverConductor(final Context ctx)
    {
        this.ctx = ctx;
        timerIntervalNs = ctx.timerIntervalNs();
        clientLivenessTimeoutNs = ctx.clientLivenessTimeoutNs();
        statusMessageTimeoutNs = ctx.statusMessageTimeoutNs();
        driverCmdQueue = ctx.driverCommandQueue();
        receiverProxy = ctx.receiverProxy();
        senderProxy = ctx.senderProxy();
        logFactory = ctx.logFactory();
        epochClock = ctx.epochClock();
        nanoClock = ctx.nanoClock();
        cachedEpochClock = ctx.cachedEpochClock();
        cachedNanoClock = ctx.cachedNanoClock();
        toDriverCommands = ctx.toDriverCommands();
        clientProxy = ctx.clientProxy();
        tempBuffer = ctx.tempBuffer();

        countersManager = ctx.countersManager();

        clientCommandAdapter = new ClientCommandAdapter(
            ctx.systemCounters().get(ERRORS),
            ctx.errorHandler(),
            toDriverCommands,
            clientProxy,
            this);

        final long nowNs = nanoClock.nanoTime();
        cachedNanoClock.update(nowNs);
        cachedEpochClock.update(epochClock.time());
        timeOfLastTimerCheckNs = nowNs;
        timeOfLastToDriverPositionChangeNs = nowNs;
        lastConsumerCommandPosition = toDriverCommands.consumerPosition();
    }

    public void onClose()
    {
        publicationImages.forEach(PublicationImage::free);
        networkPublications.forEach(NetworkPublication::free);
        ipcPublications.forEach(IpcPublication::free);

        ctx.close();
    }

    public String roleName()
    {
        return "driver-conductor";
    }

    public int doWork()
    {
        int workCount = 0;

        final long nowNs = nanoClock.nanoTime();
        updateClocks(nowNs);
        workCount += processTimers(nowNs);

        workCount += clientCommandAdapter.receive();
        workCount += driverCmdQueue.drain(Runnable::run, Configuration.COMMAND_DRAIN_LIMIT);

        final ArrayList<PublicationImage> publicationImages = this.publicationImages;
        for (int i = 0, size = publicationImages.size(); i < size; i++)
        {
            final PublicationImage image = publicationImages.get(i);
            if (image.isTrackingRebuild())
            {
                image.trackRebuild(nowNs, statusMessageTimeoutNs);
            }
        }

        final ArrayList<NetworkPublication> networkPublications = this.networkPublications;
        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);
            if (publication.state() == NetworkPublication.State.ACTIVE)
            {
                workCount += publication.updatePublisherLimit();
            }
        }

        final ArrayList<IpcPublication> ipcPublications = this.ipcPublications;
        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            if (publication.state() == IpcPublication.State.ACTIVE)
            {
                workCount += publication.updatePublisherLimit();
            }
        }

        return workCount;
    }

    public void onCreatePublicationImage(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int activeTermId,
        final int initialTermOffset,
        final int termBufferLength,
        final int senderMtuLength,
        final int transportIndex,
        final InetSocketAddress controlAddress,
        final InetSocketAddress sourceAddress,
        final ReceiveChannelEndpoint channelEndpoint)
    {
        Configuration.validateMtuLength(senderMtuLength);
        Configuration.validateInitialWindowLength(ctx.initialWindowLength(), senderMtuLength);

        final long joinPosition = computePosition(
            activeTermId, initialTermOffset, LogBufferDescriptor.positionBitsToShift(termBufferLength), initialTermId);

        final ArrayList<SubscriberPosition> subscriberPositions = createSubscriberPositions(
            sessionId, streamId, channelEndpoint, joinPosition);

        if (subscriberPositions.size() > 0)
        {
            final UdpChannel udpChannel = channelEndpoint.udpChannel();
            final String channel = udpChannel.originalUriString();
            final long registrationId = toDriverCommands.nextCorrelationId();
            final RawLog rawLog = newPublicationImageLog(
                sessionId,
                streamId,
                initialTermId,
                termBufferLength,
                isOldestSubscriptionSparse(subscriberPositions),
                senderMtuLength,
                udpChannel,
                registrationId);

            final CongestionControl congestionControl = ctx.congestionControlSupplier().newInstance(
                registrationId,
                udpChannel,
                streamId,
                sessionId,
                termBufferLength,
                senderMtuLength,
                controlAddress,
                sourceAddress,
                cachedNanoClock,
                ctx,
                countersManager);

            final InferableBoolean groupSubscription = subscriberPositions.get(0).subscription().group();
            final boolean treatAsMulticast =
                groupSubscription == INFER ? udpChannel.isMulticast() : groupSubscription == FORCE_TRUE;

            final FeedbackDelayGenerator feedbackDelayGenerator =
                treatAsMulticast ? ctx.multicastFeedbackDelayGenerator() : ctx.unicastFeedbackDelayGenerator();

            final PublicationImage image = new PublicationImage(
                registrationId,
                ctx.imageLivenessTimeoutNs(),
                ctx.untetheredWindowLimitTimeoutNs(),
                ctx.untetheredRestingTimeoutNs(),
                channelEndpoint,
                transportIndex,
                controlAddress,
                sessionId,
                streamId,
                initialTermId,
                activeTermId,
                initialTermOffset,
                rawLog,
                feedbackDelayGenerator,
                subscriberPositions,
                ReceiverHwm.allocate(tempBuffer, countersManager, registrationId, sessionId, streamId, channel),
                ReceiverPos.allocate(tempBuffer, countersManager, registrationId, sessionId, streamId, channel),
                nanoClock,
                cachedNanoClock,
                cachedEpochClock,
                ctx.systemCounters(),
                sourceAddress,
                congestionControl,
                ctx.lossReport());

            publicationImages.add(image);
            receiverProxy.newPublicationImage(channelEndpoint, image);

            final String sourceIdentity = Configuration.sourceIdentity(sourceAddress);
            for (int i = 0, size = subscriberPositions.size(); i < size; i++)
            {
                final SubscriberPosition position = subscriberPositions.get(i);

                position.addLink(image);

                clientProxy.onAvailableImage(
                    registrationId,
                    streamId,
                    sessionId,
                    position.subscription().registrationId(),
                    position.positionCounterId(),
                    rawLog.fileName(),
                    sourceIdentity);
            }
        }
    }

    void onChannelEndpointError(final long statusIndicatorId, final Exception error)
    {
        final String errorMessage = error.getClass().getSimpleName() + " : " + error.getMessage();
        clientProxy.onError(statusIndicatorId, CHANNEL_ENDPOINT_ERROR, errorMessage);
    }

    void closeChannelEndpoints()
    {
        receiveChannelEndpointByChannelMap.values().forEach(UdpChannelTransport::close);
        sendChannelEndpointByChannelMap.values().forEach(UdpChannelTransport::close);
    }

    SendChannelEndpoint senderChannelEndpoint(final UdpChannel channel)
    {
        return sendChannelEndpointByChannelMap.get(channel.canonicalForm());
    }

    ReceiveChannelEndpoint receiverChannelEndpoint(final UdpChannel channel)
    {
        return receiveChannelEndpointByChannelMap.get(channel.canonicalForm());
    }

    IpcPublication getSharedIpcPublication(final long streamId)
    {
        return findSharedIpcPublication(ipcPublications, streamId);
    }

    IpcPublication getIpcPublication(final long registrationId)
    {
        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            if (publication.registrationId() == registrationId)
            {
                return publication;
            }
        }

        return null;
    }

    NetworkPublication findNetworkPublicationByTag(final long tag)
    {
        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);
            final long publicationTag = publication.tag();
            if (publicationTag == tag && publicationTag != ChannelUri.INVALID_TAG)
            {
                return publication;
            }
        }

        return null;
    }

    IpcPublication findIpcPublicationByTag(final long tag)
    {
        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            final long publicationTag = publication.tag();
            if (publicationTag == tag && publicationTag != ChannelUri.INVALID_TAG)
            {
                return publication;
            }
        }

        return null;
    }

    void onAddNetworkPublication(
        final String channel,
        final int streamId,
        final long correlationId,
        final long clientId,
        final boolean isExclusive)
    {
        final UdpChannel udpChannel = UdpChannel.parse(channel);
        final ChannelUri channelUri = udpChannel.channelUri();
        final PublicationParams params = getPublicationParams(ctx, channelUri, this, isExclusive, false);
        validateMtuForMaxMessage(params);

        final SendChannelEndpoint channelEndpoint = getOrCreateSendChannelEndpoint(udpChannel);

        NetworkPublication publication = null;
        if (!isExclusive)
        {
            publication = findPublication(networkPublications, streamId, channelEndpoint);
        }

        if (null == publication)
        {
            if (params.hasSessionId)
            {
                checkForSessionClash(params.sessionId, streamId, udpChannel.canonicalForm());
            }

            publication = newNetworkPublication(
                correlationId, streamId, channel, udpChannel, channelEndpoint, params, isExclusive);
        }
        else
        {
            confirmMatch(channelUri, params, publication.rawLog(), publication.sessionId());
        }

        publicationLinks.add(new PublicationLink(correlationId, getOrAddClient(clientId), publication));

        clientProxy.onPublicationReady(
            correlationId,
            publication.registrationId(),
            streamId,
            publication.sessionId(),
            publication.rawLog().fileName(),
            publication.publisherLimitId(),
            channelEndpoint.statusIndicatorCounterId(),
            isExclusive);
    }

    void cleanupSpies(final NetworkPublication publication)
    {
        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink link = subscriptionLinks.get(i);
            if (link.isLinked(publication))
            {
                clientProxy.onUnavailableImage(
                    publication.registrationId(), link.registrationId(), publication.streamId(), publication.channel());
                subscriptionLinks.get(i).unlink(publication);
            }
        }
    }

    void notifyUnavailableImageLink(final long resourceId, final SubscriptionLink link)
    {
        clientProxy.onUnavailableImage(resourceId, link.registrationId(), link.streamId(), link.channel());
    }

    void notifyAvailableImageLink(
        final long resourceId,
        final int sessionId,
        final SubscriptionLink link,
        final int positionCounterId,
        final long joinPosition,
        final String logFileName,
        final String sourceIdentity)
    {
        countersManager.setCounterValue(positionCounterId, joinPosition);

        final int streamId = link.streamId();
        clientProxy.onAvailableImage(
            resourceId, streamId, sessionId, link.registrationId(), positionCounterId, logFileName, sourceIdentity);
    }

    void cleanupPublication(final NetworkPublication publication)
    {
        final String channel = publication.channelEndpoint().udpChannel().canonicalForm();
        activeSessionSet.remove(new SessionKey(publication.sessionId(), publication.streamId(), channel));
        senderProxy.removeNetworkPublication(publication);

        final SendChannelEndpoint channelEndpoint = publication.channelEndpoint();
        if (channelEndpoint.shouldBeClosed())
        {
            channelEndpoint.closeStatusIndicator();
            sendChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
            senderProxy.closeSendChannelEndpoint(channelEndpoint);
        }
    }

    void cleanupSubscriptionLink(final SubscriptionLink subscription)
    {
        final ReceiveChannelEndpoint channelEndpoint = subscription.channelEndpoint();

        if (null != channelEndpoint)
        {
            if (subscription.hasSessionId())
            {
                if (0 == channelEndpoint.decRefToStreamAndSession(subscription.streamId(), subscription.sessionId()))
                {
                    receiverProxy.removeSubscription(channelEndpoint, subscription.streamId());
                }
            }
            else
            {
                if (0 == channelEndpoint.decRefToStream(subscription.streamId()))
                {
                    receiverProxy.removeSubscription(channelEndpoint, subscription.streamId());
                }
            }

            if (channelEndpoint.shouldBeClosed())
            {
                channelEndpoint.closeStatusIndicator();
                receiveChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
                receiverProxy.closeReceiveChannelEndpoint(channelEndpoint);
            }
        }
    }

    void transitionToLinger(final PublicationImage image)
    {
        boolean rejoin = true;

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink link = subscriptionLinks.get(i);
            if (link.isLinked(image))
            {
                rejoin = link.isRejoin();
                clientProxy.onUnavailableImage(
                    image.correlationId(), link.registrationId(), image.streamId(), image.channel());
            }
        }

        if (rejoin)
        {
            receiverProxy.removeCoolDown(image.channelEndpoint(), image.sessionId(), image.streamId());
        }
    }

    void transitionToLinger(final IpcPublication publication)
    {
        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink link = subscriptionLinks.get(i);
            if (link.isLinked(publication))
            {
                clientProxy.onUnavailableImage(
                    publication.registrationId(),
                    link.registrationId(),
                    publication.streamId(),
                    CommonContext.IPC_CHANNEL);
            }
        }
    }

    void cleanupImage(final PublicationImage image)
    {
        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            subscriptionLinks.get(i).unlink(image);
        }
    }

    void cleanupIpcPublication(final IpcPublication publication)
    {
        activeSessionSet.remove(new SessionKey(publication.sessionId(), publication.streamId(), IPC_MEDIA));

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            subscriptionLinks.get(i).unlink(publication);
        }
    }

    void clientTimeout(final long clientId)
    {
        clientProxy.onClientTimeout(clientId);
    }

    void onAddIpcPublication(
        final String channel,
        final int streamId,
        final long correlationId,
        final long clientId,
        final boolean isExclusive)
    {
        final IpcPublication ipcPublication = getOrAddIpcPublication(correlationId, streamId, channel, isExclusive);
        publicationLinks.add(new PublicationLink(correlationId, getOrAddClient(clientId), ipcPublication));

        final ArrayList<SubscriberPosition> subscriberPositions = linkIpcSubscriptions(ipcPublication);

        clientProxy.onPublicationReady(
            correlationId,
            ipcPublication.registrationId(),
            streamId,
            ipcPublication.sessionId(),
            ipcPublication.rawLog().fileName(),
            ipcPublication.publisherLimitId(),
            ChannelEndpointStatus.NO_ID_ALLOCATED,
            isExclusive);

        for (int i = 0, size = subscriberPositions.size(); i < size; i++)
        {
            final SubscriberPosition subscriberPosition = subscriberPositions.get(i);

            clientProxy.onAvailableImage(
                ipcPublication.registrationId(),
                streamId,
                ipcPublication.sessionId(),
                subscriberPosition.subscription().registrationId,
                subscriberPosition.position().id(),
                ipcPublication.rawLog().fileName(),
                channel);
        }
    }

    void onRemovePublication(final long registrationId, final long correlationId)
    {
        PublicationLink publicationLink = null;
        final ArrayList<PublicationLink> publicationLinks = this.publicationLinks;
        for (int i = 0, size = publicationLinks.size(); i < size; i++)
        {
            final PublicationLink publication = publicationLinks.get(i);
            if (registrationId == publication.registrationId())
            {
                publicationLink = publication;
                fastUnorderedRemove(publicationLinks, i);
                break;
            }
        }

        if (null == publicationLink)
        {
            throw new ControlProtocolException(UNKNOWN_PUBLICATION, "unknown publication: " + registrationId);
        }

        publicationLink.close();

        clientProxy.operationSucceeded(correlationId);
    }

    void onAddSendDestination(final long registrationId, final String destinationChannel, final long correlationId)
    {
        SendChannelEndpoint sendChannelEndpoint = null;

        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);

            if (registrationId == publication.registrationId())
            {
                sendChannelEndpoint = publication.channelEndpoint();
                break;
            }
        }

        if (null == sendChannelEndpoint)
        {
            throw new ControlProtocolException(UNKNOWN_PUBLICATION, "unknown publication: " + registrationId);
        }

        sendChannelEndpoint.validateAllowsManualControl();

        final ChannelUri channelUri = ChannelUri.parse(destinationChannel);
        final InetSocketAddress dstAddress = UdpChannel.destinationAddress(channelUri);
        senderProxy.addDestination(sendChannelEndpoint, dstAddress);
        clientProxy.operationSucceeded(correlationId);
    }

    void onRemoveSendDestination(final long registrationId, final String destinationChannel, final long correlationId)
    {
        SendChannelEndpoint sendChannelEndpoint = null;

        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);

            if (registrationId == publication.registrationId())
            {
                sendChannelEndpoint = publication.channelEndpoint();
                break;
            }
        }

        if (null == sendChannelEndpoint)
        {
            throw new ControlProtocolException(UNKNOWN_PUBLICATION, "unknown publication: " + registrationId);
        }

        sendChannelEndpoint.validateAllowsManualControl();

        final ChannelUri channelUri = ChannelUri.parse(destinationChannel);
        final InetSocketAddress dstAddress = UdpChannel.destinationAddress(channelUri);
        senderProxy.removeDestination(sendChannelEndpoint, dstAddress);
        clientProxy.operationSucceeded(correlationId);
    }

    void onAddNetworkSubscription(
        final String channel, final int streamId, final long registrationId, final long clientId)
    {
        final UdpChannel udpChannel = UdpChannel.parse(channel);
        final SubscriptionParams params = SubscriptionParams.getSubscriptionParams(udpChannel.channelUri(), ctx);

        checkForClashingSubscription(params, udpChannel, streamId);

        final ReceiveChannelEndpoint channelEndpoint = getOrCreateReceiveChannelEndpoint(udpChannel);

        if (params.hasSessionId)
        {
            if (1 == channelEndpoint.incRefToStreamAndSession(streamId, params.sessionId))
            {
                receiverProxy.addSubscription(channelEndpoint, streamId, params.sessionId);
            }
        }
        else
        {
            if (1 == channelEndpoint.incRefToStream(streamId))
            {
                receiverProxy.addSubscription(channelEndpoint, streamId);
            }
        }

        final AeronClient client = getOrAddClient(clientId);
        final SubscriptionLink subscription = new NetworkSubscriptionLink(
            registrationId, channelEndpoint, streamId, channel, client, params);

        subscriptionLinks.add(subscription);
        clientProxy.onSubscriptionReady(registrationId, channelEndpoint.statusIndicatorCounterId());

        linkMatchingImages(subscription);
    }

    void onAddIpcSubscription(final String channel, final int streamId, final long registrationId, final long clientId)
    {
        final SubscriptionParams params = SubscriptionParams.getSubscriptionParams(ChannelUri.parse(channel), ctx);
        final IpcSubscriptionLink subscriptionLink = new IpcSubscriptionLink(
            registrationId, streamId, channel, getOrAddClient(clientId), params);
        final ArrayList<SubscriberPosition> subscriberPositions = new ArrayList<>();

        subscriptionLinks.add(subscriptionLink);

        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            if (IpcPublication.State.ACTIVE == publication.state() && subscriptionLink.matches(publication))
            {
                final Position subPos = linkIpcSubscription(publication, subscriptionLink);
                subscriberPositions.add(new SubscriberPosition(subscriptionLink, publication, subPos));
            }
        }

        clientProxy.onSubscriptionReady(registrationId, ChannelEndpointStatus.NO_ID_ALLOCATED);

        for (int i = 0, size = subscriberPositions.size(); i < size; i++)
        {
            final SubscriberPosition subscriberPosition = subscriberPositions.get(i);
            final IpcPublication publication = (IpcPublication)subscriberPosition.subscribable();

            clientProxy.onAvailableImage(
                publication.registrationId(),
                streamId,
                publication.sessionId(),
                registrationId,
                subscriberPosition.position().id(),
                publication.rawLog().fileName(),
                channel);
        }
    }

    void onAddSpySubscription(final String channel, final int streamId, final long registrationId, final long clientId)
    {
        final UdpChannel udpChannel = UdpChannel.parse(channel);
        final AeronClient client = getOrAddClient(clientId);
        final SubscriptionParams params = SubscriptionParams.getSubscriptionParams(udpChannel.channelUri(), ctx);
        final ArrayList<SubscriberPosition> subscriberPositions = new ArrayList<>();
        final SpySubscriptionLink subscriptionLink = new SpySubscriptionLink(
            registrationId, udpChannel, streamId, client, params);

        subscriptionLinks.add(subscriptionLink);

        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);
            if (NetworkPublication.State.ACTIVE == publication.state() && subscriptionLink.matches(publication))
            {
                final Position subPos = linkSpy(publication, subscriptionLink);
                subscriberPositions.add(new SubscriberPosition(subscriptionLink, publication, subPos));
            }
        }

        clientProxy.onSubscriptionReady(registrationId, ChannelEndpointStatus.NO_ID_ALLOCATED);

        for (int i = 0, size = subscriberPositions.size(); i < size; i++)
        {
            final SubscriberPosition subscriberPosition = subscriberPositions.get(i);
            final NetworkPublication publication = (NetworkPublication)subscriberPosition.subscribable();

            clientProxy.onAvailableImage(
                publication.registrationId(),
                streamId,
                publication.sessionId(),
                registrationId,
                subscriberPosition.position().id(),
                publication.rawLog().fileName(),
                CommonContext.IPC_CHANNEL);
        }
    }

    void onRemoveSubscription(final long registrationId, final long correlationId)
    {
        final SubscriptionLink subscription = removeSubscriptionLink(subscriptionLinks, registrationId);
        if (null == subscription)
        {
            throw new ControlProtocolException(UNKNOWN_SUBSCRIPTION, "unknown subscription: " + registrationId);
        }

        subscription.close();
        final ReceiveChannelEndpoint channelEndpoint = subscription.channelEndpoint();

        if (null != channelEndpoint)
        {
            if (subscription.hasSessionId())
            {
                if (0 == channelEndpoint.decRefToStreamAndSession(subscription.streamId(), subscription.sessionId()))
                {
                    receiverProxy.removeSubscription(
                        channelEndpoint, subscription.streamId(), subscription.sessionId());
                }
            }
            else
            {
                if (0 == channelEndpoint.decRefToStream(subscription.streamId()))
                {
                    receiverProxy.removeSubscription(channelEndpoint, subscription.streamId());
                }
            }

            if (channelEndpoint.shouldBeClosed())
            {
                channelEndpoint.closeStatusIndicator();
                receiveChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
                receiverProxy.closeReceiveChannelEndpoint(channelEndpoint);
            }
        }

        clientProxy.operationSucceeded(correlationId);
    }

    void onClientKeepalive(final long clientId)
    {
        final AeronClient client = findClient(clients, clientId);
        if (null != client)
        {
            client.timeOfLastKeepaliveMs(cachedEpochClock.time());
        }
    }

    void onAddCounter(
        final int typeId,
        final DirectBuffer keyBuffer,
        final int keyOffset,
        final int keyLength,
        final DirectBuffer labelBuffer,
        final int labelOffset,
        final int labelLength,
        final long correlationId,
        final long clientId)
    {
        final AeronClient client = getOrAddClient(clientId);
        final AtomicCounter counter = countersManager.newCounter(
            typeId, keyBuffer, keyOffset, keyLength, labelBuffer, labelOffset, labelLength);

        counterLinks.add(new CounterLink(counter, correlationId, client));
        clientProxy.onCounterReady(correlationId, counter.id());
    }

    void onRemoveCounter(final long registrationId, final long correlationId)
    {
        CounterLink counterLink = null;
        final ArrayList<CounterLink> counterLinks = this.counterLinks;
        for (int i = 0, size = counterLinks.size(); i < size; i++)
        {
            final CounterLink link = counterLinks.get(i);
            if (registrationId == link.registrationId())
            {
                counterLink = link;
                fastUnorderedRemove(counterLinks, i);
                break;
            }
        }

        if (null == counterLink)
        {
            throw new ControlProtocolException(UNKNOWN_COUNTER, "unknown counter: " + registrationId);
        }

        clientProxy.operationSucceeded(correlationId);
        clientProxy.onUnavailableCounter(registrationId, counterLink.counterId());

        counterLink.close();
    }

    void onClientClose(final long clientId)
    {
        final AeronClient client = findClient(clients, clientId);
        if (null != client)
        {
            client.onClosedByCommand();
        }
    }

    void onAddRcvDestination(final long registrationId, final String destinationChannel, final long correlationId)
    {
        ReceiveChannelEndpoint receiveChannelEndpoint = null;

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink subscriptionLink = subscriptionLinks.get(i);

            if (registrationId == subscriptionLink.registrationId())
            {
                receiveChannelEndpoint = subscriptionLink.channelEndpoint();
                break;
            }
        }

        if (null == receiveChannelEndpoint)
        {
            throw new ControlProtocolException(UNKNOWN_SUBSCRIPTION, "unknown subscription: " + registrationId);
        }

        receiveChannelEndpoint.validateAllowsDestinationControl();

        final UdpChannel udpChannel = UdpChannel.parse(destinationChannel);
        final ReceiveDestinationUdpTransport transport = new ReceiveDestinationUdpTransport(udpChannel, ctx);

        receiverProxy.addDestination(receiveChannelEndpoint, transport);
        clientProxy.operationSucceeded(correlationId);
    }

    void onRemoveRcvDestination(final long registrationId, final String destinationChannel, final long correlationId)
    {
        ReceiveChannelEndpoint receiveChannelEndpoint = null;

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink subscriptionLink = subscriptionLinks.get(i);

            if (registrationId == subscriptionLink.registrationId())
            {
                receiveChannelEndpoint = subscriptionLink.channelEndpoint();
                break;
            }
        }

        if (null == receiveChannelEndpoint)
        {
            throw new ControlProtocolException(UNKNOWN_SUBSCRIPTION, "unknown subscription: " + registrationId);
        }

        receiveChannelEndpoint.validateAllowsDestinationControl();

        final UdpChannel destinationUdpChannel = UdpChannel.parse(destinationChannel);
        receiverProxy.removeDestination(receiveChannelEndpoint, destinationUdpChannel);
        clientProxy.operationSucceeded(correlationId);
    }

    void onTerminateDriver(final DirectBuffer tokenBuffer, final int tokenOffset, final int tokenLength)
    {
        if (ctx.terminationValidator().allowTermination(ctx.aeronDirectory(), tokenBuffer, tokenOffset, tokenLength))
        {
            ctx.terminationHook().run();
        }
    }

    private void heartbeatAndCheckTimers(final long nowNs)
    {
        final long nowMs = cachedEpochClock.time();
        toDriverCommands.consumerHeartbeatTime(nowMs);

        checkManagedResources(clients, nowNs, nowMs);
        checkManagedResources(publicationLinks, nowNs, nowMs);
        checkManagedResources(networkPublications, nowNs, nowMs);
        checkManagedResources(subscriptionLinks, nowNs, nowMs);
        checkManagedResources(publicationImages, nowNs, nowMs);
        checkManagedResources(ipcPublications, nowNs, nowMs);
        checkManagedResources(counterLinks, nowNs, nowMs);
    }

    private void checkForBlockedToDriverCommands(final long nowNs)
    {
        final long consumerPosition = toDriverCommands.consumerPosition();

        if (consumerPosition == lastConsumerCommandPosition)
        {
            if (toDriverCommands.producerPosition() > consumerPosition &&
                ((timeOfLastToDriverPositionChangeNs + clientLivenessTimeoutNs) - nowNs < 0))
            {
                if (toDriverCommands.unblock())
                {
                    ctx.systemCounters().get(UNBLOCKED_COMMANDS).incrementOrdered();
                }
            }
        }
        else
        {
            timeOfLastToDriverPositionChangeNs = nowNs;
            lastConsumerCommandPosition = consumerPosition;
        }
    }

    private ArrayList<SubscriberPosition> createSubscriberPositions(
        final int sessionId, final int streamId, final ReceiveChannelEndpoint channelEndpoint, final long joinPosition)
    {
        final ArrayList<SubscriberPosition> subscriberPositions = new ArrayList<>();

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink subscription = subscriptionLinks.get(i);
            if (subscription.matches(channelEndpoint, streamId, sessionId))
            {
                final Position position = SubscriberPos.allocate(
                    tempBuffer,
                    countersManager,
                    subscription.registrationId(),
                    sessionId,
                    streamId,
                    subscription.channel(),
                    joinPosition);

                position.setOrdered(joinPosition);
                subscriberPositions.add(new SubscriberPosition(subscription, null, position));
            }
        }

        return subscriberPositions;
    }

    private static NetworkPublication findPublication(
        final ArrayList<NetworkPublication> publications,
        final int streamId,
        final SendChannelEndpoint channelEndpoint)
    {
        for (int i = 0, size = publications.size(); i < size; i++)
        {
            final NetworkPublication publication = publications.get(i);

            if (streamId == publication.streamId() &&
                channelEndpoint == publication.channelEndpoint() &&
                NetworkPublication.State.ACTIVE == publication.state() &&
                !publication.isExclusive())
            {
                return publication;
            }
        }

        return null;
    }

    private NetworkPublication newNetworkPublication(
        final long registrationId,
        final int streamId,
        final String channel,
        final UdpChannel udpChannel,
        final SendChannelEndpoint channelEndpoint,
        final PublicationParams params,
        final boolean isExclusive)
    {
        final String canonicalForm = udpChannel.canonicalForm();
        final int sessionId = params.hasSessionId ? params.sessionId : nextAvailableSessionId(streamId, canonicalForm);
        final int initialTermId = params.isReplay ? params.initialTermId : BitUtil.generateRandomisedId();

        final UnsafeBufferPosition publisherPosition = PublisherPos.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel);
        final UnsafeBufferPosition publisherLimit = PublisherLimit.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel);
        final UnsafeBufferPosition senderPosition = SenderPos.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel);
        final UnsafeBufferPosition senderLimit = SenderLimit.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel);

        if (params.isReplay)
        {
            final int bits = LogBufferDescriptor.positionBitsToShift(params.termLength);
            final long position = computePosition(params.termId, params.termOffset, bits, initialTermId);
            publisherPosition.setOrdered(position);
            publisherLimit.setOrdered(position);
            senderPosition.setOrdered(position);
            senderLimit.setOrdered(position);
        }

        final RetransmitHandler retransmitHandler = new RetransmitHandler(
            cachedNanoClock,
            ctx.systemCounters().get(INVALID_PACKETS),
            ctx.retransmitUnicastDelayGenerator(),
            ctx.retransmitUnicastLingerGenerator());

        final FlowControl flowControl = udpChannel.isMulticast() || udpChannel.hasExplicitControl() ?
            ctx.multicastFlowControlSupplier().newInstance(udpChannel, streamId, registrationId) :
            ctx.unicastFlowControlSupplier().newInstance(udpChannel, streamId, registrationId);

        final NetworkPublication publication = new NetworkPublication(
            registrationId,
            params,
            channelEndpoint,
            cachedNanoClock,
            newNetworkPublicationLog(sessionId, streamId, initialTermId, udpChannel, registrationId, params),
            Configuration.producerWindowLength(params.termLength, ctx.publicationTermWindowLength()),
            publisherPosition,
            publisherLimit,
            senderPosition,
            senderLimit,
            SenderBpe.allocate(tempBuffer, countersManager, registrationId, sessionId, streamId, channel),
            sessionId,
            streamId,
            initialTermId,
            ctx.systemCounters(),
            flowControl,
            retransmitHandler,
            networkPublicationThreadLocals,
            ctx.publicationUnblockTimeoutNs(),
            ctx.publicationConnectionTimeoutNs(),
            ctx.untetheredWindowLimitTimeoutNs(),
            ctx.untetheredRestingTimeoutNs(),
            ctx.spiesSimulateConnection(),
            isExclusive);

        channelEndpoint.incRef();
        networkPublications.add(publication);
        senderProxy.newNetworkPublication(publication);
        linkSpies(subscriptionLinks, publication);
        activeSessionSet.add(new SessionKey(sessionId, streamId, canonicalForm));

        return publication;
    }

    private RawLog newNetworkPublicationLog(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final UdpChannel udpChannel,
        final long registrationId,
        final PublicationParams params)
    {
        final RawLog rawLog = logFactory.newPublication(
            udpChannel.canonicalForm(), sessionId, streamId, registrationId, params.termLength, params.isSparse);

        initPublicationMetadata(sessionId, streamId, initialTermId, registrationId, params, rawLog);

        return rawLog;
    }

    private RawLog newIpcPublicationLog(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final long registrationId,
        final PublicationParams params)
    {
        final RawLog rawLog = logFactory.newPublication(
            IPC_MEDIA, sessionId, streamId, registrationId, params.termLength, params.isSparse);

        initPublicationMetadata(sessionId, streamId, initialTermId, registrationId, params, rawLog);

        return rawLog;
    }

    private void initPublicationMetadata(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final long registrationId,
        final PublicationParams params,
        final RawLog rawLog)
    {
        final UnsafeBuffer logMetaData = rawLog.metaData();

        defaultDataHeader.sessionId(sessionId).streamId(streamId).termId(initialTermId);
        storeDefaultFrameHeader(logMetaData, defaultDataHeader);

        initialTermId(logMetaData, initialTermId);
        mtuLength(logMetaData, params.mtuLength);
        termLength(logMetaData, rawLog.termLength());
        pageSize(logMetaData, ctx.filePageSize());
        correlationId(logMetaData, registrationId);
        endOfStreamPosition(logMetaData, Long.MAX_VALUE);

        initialisePositionCounters(initialTermId, params, logMetaData);
    }

    private static void initialisePositionCounters(
        final int initialTermId, final PublicationParams params, final UnsafeBuffer logMetaData)
    {
        if (params.isReplay)
        {
            final int termId = params.termId;
            final int termCount = termId - initialTermId;
            int activeIndex = indexByTerm(initialTermId, termId);

            rawTail(logMetaData, activeIndex, packTail(termId, params.termOffset));
            for (int i = 1; i < PARTITION_COUNT; i++)
            {
                final int expectedTermId = (termId + i) - PARTITION_COUNT;
                activeIndex = nextPartitionIndex(activeIndex);
                initialiseTailWithTermId(logMetaData, activeIndex, expectedTermId);
            }

            activeTermCount(logMetaData, termCount);
        }
        else
        {
            initialiseTailWithTermId(logMetaData, 0, initialTermId);
            for (int i = 1; i < PARTITION_COUNT; i++)
            {
                final int expectedTermId = (initialTermId + i) - PARTITION_COUNT;
                initialiseTailWithTermId(logMetaData, i, expectedTermId);
            }
        }
    }

    private RawLog newPublicationImageLog(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int termBufferLength,
        final boolean isSparse,
        final int senderMtuLength,
        final UdpChannel udpChannel,
        final long correlationId)
    {
        final RawLog rawLog = logFactory.newImage(
            udpChannel.canonicalForm(), sessionId, streamId, correlationId, termBufferLength, isSparse);

        final UnsafeBuffer logMetaData = rawLog.metaData();

        defaultDataHeader.sessionId(sessionId).streamId(streamId).termId(initialTermId);
        storeDefaultFrameHeader(logMetaData, defaultDataHeader);

        initialTermId(logMetaData, initialTermId);
        mtuLength(logMetaData, senderMtuLength);
        termLength(logMetaData, termBufferLength);
        pageSize(logMetaData, ctx.filePageSize());
        correlationId(logMetaData, correlationId);
        endOfStreamPosition(logMetaData, Long.MAX_VALUE);

        return rawLog;
    }

    private SendChannelEndpoint getOrCreateSendChannelEndpoint(final UdpChannel udpChannel)
    {
        SendChannelEndpoint channelEndpoint = findExistingSendChannelEndpoint(udpChannel);
        if (null == channelEndpoint)
        {
            channelEndpoint = ctx.sendChannelEndpointSupplier().newInstance(
                udpChannel,
                SendChannelStatus.allocate(tempBuffer, countersManager, udpChannel.originalUriString()),
                ctx);

            sendChannelEndpointByChannelMap.put(udpChannel.canonicalForm(), channelEndpoint);
            senderProxy.registerSendChannelEndpoint(channelEndpoint);
        }

        return channelEndpoint;
    }

    private SendChannelEndpoint findExistingSendChannelEndpoint(final UdpChannel udpChannel)
    {
        if (udpChannel.hasTag())
        {
            for (final SendChannelEndpoint endpoint : sendChannelEndpointByChannelMap.values())
            {
                final UdpChannel endpointUdpChannel = endpoint.udpChannel();

                if (endpointUdpChannel.matchesTag(udpChannel))
                {
                    return endpoint;
                }
            }
        }

        return sendChannelEndpointByChannelMap.get(udpChannel.canonicalForm());
    }

    private void checkForClashingSubscription(
        final SubscriptionParams params, final UdpChannel udpChannel, final int streamId)
    {
        final ReceiveChannelEndpoint channelEndpoint = findExistingReceiveChannelEndpoint(udpChannel);
        if (null != channelEndpoint)
        {
            final boolean isReliable = params.isReliable;
            final boolean isRejoin = params.isRejoin;
            final ArrayList<SubscriptionLink> existingLinks = subscriptionLinks;
            for (int i = 0, size = existingLinks.size(); i < size; i++)
            {
                final SubscriptionLink subscription = existingLinks.get(i);
                if (isReliable != subscription.isReliable() && subscription.matches(channelEndpoint, streamId, params))
                {
                    throw new IllegalStateException(
                        "option conflicts with existing subscriptions: reliable=" + isReliable);
                }

                if (isRejoin != subscription.isRejoin() && subscription.matches(channelEndpoint, streamId, params))
                {
                    throw new IllegalStateException(
                        "option conflicts with existing subscriptions: rejoin=" + isRejoin);
                }
            }
        }
    }

    private void linkMatchingImages(final SubscriptionLink subscription)
    {
        final long registrationId = subscription.registrationId();
        final int streamId = subscription.streamId();
        final String channel = subscription.channel();

        for (int i = 0, size = publicationImages.size(); i < size; i++)
        {
            final PublicationImage image = publicationImages.get(i);
            if (subscription.matches(image) && image.isAcceptingSubscriptions())
            {
                final long rebuildPosition = image.rebuildPosition();
                final int sessionId = image.sessionId();
                final Position position = SubscriberPos.allocate(
                    tempBuffer, countersManager, registrationId, sessionId, streamId, channel, rebuildPosition);

                position.setOrdered(rebuildPosition);
                subscription.link(image, position);
                image.addSubscriber(subscription, position);

                clientProxy.onAvailableImage(
                    image.correlationId(),
                    streamId,
                    sessionId,
                    registrationId,
                    position.id(),
                    image.rawLog().fileName(),
                    Configuration.sourceIdentity(image.sourceAddress()));
            }
        }
    }

    private ArrayList<SubscriberPosition> linkIpcSubscriptions(final IpcPublication publication)
    {
        final ArrayList<SubscriptionLink> subscriptionLinks = this.subscriptionLinks;
        final ArrayList<SubscriberPosition> subscriberPositions = new ArrayList<>();

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink subscription = subscriptionLinks.get(i);
            if (subscription.matches(publication) && !subscription.isLinked(publication))
            {
                final Position subPos = linkIpcSubscription(publication, subscription);
                subscriberPositions.add(new SubscriberPosition(subscription, publication, subPos));
            }
        }

        return subscriberPositions;
    }

    private Position linkIpcSubscription(final IpcPublication publication, final SubscriptionLink subscription)
    {
        final long joinPosition = publication.joinPosition();
        final long registrationId = subscription.registrationId();
        final int sessionId = publication.sessionId();
        final int streamId = subscription.streamId();
        final String channel = subscription.channel();

        final Position position = SubscriberPos.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel, joinPosition);

        position.setOrdered(joinPosition);
        subscription.link(publication, position);
        publication.addSubscriber(subscription, position);

        return position;
    }

    private Position linkSpy(final NetworkPublication publication, final SubscriptionLink subscription)
    {
        final long joinPosition = publication.consumerPosition();
        final long subscriptionRegistrationId = subscription.registrationId();
        final int streamId = publication.streamId();
        final int sessionId = publication.sessionId();
        final String channel = subscription.channel();

        final Position position = SubscriberPos.allocate(
            tempBuffer, countersManager, subscriptionRegistrationId, sessionId, streamId, channel, joinPosition);

        position.setOrdered(joinPosition);
        subscription.link(publication, position);
        publication.addSubscriber(subscription, position);

        return position;
    }

    private ReceiveChannelEndpoint getOrCreateReceiveChannelEndpoint(final UdpChannel udpChannel)
    {
        ReceiveChannelEndpoint channelEndpoint = findExistingReceiveChannelEndpoint(udpChannel);
        if (null == channelEndpoint)
        {
            channelEndpoint = ctx.receiveChannelEndpointSupplier().newInstance(
                udpChannel,
                new DataPacketDispatcher(ctx.driverConductorProxy(), receiverProxy.receiver()),
                ReceiveChannelStatus.allocate(tempBuffer, countersManager, udpChannel.originalUriString()),
                ctx);

            receiveChannelEndpointByChannelMap.put(udpChannel.canonicalForm(), channelEndpoint);
            receiverProxy.registerReceiveChannelEndpoint(channelEndpoint);
        }

        return channelEndpoint;
    }

    private ReceiveChannelEndpoint findExistingReceiveChannelEndpoint(final UdpChannel udpChannel)
    {
        if (udpChannel.hasTag())
        {
            for (final ReceiveChannelEndpoint endpoint : receiveChannelEndpointByChannelMap.values())
            {
                if (endpoint.udpChannel().matchesTag(udpChannel))
                {
                    return endpoint;
                }
            }
        }

        return receiveChannelEndpointByChannelMap.get(udpChannel.canonicalForm());
    }

    private AeronClient getOrAddClient(final long clientId)
    {
        AeronClient client = findClient(clients, clientId);
        if (null == client)
        {
            client = new AeronClient(
                clientId,
                clientLivenessTimeoutNs,
                cachedEpochClock.time(),
                ctx.systemCounters().get(SystemCounterDescriptor.CLIENT_TIMEOUTS),
                ClientHeartbeatTimestamp.allocate(tempBuffer, countersManager, clientId));
            clients.add(client);
        }

        return client;
    }

    private IpcPublication getOrAddIpcPublication(
        final long correlationId, final int streamId, final String channel, final boolean isExclusive)
    {
        IpcPublication publication = null;
        final ChannelUri channelUri = ChannelUri.parse(channel);
        final PublicationParams params = getPublicationParams(ctx, channelUri, this, isExclusive, true);

        if (!isExclusive)
        {
            publication = findSharedIpcPublication(ipcPublications, streamId);
        }

        if (null == publication)
        {
            if (params.hasSessionId)
            {
                checkForSessionClash(params.sessionId, streamId, IPC_MEDIA);
            }

            validateMtuForMaxMessage(params);
            publication = addIpcPublication(correlationId, streamId, channel, isExclusive, params);
        }
        else
        {
            confirmMatch(channelUri, params, publication.rawLog(), publication.sessionId());
        }

        return publication;
    }

    private IpcPublication addIpcPublication(
        final long registrationId,
        final int streamId,
        final String channel,
        final boolean isExclusive,
        final PublicationParams params)
    {
        final int sessionId = params.hasSessionId ? params.sessionId : nextAvailableSessionId(streamId, IPC_MEDIA);
        final int initialTermId = params.isReplay ? params.initialTermId : BitUtil.generateRandomisedId();
        final RawLog rawLog = newIpcPublicationLog(sessionId, streamId, initialTermId, registrationId, params);

        final UnsafeBufferPosition publisherPosition = PublisherPos.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel);
        final UnsafeBufferPosition publisherLimit = PublisherLimit.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel);

        if (params.isReplay)
        {
            final int positionBitsToShift = positionBitsToShift(params.termLength);
            final long position = computePosition(params.termId, params.termOffset, positionBitsToShift, initialTermId);
            publisherPosition.setOrdered(position);
            publisherLimit.setOrdered(position);
        }

        final IpcPublication publication = new IpcPublication(
            registrationId,
            params.entityTag,
            sessionId,
            streamId,
            publisherPosition,
            publisherLimit,
            rawLog,
            Configuration.producerWindowLength(params.termLength, ctx.ipcPublicationTermWindowLength()),
            ctx.publicationUnblockTimeoutNs(),
            ctx.untetheredWindowLimitTimeoutNs(),
            ctx.untetheredRestingTimeoutNs(),
            cachedNanoClock.nanoTime(),
            ctx.systemCounters(),
            isExclusive);

        ipcPublications.add(publication);
        activeSessionSet.add(new SessionKey(sessionId, streamId, IPC_MEDIA));

        return publication;
    }

    private static AeronClient findClient(final ArrayList<AeronClient> clients, final long clientId)
    {
        AeronClient aeronClient = null;

        for (int i = 0, size = clients.size(); i < size; i++)
        {
            final AeronClient client = clients.get(i);
            if (client.clientId() == clientId)
            {
                aeronClient = client;
                break;
            }
        }

        return aeronClient;
    }

    private static SubscriptionLink removeSubscriptionLink(
        final ArrayList<SubscriptionLink> subscriptionLinks, final long registrationId)
    {
        SubscriptionLink subscriptionLink = null;

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink subscription = subscriptionLinks.get(i);
            if (subscription.registrationId() == registrationId)
            {
                subscriptionLink = subscription;
                fastUnorderedRemove(subscriptionLinks, i);
                break;
            }
        }

        return subscriptionLink;
    }

    private static IpcPublication findSharedIpcPublication(
        final ArrayList<IpcPublication> ipcPublications, final long streamId)
    {
        IpcPublication ipcPublication = null;

        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            if (publication.streamId() == streamId &&
                !publication.isExclusive() &&
                IpcPublication.State.ACTIVE == publication.state())
            {
                ipcPublication = publication;
                break;
            }
        }

        return ipcPublication;
    }

    private void checkForSessionClash(final int sessionId, final int streamId, final String channel)
    {
        if (activeSessionSet.contains(new SessionKey(sessionId, streamId, channel)))
        {
            throw new IllegalStateException("existing publication has clashing session id: " + sessionId);
        }
    }

    private int nextAvailableSessionId(final int streamId, final String channel)
    {
        final SessionKey sessionKey = new SessionKey(streamId, channel);

        while (true)
        {
            int sessionId = nextSessionId++;

            if (ctx.publicationReservedSessionIdLow() <= sessionId &&
                sessionId <= ctx.publicationReservedSessionIdHigh())
            {
                nextSessionId = ctx.publicationReservedSessionIdHigh() + 1;
                sessionId = nextSessionId++;
            }

            sessionKey.sessionId = sessionId;
            if (!activeSessionSet.contains(sessionKey))
            {
                return sessionId;
            }
        }
    }

    private <T extends DriverManagedResource> void checkManagedResources(
        final ArrayList<T> list, final long nowNs, final long nowMs)
    {
        for (int lastIndex = list.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final DriverManagedResource resource = list.get(i);

            resource.onTimeEvent(nowNs, nowMs, this);

            if (resource.hasReachedEndOfLife())
            {
                if (resource.free())
                {
                    fastUnorderedRemove(list, i, lastIndex--);
                    resource.close();
                }
                else
                {
                    ctx.systemCounters().get(FREE_FAILS).incrementOrdered();
                }
            }
        }
    }

    private void linkSpies(final ArrayList<SubscriptionLink> links, final NetworkPublication publication)
    {
        for (int i = 0, size = links.size(); i < size; i++)
        {
            final SubscriptionLink subscription = links.get(i);
            if (subscription.matches(publication) && !subscription.isLinked(publication))
            {
                clientProxy.onAvailableImage(
                    publication.registrationId(),
                    publication.streamId(),
                    publication.sessionId(),
                    subscription.registrationId(),
                    linkSpy(publication, subscription).id(),
                    publication.rawLog().fileName(),
                    CommonContext.IPC_CHANNEL);
            }
        }
    }

    private void updateClocks(final long nowNs)
    {
        if (clockUpdateDeadlineNs - nowNs < 0)
        {
            clockUpdateDeadlineNs = nowNs + CLOCK_UPDATE_DURATION_NS;
            cachedNanoClock.update(nowNs);
            cachedEpochClock.update(epochClock.time());
        }
    }

    private int processTimers(final long nowNs)
    {
        int workCount = 0;

        if ((timeOfLastTimerCheckNs + timerIntervalNs) - nowNs < 0)
        {
            heartbeatAndCheckTimers(nowNs);
            checkForBlockedToDriverCommands(nowNs);
            timeOfLastTimerCheckNs = nowNs;
            workCount = 1;
        }

        return workCount;
    }

    private static boolean isOldestSubscriptionSparse(final ArrayList<SubscriberPosition> subscriberPositions)
    {
        final SubscriberPosition subscriberPosition = subscriberPositions.get(0);
        long regId = subscriberPosition.subscription().registrationId();
        boolean isSparse = subscriberPosition.subscription().isSparse();

        for (int i = 1, size = subscriberPositions.size(); i < size; i++)
        {
            final SubscriptionLink subscription = subscriberPositions.get(i).subscription();
            if (subscription.registrationId() < regId)
            {
                isSparse = subscription.isSparse();
                regId = subscription.registrationId();
            }
        }

        return isSparse;
    }

    static final class SessionKey
    {
        int sessionId;
        final int streamId;
        final String channel;

        SessionKey(final int streamId, final String channel)
        {
            this.streamId = streamId;
            this.channel = channel;
        }

        SessionKey(final int sessionId, final int streamId, final String channel)
        {
            this.sessionId = sessionId;
            this.streamId = streamId;
            this.channel = channel;
        }

        public boolean equals(final Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (o == null || getClass() != o.getClass())
            {
                return false;
            }

            final SessionKey that = (SessionKey)o;
            return sessionId == that.sessionId && streamId == that.streamId && channel.equals(that.channel);
        }

        public int hashCode()
        {
            return 31 * sessionId * streamId * channel.hashCode();
        }

        public String toString()
        {
            return "SessionKey{" +
                "sessionId=" + sessionId +
                ", streamId=" + streamId +
                ", channel=" + channel +
                '}';
        }
    }
}
