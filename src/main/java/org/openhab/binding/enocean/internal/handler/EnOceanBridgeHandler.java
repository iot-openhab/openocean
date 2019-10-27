/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
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
package org.openhab.binding.enocean.internal.handler;

import static org.openhab.binding.enocean.internal.EnOceanBindingConstants.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.core.status.ConfigStatusMessage;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.ConfigStatusBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.util.HexUtils;
import org.eclipse.smarthome.io.transport.serial.PortInUseException;
import org.eclipse.smarthome.io.transport.serial.SerialPortManager;
import org.openhab.binding.enocean.internal.EnOceanConfigStatusMessage;
import org.openhab.binding.enocean.internal.config.EnOceanBaseConfig;
import org.openhab.binding.enocean.internal.config.EnOceanBridgeConfig;
import org.openhab.binding.enocean.internal.messages.BaseResponse;
import org.openhab.binding.enocean.internal.messages.ESP3Packet;
import org.openhab.binding.enocean.internal.messages.ESP3PacketFactory;
import org.openhab.binding.enocean.internal.messages.RDBaseIdResponse;
import org.openhab.binding.enocean.internal.messages.RDRepeaterResponse;
import org.openhab.binding.enocean.internal.messages.RDVersionResponse;
import org.openhab.binding.enocean.internal.messages.Response;
import org.openhab.binding.enocean.internal.messages.Response.ResponseType;
import org.openhab.binding.enocean.internal.messages.SA_RDLearnedClientsResponse;
import org.openhab.binding.enocean.internal.messages.SA_RDLearnedClientsResponse.LearnedClient;
import org.openhab.binding.enocean.internal.transceiver.ESP3PacketListener;
import org.openhab.binding.enocean.internal.transceiver.EnOceanSerialTransceiver;
import org.openhab.binding.enocean.internal.transceiver.EnOceanTransceiver;
import org.openhab.binding.enocean.internal.transceiver.ResponseListener;
import org.openhab.binding.enocean.internal.transceiver.ResponseListenerIgnoringTimeouts;
import org.openhab.binding.enocean.internal.transceiver.TeachInListener;
import org.openhab.binding.enocean.internal.transceiver.TransceiverErrorListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EnOceanBridgeHandler} is responsible for sending ESP3Packages build by {@link EnOceanActuatorHandler} and
 * transferring received ESP3Packages to {@link EnOceanSensorHandler}.
 *
 * @author Daniel Weber - Initial contribution
 */
public class EnOceanBridgeHandler extends ConfigStatusBridgeHandler implements TransceiverErrorListener {

    private Logger logger = LoggerFactory.getLogger(EnOceanBridgeHandler.class);

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<>(Arrays.asList(THING_TYPE_BRIDGE));

    private EnOceanTransceiver transceiver; // holds connection to serial/tcp port and sends/receives messages
    private ScheduledFuture<?> connectorTask; // is used for reconnection if something goes wrong
    private ScheduledFuture<?> refreshPropertiesTask; // is used for refreshing properties

    private byte[] baseId = null;
    private Thing[] sendingThings = new Thing[128];

    private int nextSenderId = 0;
    private SerialPortManager serialPortManager;
    private boolean smackAvailable = false;

    public EnOceanBridgeHandler(Bridge bridge, SerialPortManager serialPortManager) {
        super(bridge);
        this.serialPortManager = serialPortManager;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (transceiver == null) {
            updateStatus(ThingStatus.OFFLINE);
            return;
        }

        switch (channelUID.getId()) {
            case CHANNEL_REPEATERMODE:
                if (command instanceof RefreshType) {
                    sendMessage(ESP3PacketFactory.CO_RD_REPEATER,
                            new ResponseListenerIgnoringTimeouts<RDRepeaterResponse>() {

                                @Override
                                public void responseReceived(RDRepeaterResponse response) {
                                    if (response.isValid() && response.isOK()) {
                                        updateState(channelUID, response.getRepeaterLevel());
                                    } else {
                                        updateState(channelUID, new StringType(REPEATERMODE_OFF));
                                    }
                                }
                            });
                } else if (command instanceof StringType) {
                    sendMessage(ESP3PacketFactory.CO_WR_REPEATER((StringType) command),
                            new ResponseListenerIgnoringTimeouts<BaseResponse>() {

                                @Override
                                public void responseReceived(BaseResponse response) {
                                    if (response.isOK()) {
                                        updateState(channelUID, (StringType) command);
                                    }
                                }

                            });
                }
                break;

            case CHANNEL_SETBASEID:
                if (command instanceof StringType) {
                    try {

                        byte[] id = HexUtils.hexToBytes(((StringType) command).toFullString());

                        sendMessage(ESP3PacketFactory.CO_WR_IDBASE(id),
                                new ResponseListenerIgnoringTimeouts<BaseResponse>() {

                                    @Override
                                    public void responseReceived(BaseResponse response) {

                                        if (response.isOK()) {
                                            updateState(channelUID, new StringType("New Id successfully set"));
                                        } else if (response.getResponseType() == ResponseType.RET_FLASH_HW_ERROR) {
                                            updateState(channelUID,
                                                    new StringType("The write/erase/verify process failed"));
                                        } else if (response.getResponseType() == ResponseType.RET_BASEID_OUT_OF_RANGE) {
                                            updateState(channelUID, new StringType("Base id out of range"));
                                        } else if (response.getResponseType() == ResponseType.RET_BASEID_MAX_REACHED) {
                                            updateState(channelUID, new StringType("No more change possible"));
                                        }
                                    }

                                });

                    } catch (IllegalArgumentException e) {
                        updateState(channelUID, new StringType("BaseId could not be parsed"));
                    }
                }
                break;

            default:
                break;
        }
    }

    @Override
    public void initialize() {

        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "trying to connect to gateway...");
        if (this.serialPortManager == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "SerialPortManager could not be found");
        } else {
            Integer devId = getConfig().as(EnOceanBridgeConfig.class).nextSenderId;
            if (devId != null) {
                nextSenderId = devId;
            } else {
                nextSenderId = 0;
            }

            if (connectorTask == null || connectorTask.isDone()) {
                connectorTask = scheduler.scheduleWithFixedDelay(new Runnable() {

                    @Override
                    public void run() {
                        if (thing.getStatus() != ThingStatus.ONLINE) {
                            initTransceiver();
                        }
                    }

                }, 0, 60, TimeUnit.SECONDS);
            }

            if(refreshPropertiesTask == null || refreshPropertiesTask.isDone()) {
                refreshPropertiesTask = scheduler.scheduleWithFixedDelay(new Runnable() {

                    @Override
                    public void run() {
                        refreshProperties();
                    }
                }, 300, 300, TimeUnit.SECONDS);
            }
        }
    }

    private synchronized void initTransceiver() {

        try {

            EnOceanBridgeConfig c = getThing().getConfiguration().as(EnOceanBridgeConfig.class);
            if (transceiver != null) {
                transceiver.ShutDown();
            }

            transceiver = new EnOceanSerialTransceiver(c.path, this, scheduler, serialPortManager);

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "opening serial port...");
            transceiver.Initialize();

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "starting rx thread...");
            transceiver.StartReceiving(scheduler);

            if (c.rs485) {
                baseId = new byte[4];
                updateStatus(ThingStatus.ONLINE);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "trying to get bridge base id...");
                logger.debug("request base id");
                transceiver.sendESP3Packet(ESP3PacketFactory.CO_RD_IDBASE,
                        new ResponseListenerIgnoringTimeouts<RDBaseIdResponse>() {

                            @Override
                            public void responseReceived(RDBaseIdResponse response) {

                                logger.debug("received response for base id");

                                if (response.isValid() && response.isOK()) {
                                    baseId = response.getBaseId().clone();
                                    updateProperty(PROPERTY_BASE_ID, HexUtils.bytesToHex(response.getBaseId()));
                                    updateProperty(PROPERTY_REMAINING_WRITE_CYCLES_Base_ID,
                                            Integer.toString(response.getRemainingWriteCycles()));
                                    transceiver.setFilteredDeviceId(baseId);

                                    updateStatus(ThingStatus.ONLINE);
                                } else {
                                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Could not get BaseId");
                                }
                            }
                        });
            }

            transceiver.sendESP3Packet(ESP3PacketFactory.CO_RD_VERSION,
                    new ResponseListenerIgnoringTimeouts<RDVersionResponse>() {

                        @Override
                        public void responseReceived(RDVersionResponse response) {

                            if (response.isValid() && response.isOK()) {
                                updateProperty(PROPERTY_APP_VERSION, response.getAPPVersion());
                                updateProperty(PROPERTY_API_VERSION, response.getAPIVersion());
                                updateProperty(PROPERTY_CHIP_ID, response.getChipID());
                                updateProperty(PROPERTY_DESCRIPTION, response.getDescription());
                            }
                        }
                    });

            logger.debug("set postmaster mailboxes");
            transceiver.sendESP3Packet(ESP3PacketFactory.SA_WR_POSTMASTER((byte) (c.enableSmack ? 20 : 0)),
                    new ResponseListenerIgnoringTimeouts<BaseResponse>() {

                        @Override
                        public void responseReceived(BaseResponse response) {

                            logger.debug("received response for postmaster mailboxes");
                            if (response.isOK()) {
                                updateProperty("Postmaster mailboxes:", Integer.toString(c.enableSmack ? 20 : 0));
                                smackAvailable = c.enableSmack;
                                refreshProperties();
                            } else {
                                updateProperty("Postmaster mailboxes:", "Not supported");
                                smackAvailable = false;
                            }
                        }
                    });

        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Port could not be found");
        } catch (PortInUseException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Port already in use");
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Port could not be initialized");
            return;
        }
    }

    private void refreshProperties() {
        if (getThing().getStatus() == ThingStatus.ONLINE && smackAvailable) {

            logger.debug("request learned smack clients");
            try {
                transceiver.sendESP3Packet(ESP3PacketFactory.SA_RD_LEARNEDCLIENTS,
                        new ResponseListenerIgnoringTimeouts<SA_RDLearnedClientsResponse>() {

                            @Override
                            public void responseReceived(SA_RDLearnedClientsResponse response) {

                                logger.debug("received response for learned smack clients");
                                if (response.isValid() && response.isOK()) {
                                    LearnedClient[] clients = response.getLearnedClients();
                                    updateProperty("Learned smart ack clients", Integer.toString(clients.length));

                                    final StringBuffer buffer = new StringBuffer();
                                    for(int i = 0; i < clients.length; i++) {                                        
                                            buffer.append(String.format("%s (MB Idx: %d), ", HexUtils.bytesToHex(clients[i].clientId), clients[i].mailboxIndex));
                                    }
                                    updateProperty("Smart ack clients", buffer.toString());
                                }
                            }
                        });
            } catch (IOException e) {
                logger.debug("Exception during refresh of properties", e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Smack packet could not be send");
                
            }
        }
    }

    @Override
    public synchronized void dispose() {
        if (transceiver != null) {
            transceiver.ShutDown();
            transceiver = null;
        }

        if (connectorTask != null && !connectorTask.isDone()) {
            connectorTask.cancel(true);
            connectorTask = null;
        }

        if (refreshPropertiesTask != null && !refreshPropertiesTask.isDone()) {
            refreshPropertiesTask.cancel(true);
            refreshPropertiesTask = null;
        }

        super.dispose();
    }

    @Override
    public Collection<ConfigStatusMessage> getConfigStatus() {
        Collection<ConfigStatusMessage> configStatusMessages = new LinkedList<>();

        // The serial port must be provided
        String path = getThing().getConfiguration().as(EnOceanBridgeConfig.class).path;
        if (path == null || path.isEmpty()) {
            configStatusMessages.add(ConfigStatusMessage.Builder.error(PATH)
                    .withMessageKeySuffix(EnOceanConfigStatusMessage.PORT_MISSING.getMessageKey()).withArguments(PATH)
                    .build());
        }

        return configStatusMessages;
    }

    public byte[] getBaseId() {
        return baseId.clone();
    }

    public int getNextSenderId(Thing sender) {
        // TODO: change id to enoceanId
        return getNextSenderId(sender.getConfiguration().as(EnOceanBaseConfig.class).enoceanId);
    }

    public int getNextSenderId(String senderId) {
        if (nextSenderId != 0 && sendingThings[nextSenderId] == null) {
            int result = nextSenderId;
            EnOceanBridgeConfig config = getConfig().as(EnOceanBridgeConfig.class);
            config.nextSenderId = null;
            updateConfiguration(config);
            nextSenderId = 0;

            return result;
        }

        for (byte i = 1; i < sendingThings.length; i++) {
            if (sendingThings[i] == null || sendingThings[i].getConfiguration().as(EnOceanBaseConfig.class).enoceanId
                    .equalsIgnoreCase(senderId)) {
                return i;
            }
        }

        return -1;
    }

    public boolean existsSender(int id, Thing sender) {
        return sendingThings[id] != null && !sendingThings[id].getConfiguration().as(EnOceanBaseConfig.class).enoceanId
                .equalsIgnoreCase(sender.getConfiguration().as(EnOceanBaseConfig.class).enoceanId);
    }

    public void addSender(int id, Thing thing) {
        sendingThings[id] = thing;
    }

    public void removeSender(int id) {
        sendingThings[id] = null;
    }

    public <T extends Response> void sendMessage(ESP3Packet message, ResponseListener<T> responseListener) {
        try {
            transceiver.sendESP3Packet(message, responseListener);
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    public void addPacketListener(ESP3PacketListener listener) {
        addPacketListener(listener, listener.getSenderIdToListenTo());
    }

    public void addPacketListener(ESP3PacketListener listener, long senderIdToListenTo) {
        if (transceiver != null) {
            transceiver.addPacketListener(listener, senderIdToListenTo);
        }
    }

    public void removePacketListener(ESP3PacketListener listener) {
        removePacketListener(listener, listener.getSenderIdToListenTo());
    }

    public void removePacketListener(ESP3PacketListener listener, long senderIdToListenTo) {
        if (transceiver != null) {
            transceiver.removePacketListener(listener, senderIdToListenTo);
        }
    }

    public void startDiscovery(TeachInListener teachInListener) {
        transceiver.startDiscovery(teachInListener);

        if (smackAvailable) {
            // activate smack teach in
            logger.debug("activate smack teach in");
            try {
                transceiver.sendESP3Packet(ESP3PacketFactory.SA_WR_LEARNMODE(true),
                        new ResponseListenerIgnoringTimeouts<BaseResponse>() {

                            @Override
                            public void responseReceived(BaseResponse response) {

                                if (response.isOK()) {
                                    logger.debug("Smack teach in activated");
                                }
                            }
                        });
            } catch (IOException e) {
                logger.debug("Exception during activating smack teach in: {}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Smack packet could not be send");
            }
        }
    }

    public void stopDiscovery() {
        transceiver.stopDiscovery();
        try {
            transceiver.sendESP3Packet(ESP3PacketFactory.SA_WR_LEARNMODE(false), null);
        } catch (IOException e) {
            logger.debug("Exception during deactivating smack teach in: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Smack packet could not be send");
        }
    }

    @Override
    public void ErrorOccured(Throwable exception) {
        transceiver.ShutDown();
        transceiver = null;
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, exception.getMessage());

    }
}
