package com.ocpp.chargepointsimulator.domain;

import com.ocpp.chargepointsimulator.exceptions.ConnectorNotFoundException;
import eu.chargetime.ocpp.JSONClient;
import lombok.Getter;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A single simulated charge point: its configuration, its connectors and its own WebSocket client
 * towards the central system.
 *
 * <p>Sessions replace the former single global {@code ChargePointConfiguration} singleton, which
 * meant only one charge point could exist per application instance. A session is created by
 * {@code ChargePointSessionFactory}, which also attaches the {@link JSONClient}.
 */
public class ChargePointSession {

    /** Heartbeats are sent at a fixed interval, matching the behavior of the original simulator. */
    private static final long HEARTBEAT_INTERVAL_MILLIS = 15_000L;

    @Getter
    private final ChargePointConfig config;
    private final Map<Integer, ConnectorState> connectors;
    /** The charging profiles the central system pushed to this charge point. */
    @Getter
    private final ChargingProfileStore chargingProfiles = new ChargingProfileStore();
    /** When the oldest connector of this charge point started charging, used by Relative profiles. */
    private volatile Instant lastTransactionStart;

    private volatile JSONClient client;
    @Getter
    private volatile boolean connected;
    private volatile long lastHeartbeatEpochMillis;
    private volatile String lastError;
    private volatile Instant lastErrorAt;

    public ChargePointSession(ChargePointConfig config) {
        this.config = config;
        Map<Integer, ConnectorState> connectorStates = new LinkedHashMap<>();
        config.connectorIds().forEach(connectorId -> connectorStates.put(connectorId, new ConnectorState(connectorId)));
        this.connectors = Collections.unmodifiableMap(connectorStates);
    }

    /**
     * Attaches the WebSocket client of this session.
     *
     * <p>Called exactly once by {@code ChargePointSessionFactory}: the client needs the event
     * handlers which in turn need this session, so the reference is completed after construction.
     */
    public void attachClient(JSONClient client) {
        this.client = client;
    }

    public JSONClient getClient() {
        JSONClient currentClient = client;
        if (currentClient == null) {
            throw new IllegalStateException(
                    "No JSONClient attached to charge point '" + getChargePointId() + "' yet.");
        }
        return currentClient;
    }

    public String getChargePointId() {
        return config.chargePointId();
    }

    public String webSocketUrl() {
        return config.webSocketUrl();
    }

    public Collection<ConnectorState> getConnectors() {
        return connectors.values();
    }

    /** @return connector id to the id of the transaction running on it, for TxProfile validation. */
    public Map<Integer, Integer> runningTransactions() {
        Map<Integer, Integer> running = new LinkedHashMap<>();
        connectors.forEach((connectorId, connector) -> {
            Integer transactionId = connector.getTransactionId();
            if (transactionId != null) {
                running.put(connectorId, transactionId);
            }
        });
        return running;
    }

    /**
     * Remembers when a transaction started, which is the anchor of a {@code Relative} charging
     * schedule. The value survives until the last transaction of this charge point is over.
     */
    public void transactionStarted(Instant startedAt) {
        this.lastTransactionStart = startedAt;
    }

    /** @return start of the running transaction, or of the last one when nothing charges. */
    public Instant transactionStart() {
        for (ConnectorState connector : connectors.values()) {
            Instant startedAt = connector.getTransactionStartedAt();
            if (startedAt != null) {
                return startedAt;
            }
        }
        return lastTransactionStart;
    }

    /** @throws ConnectorNotFoundException when this charge point does not expose the connector. */
    public ConnectorState getConnector(int connectorId) {
        return findConnector(connectorId).orElseThrow(() -> new ConnectorNotFoundException(
                "Charge point '" + getChargePointId() + "' has no connector " + connectorId
                        + ", configured connectors are " + config.connectorIds() + "."));
    }

    public Optional<ConnectorState> findConnector(int connectorId) {
        return Optional.ofNullable(connectors.get(connectorId));
    }

    public Optional<ConnectorState> findConnectorByTransactionId(Integer transactionId) {
        if (transactionId == null) {
            return Optional.empty();
        }
        return connectors.values().stream()
                .filter(connector -> transactionId.equals(connector.getTransactionId()))
                .findFirst();
    }

    public void markConnected() {
        this.connected = true;
        // Heartbeats start with the first interval after connecting, not immediately.
        this.lastHeartbeatEpochMillis = System.currentTimeMillis();
        clearError();
    }

    public void markDisconnected() {
        this.connected = false;
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    public Optional<Instant> getLastErrorAt() {
        return Optional.ofNullable(lastErrorAt);
    }

    /**
     * Records a failure so it stays observable through the REST API. Used for failures that cannot
     * be returned to a caller, e.g. errors raised while handling an incoming OCPP request or from a
     * scheduled job.
     */
    public void recordError(String error) {
        this.lastError = error;
        this.lastErrorAt = Instant.now();
    }

    public void clearError() {
        this.lastError = null;
        this.lastErrorAt = null;
    }

    public boolean isHeartbeatDue(long nowEpochMillis) {
        return lastHeartbeatEpochMillis == 0
                || nowEpochMillis - lastHeartbeatEpochMillis >= HEARTBEAT_INTERVAL_MILLIS;
    }

    public void markHeartbeatSent(long epochMillis) {
        this.lastHeartbeatEpochMillis = epochMillis;
    }

    /** @return a human-readable summary of the connector states, handy for logs. */
    public String describeConnectors() {
        return connectors.values().stream()
                .map(connector -> "#" + connector.getConnectorId() + "=" + connector.getStatus()
                        + (connector.hasTransaction() ? "(tx=" + connector.getTransactionId() + ")" : ""))
                .collect(Collectors.joining(", "));
    }
}
