/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.deconz.internal.handler;

import static org.openhab.binding.deconz.internal.BindingConstants.*;
import static org.openhab.binding.deconz.internal.Util.buildUrl;

import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.deconz.internal.discovery.ThingDiscoveryService;
import org.openhab.binding.deconz.internal.dto.ApiKeyMessage;
import org.openhab.binding.deconz.internal.dto.BridgeFullState;
import org.openhab.binding.deconz.internal.netutils.AsyncHttpClient;
import org.openhab.binding.deconz.internal.netutils.WebSocketConnection;
import org.openhab.binding.deconz.internal.netutils.WebSocketConnectionListener;
import org.openhab.core.cache.ExpiringCacheAsync;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The bridge Thing is responsible for requesting all available sensors and switches and propagate
 * them to the discovery service.
 *
 * It performs the authorization process if necessary.
 *
 * A websocket connection is established to the deCONZ software and kept alive.
 *
 * @author David Graeff - Initial contribution
 */
@NonNullByDefault
public class DeconzBridgeHandler extends BaseBridgeHandler implements WebSocketConnectionListener {
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(BRIDGE_TYPE);

    private final Logger logger = LoggerFactory.getLogger(DeconzBridgeHandler.class);
    private final WebSocketConnection websocket;
    private final AsyncHttpClient http;
    private DeconzBridgeConfig config = new DeconzBridgeConfig();
    private final Gson gson;
    private @Nullable ScheduledFuture<?> scheduledFuture;
    private int websocketPort = 0;
    /** Prevent a dispose/init cycle while this flag is set. Use for property updates */
    private boolean ignoreConfigurationUpdate;
    private boolean websocketReconnect = false;

    private final ExpiringCacheAsync<Optional<BridgeFullState>> fullStateCache = new ExpiringCacheAsync<>(1000);

    /** The poll frequency for the API Key verification */
    private static final int POLL_FREQUENCY_SEC = 10;

    public DeconzBridgeHandler(Bridge thing, WebSocketFactory webSocketFactory, AsyncHttpClient http, Gson gson) {
        super(thing);
        this.http = http;
        this.gson = gson;
        String websocketID = thing.getUID().getAsString().replace(':', '-');
        if (websocketID.length() < 4) {
            websocketID = "openHAB-deconz-" + websocketID;
        } else if (websocketID.length() > 20) {
            websocketID = websocketID.substring(websocketID.length() - 20);
        }
        this.websocket = new WebSocketConnection(this, webSocketFactory.createWebSocketClient(websocketID), gson);
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(ThingDiscoveryService.class);
    }

    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {
        if (!ignoreConfigurationUpdate) {
            super.handleConfigurationUpdate(configurationParameters);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    /**
     * Stops the API request or websocket reconnect timer
     */
    private void stopTimer() {
        ScheduledFuture<?> future = scheduledFuture;
        if (future != null) {
            future.cancel(true);
            scheduledFuture = null;
        }
    }

    /**
     * Parses the response message to the API key generation REST API.
     *
     * @param r The response
     */
    private void parseAPIKeyResponse(AsyncHttpClient.Result r) {
        if (r.getResponseCode() == 403) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "Allow authentication for 3rd party apps. Trying again in " + POLL_FREQUENCY_SEC + " seconds");
            stopTimer();
            scheduledFuture = scheduler.schedule(() -> requestApiKey(), POLL_FREQUENCY_SEC, TimeUnit.SECONDS);
        } else if (r.getResponseCode() == 200) {
            ApiKeyMessage[] response = Objects.requireNonNull(gson.fromJson(r.getBody(), ApiKeyMessage[].class));
            if (response.length == 0) {
                throw new IllegalStateException("Authorisation request response is empty");
            }
            config.apikey = response[0].success.username;
            Configuration configuration = editConfiguration();
            configuration.put(CONFIG_APIKEY, config.apikey);
            updateConfiguration(configuration);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "Waiting for configuration");
            initializeBridgeState();
        } else {
            throw new IllegalStateException("Unknown status code for authorisation request");
        }
    }

    /**
     * get the full state of the bridge from the cache
     *
     * @return a CompletableFuture that returns an Optional of the bridge full state
     */
    public CompletableFuture<Optional<BridgeFullState>> getBridgeFullState() {
        return fullStateCache.getValue(this::refreshFullStateCache);
    }

    /**
     * refresh the full bridge state (used for initial processing and state-lookup)
     *
     * @return Completable future with an Optional of the BridgeFullState
     */
    private CompletableFuture<Optional<BridgeFullState>> refreshFullStateCache() {
        logger.trace("{} starts refreshing the fullStateCache", thing.getUID());
        if (config.apikey == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String url = buildUrl(config.getHostWithoutPort(), config.httpPort, config.apikey);
        return http.get(url, config.timeout).thenApply(r -> {
            if (r.getResponseCode() == 403) {
                return Optional.ofNullable((BridgeFullState) null);
            } else if (r.getResponseCode() == 200) {
                return Optional.ofNullable(gson.fromJson(r.getBody(), BridgeFullState.class));
            } else {
                throw new IllegalStateException("Unknown status code for full state request");
            }
        }).handle((v, t) -> {
            if (t == null) {
                return v;
            } else if (t instanceof SocketTimeoutException || t instanceof TimeoutException
                    || t instanceof CompletionException) {
                logger.debug("Get full state failed", t);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, t.getMessage());
            }
            return Optional.empty();
        });
    }

    /**
     * Perform a request to the REST API for retrieving the full bridge state with all sensors and switches
     * and configuration.
     */
    public void initializeBridgeState() {
        getBridgeFullState().thenAccept(fullState -> fullState.ifPresentOrElse(state -> {
            if (state.config.name.isEmpty()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE,
                        "You are connected to a HUE bridge, not a deCONZ software!");
                return;
            }
            if (state.config.websocketport == 0) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE,
                        "deCONZ software too old. No websocket support!");
                return;
            }

            // Add some information about the bridge
            Map<String, String> editProperties = editProperties();
            editProperties.put("apiversion", state.config.apiversion);
            editProperties.put("swversion", state.config.swversion);
            editProperties.put("fwversion", state.config.fwversion);
            editProperties.put("uuid", state.config.uuid);
            editProperties.put("zigbeechannel", String.valueOf(state.config.zigbeechannel));
            editProperties.put("ipaddress", state.config.ipaddress);
            ignoreConfigurationUpdate = true;
            updateProperties(editProperties);
            ignoreConfigurationUpdate = false;

            // Use requested websocket port if no specific port is given
            websocketPort = config.port == 0 ? state.config.websocketport : config.port;
            websocketReconnect = true;
            startWebsocket();
        }, () -> {
            // initial response was empty, re-trying in POLL_FREQUENCY_SEC seconds
            scheduledFuture = scheduler.schedule(this::initializeBridgeState, POLL_FREQUENCY_SEC, TimeUnit.SECONDS);
        })).exceptionally(e -> {
            if (e != null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, e.getMessage());
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE);
            }
            logger.warn("Initial full state parsing failed", e);
            return null;
        });
    }

    /**
     * Starts the websocket connection.
     * {@link #initializeBridgeState} need to be called first to obtain the websocket port.
     */
    private void startWebsocket() {
        if (websocket.isConnected() || websocketPort == 0 || websocketReconnect == false) {
            return;
        }

        stopTimer();
        scheduledFuture = scheduler.schedule(this::startWebsocket, POLL_FREQUENCY_SEC, TimeUnit.SECONDS);

        websocket.start(config.getHostWithoutPort() + ":" + websocketPort);
    }

    /**
     * Perform a request to the REST API for generating an API key.
     *
     */
    private CompletableFuture<?> requestApiKey() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "Requesting API Key");
        stopTimer();
        String url = buildUrl(config.getHostWithoutPort(), config.httpPort);
        return http.post(url, "{\"devicetype\":\"openHAB\"}", config.timeout).thenAccept(this::parseAPIKeyResponse)
                .exceptionally(e -> {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                    logger.warn("Authorisation failed", e);
                    return null;
                });
    }

    @Override
    public void initialize() {
        logger.debug("Start initializing!");
        config = getConfigAs(DeconzBridgeConfig.class);
        if (config.apikey == null) {
            requestApiKey();
        } else {
            initializeBridgeState();
        }
    }

    @Override
    public void dispose() {
        websocketReconnect = false;
        stopTimer();
        websocket.close();
    }

    @Override
    public void connectionError(@Nullable Throwable e) {
        if (e != null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Unknown reason");
        }
        stopTimer();
        // Wait for POLL_FREQUENCY_SEC after a connection error before trying again
        scheduledFuture = scheduler.schedule(this::startWebsocket, POLL_FREQUENCY_SEC, TimeUnit.SECONDS);
    }

    @Override
    public void connectionEstablished() {
        stopTimer();
        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void connectionLost(String reason) {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, reason);
        startWebsocket();
    }

    /**
     * Return the websocket connection.
     */
    public WebSocketConnection getWebsocketConnection() {
        return websocket;
    }

    /**
     * Return the http connection.
     */
    public AsyncHttpClient getHttp() {
        return http;
    }

    /**
     * Return the bridge configuration.
     */
    public DeconzBridgeConfig getBridgeConfig() {
        return config;
    }
}
