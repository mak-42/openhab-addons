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
package org.openhab.binding.homematic.internal.discovery;

import static org.openhab.binding.homematic.internal.HomematicBindingConstants.BINDING_ID;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.homematic.internal.common.HomematicConfig;
import org.openhab.binding.homematic.internal.communicator.HomematicGateway;
import org.openhab.binding.homematic.internal.handler.HomematicBridgeHandler;
import org.openhab.binding.homematic.internal.model.HmDevice;
import org.openhab.binding.homematic.internal.type.UidUtils;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HomematicDeviceDiscoveryService} is used to discover devices that are connected to a Homematic gateway.
 *
 * @author Gerhard Riegler - Initial contribution
 */
public class HomematicDeviceDiscoveryService extends AbstractDiscoveryService
        implements DiscoveryService, ThingHandlerService {
    private final Logger logger = LoggerFactory.getLogger(HomematicDeviceDiscoveryService.class);
    private static final int DISCOVER_TIMEOUT_SECONDS = 300;

    private @NonNullByDefault({}) HomematicBridgeHandler bridgeHandler;
    private Future<?> loadDevicesFuture;
    private volatile boolean isInInstallMode = false;
    private volatile Object installModeSync = new Object();

    public HomematicDeviceDiscoveryService() {
        super(Set.of(new ThingTypeUID(BINDING_ID, "-")), DISCOVER_TIMEOUT_SECONDS, false);
    }

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        if (handler instanceof HomematicBridgeHandler homematicBridgeHandler) {
            this.bridgeHandler = homematicBridgeHandler;
            this.bridgeHandler.setDiscoveryService(this);
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return bridgeHandler;
    }

    /**
     * Called on component activation.
     */
    @Override
    public void activate() {
        super.activate(null);
    }

    @Override
    public void deactivate() {
        super.deactivate();
    }

    @Override
    protected void startScan() {
        logger.debug("Starting Homematic discovery scan");
        enableInstallMode();
        loadDevices();
    }

    /**
     * Will set controller in <i>installMode==true</i>, but only if the bridge
     * is ONLINE (e.g. not during INITIALIZATION).
     */
    private void enableInstallMode() {
        try {
            HomematicGateway gateway = bridgeHandler.getGateway();
            ThingStatus bridgeStatus = null;

            if (bridgeHandler != null) {
                Thing bridge = bridgeHandler.getThing();
                bridgeStatus = bridge.getStatus();
            }
            if (ThingStatus.ONLINE == bridgeStatus) {
                gateway.setInstallMode(true, getInstallModeDuration());

                int remaining = gateway.getInstallMode();
                if (remaining > 0) {
                    setIsInInstallMode();
                    logger.debug("Successfully put controller in install mode. Remaining time: {} seconds", remaining);
                } else {
                    logger.warn("Controller did not accept requested install mode");
                }
            } else {
                logger.debug("Will not attempt to set controller in install mode, because bridge is not ONLINE.");
            }
        } catch (Exception ex) {
            logger.warn("Failed to set Homematic controller in install mode", ex);
        }
    }

    private int getInstallModeDuration() {
        if (bridgeHandler != null) {
            return bridgeHandler.getThing().getConfiguration().as(HomematicConfig.class).getInstallModeDuration();
        }
        return HomematicConfig.DEFAULT_INSTALL_MODE_DURATION;
    }

    @Override
    public int getScanTimeout() {
        return getInstallModeDuration();
    }

    @Override
    public synchronized void stopScan() {
        logger.debug("Stopping Homematic discovery scan");
        if (bridgeHandler != null && bridgeHandler.getGateway() != null) {
            disableInstallMode();
            bridgeHandler.getGateway().cancelLoadAllDeviceMetadata();
        }
        waitForScanFinishing();
        super.stopScan();
    }

    private void disableInstallMode() {
        try {
            synchronized (installModeSync) {
                if (isInInstallMode) {
                    isInInstallMode = false;
                    installModeSync.notify();
                    bridgeHandler.getGateway().setInstallMode(false, 0);
                }
            }

        } catch (Exception ex) {
            logger.warn("Failed to disable Homematic controller's install mode", ex);
        }
    }

    private void setIsInInstallMode() {
        synchronized (installModeSync) {
            isInInstallMode = true;
        }
    }

    private void waitForInstallModeFinished(int timeout) throws InterruptedException {
        synchronized (installModeSync) {
            while (isInInstallMode) {
                installModeSync.wait(timeout);
            }
        }
    }

    private void waitForLoadDevicesFinished() throws InterruptedException, ExecutionException {
        if (loadDevicesFuture != null) {
            loadDevicesFuture.get();
        }
    }

    /**
     * Waits for the discovery scan to finish and then returns.
     */
    public void waitForScanFinishing() {
        logger.debug("Waiting for finishing Homematic device discovery scan");
        try {
            waitForInstallModeFinished(DISCOVER_TIMEOUT_SECONDS * 1000);
            waitForLoadDevicesFinished();
        } catch (ExecutionException | InterruptedException ex) {
            // ignore
        } catch (Exception ex) {
            logger.error("Error waiting for device discovery scan: {}", ex.getMessage(), ex);
        }
        String gatewayId = bridgeHandler != null && bridgeHandler.getGateway() != null
                ? bridgeHandler.getGateway().getId()
                : "UNKNOWN";
        logger.debug("Finished Homematic device discovery scan on gateway '{}'", gatewayId);
    }

    /**
     * Starts a thread which loads all Homematic devices connected to the gateway.
     */
    public void loadDevices() {
        if (loadDevicesFuture == null && bridgeHandler.getGateway() != null) {
            loadDevicesFuture = scheduler.submit(() -> {
                try {
                    final HomematicGateway gateway = bridgeHandler.getGateway();
                    gateway.loadAllDeviceMetadata();
                    bridgeHandler.getTypeGenerator().validateFirmwares();
                } catch (Throwable ex) {
                    logger.error("{}", ex.getMessage(), ex);
                } finally {
                    loadDevicesFuture = null;
                    bridgeHandler.setOfflineStatus();
                    removeOlderResults(getTimestampOfLastScan());
                }
            });
        } else {
            logger.debug("Homematic devices discovery scan in progress");
        }
    }

    /**
     * Removes the Homematic device.
     */
    public void deviceRemoved(HmDevice device) {
        ThingUID thingUID = UidUtils.generateThingUID(device, bridgeHandler.getThing());
        thingRemoved(thingUID);
    }

    /**
     * Generates the DiscoveryResult from a Homematic device.
     */
    public void deviceDiscovered(HmDevice device) {
        ThingUID bridgeUID = bridgeHandler.getThing().getUID();
        ThingTypeUID typeUid = UidUtils.generateThingTypeUID(device);
        ThingUID thingUID = new ThingUID(typeUid, bridgeUID, device.getAddress());
        String label = device.getName() != null ? device.getName() : device.getAddress();
        long timeToLive = bridgeHandler.getThing().getConfiguration().as(HomematicConfig.class)
                .getDiscoveryTimeToLive();

        DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID).withLabel(label)
                .withProperty(Thing.PROPERTY_SERIAL_NUMBER, device.getAddress())
                .withRepresentationProperty(Thing.PROPERTY_SERIAL_NUMBER).withTTL(timeToLive).build();
        thingDiscovered(discoveryResult);
    }
}
