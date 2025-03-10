/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.remoteopenhab.internal.discovery;

import static org.openhab.binding.remoteopenhab.internal.config.RemoteopenhabThingConfiguration.THING_UID;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.remoteopenhab.internal.RemoteopenhabBindingConstants;
import org.openhab.binding.remoteopenhab.internal.data.RemoteopenhabStatusInfo;
import org.openhab.binding.remoteopenhab.internal.data.RemoteopenhabThing;
import org.openhab.binding.remoteopenhab.internal.exceptions.RemoteopenhabException;
import org.openhab.binding.remoteopenhab.internal.handler.RemoteopenhabBridgeHandler;
import org.openhab.binding.remoteopenhab.internal.listener.RemoteopenhabThingsDataListener;
import org.openhab.binding.remoteopenhab.internal.rest.RemoteopenhabRestClient;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link RemoteopenhabDiscoveryService} is responsible for discovering all the remote things
 * available in the remote openHAB server.
 *
 * @author Laurent Garnier - Initial contribution
 */
@NonNullByDefault
public class RemoteopenhabDiscoveryService extends AbstractDiscoveryService
        implements ThingHandlerService, RemoteopenhabThingsDataListener {

    private final Logger logger = LoggerFactory.getLogger(RemoteopenhabDiscoveryService.class);

    private static final int SEARCH_TIME = 10;

    private @NonNullByDefault({}) RemoteopenhabBridgeHandler bridgeHandler;
    private @NonNullByDefault({}) RemoteopenhabRestClient restClient;

    public RemoteopenhabDiscoveryService() {
        super(RemoteopenhabBindingConstants.SUPPORTED_THING_TYPES_UIDS, SEARCH_TIME, false);
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof RemoteopenhabBridgeHandler remoteopenhabBridgeHandler) {
            this.bridgeHandler = remoteopenhabBridgeHandler;
            this.restClient = bridgeHandler.gestRestClient();
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return bridgeHandler;
    }

    @Override
    public void activate() {
        ThingHandlerService.super.activate();
        restClient.addThingsDataListener(this);
    }

    @Override
    public void deactivate() {
        restClient.removeThingsDataListener(this);
        super.deactivate();
    }

    @Override
    protected void startScan() {
        logger.debug("Starting discovery scan for remote things");
        if (bridgeHandler.getThing().getStatus() == ThingStatus.ONLINE) {
            try {
                List<RemoteopenhabThing> things = restClient.getRemoteThings();
                ThingUID bridgeUID = bridgeHandler.getThing().getUID();
                for (RemoteopenhabThing thing : things) {
                    createDiscoveryResult(thing, bridgeUID);
                }
            } catch (RemoteopenhabException e) {
                logger.debug("Scan for remote things failed", e);
            }
        }
    }

    @Override
    protected synchronized void stopScan() {
        super.stopScan();
        removeOlderResults(getTimestampOfLastScan(), bridgeHandler.getThing().getUID());
    }

    @Override
    public void onThingStatusUpdated(String thingUID, RemoteopenhabStatusInfo statusInfo) {
        // Nothing to do
    }

    @Override
    public void onThingAdded(RemoteopenhabThing thing) {
        createDiscoveryResult(thing, bridgeHandler.getThing().getUID());
    }

    @Override
    public void onThingRemoved(RemoteopenhabThing thing) {
        removeDiscoveryResult(thing, bridgeHandler.getThing().getUID());
    }

    @Override
    public void onChannelTriggered(String channelUID, @Nullable String event) {
        // Nothing to do
    }

    private void createDiscoveryResult(RemoteopenhabThing thing, ThingUID bridgeUID) {
        ThingUID thingUID = buildThingUID(thing, bridgeUID);
        logger.debug("Create a DiscoveryResult for remote openHAB thing {} with thingUID setting {}", thingUID,
                thing.uid);
        Map<String, Object> properties = Map.of(THING_UID, thing.uid);
        DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withProperties(properties)
                .withRepresentationProperty(THING_UID).withBridge(bridgeUID).withLabel(thing.label).build();
        thingDiscovered(discoveryResult);
    }

    private void removeDiscoveryResult(RemoteopenhabThing thing, ThingUID bridgeUID) {
        ThingUID thingUID = buildThingUID(thing, bridgeUID);
        logger.debug("Remove a DiscoveryResult for remote openHAB thing {} with thingUID setting {}", thingUID,
                thing.uid);
        thingRemoved(thingUID);
    }

    private ThingUID buildThingUID(RemoteopenhabThing thing, ThingUID bridgeUID) {
        return new ThingUID(RemoteopenhabBindingConstants.THING_TYPE_THING, bridgeUID,
                thing.uid.replaceAll("[^A-Za-z0-9_]", "_"));
    }
}
