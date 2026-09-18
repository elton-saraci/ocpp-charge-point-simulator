package com.ocpp.chargepointsimulator.domain;

import eu.chargetime.ocpp.model.core.ChargePointStatus;

import java.time.Instant;

/**
 * Mutable runtime state of a single connector.
 *
 * <p>Every accessor is synchronized on purpose: a connector is mutated from the OCPP receiver
 * thread, from the metering scheduler and from HTTP request threads.
 */
public class ConnectorState {

    private final int connectorId;
    private ChargePointStatus status = ChargePointStatus.Available;
    private int currentMeterValueWh = 0;
    private String idTag;
    private Integer transactionId;
    private Instant transactionStartedAt;
    private long lastMeterValuesEpochMillis;
    /** Limit imposed by the stored charging profiles, {@code null} when none applies. */
    private Integer chargingLimitW;
    /** Set while a charging profile holds this connector at zero power. */
    private boolean suspended;

    public ConnectorState(int connectorId) {
        this.connectorId = connectorId;
    }

    public synchronized int getConnectorId() {
        return connectorId;
    }

    public synchronized ChargePointStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(ChargePointStatus status) {
        this.status = status;
    }

    public synchronized boolean isCharging() {
        return ChargePointStatus.Charging == status;
    }

    /** @return the power limit the charging profiles impose, {@code null} when no profile applies. */
    public synchronized Integer getChargingLimitW() {
        return chargingLimitW;
    }

    public synchronized void setChargingLimitW(Integer chargingLimitW) {
        this.chargingLimitW = chargingLimitW;
    }

    /** @return whether a charging profile holds this connector at zero power right now. */
    public synchronized boolean isSuspended() {
        return suspended;
    }

    public synchronized void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public synchronized boolean hasTransaction() {
        return transactionId != null;
    }

    public synchronized Integer getTransactionId() {
        return transactionId;
    }

    public synchronized String getIdTag() {
        return idTag;
    }

    public synchronized void setIdTag(String idTag) {
        this.idTag = idTag;
    }

    /** Registers the accepted transaction and moves the connector into the given status. */
    public synchronized void startTransaction(Integer transactionId, String idTag, ChargePointStatus status) {
        this.transactionId = transactionId;
        this.idTag = idTag;
        this.status = status;
        this.transactionStartedAt = Instant.now();
    }

    /** @return when the running transaction started, {@code null} when there is none. */
    public synchronized Instant getTransactionStartedAt() {
        return transactionStartedAt;
    }

    public synchronized void clearTransaction() {
        this.transactionId = null;
        this.idTag = null;
        this.transactionStartedAt = null;
        this.suspended = false;
    }

    public synchronized int getCurrentMeterValueWh() {
        return currentMeterValueWh;
    }

    /** Advances the energy register by one metering interval and returns the new register value. */
    public synchronized int advanceMeterValueWh(int stepWh) {
        currentMeterValueWh += stepWh;
        return currentMeterValueWh;
    }

    /**
     * Restores the energy register, used when a charge point is redefined and the physical meter of a
     * connector keeps counting across the change.
     */
    public synchronized void restoreMeterValueWh(int meterValueWh) {
        this.currentMeterValueWh = meterValueWh;
    }

    public synchronized void markMeterValuesSent(long epochMillis) {
        this.lastMeterValuesEpochMillis = epochMillis;
    }

    /** @return {@code true} when a MeterValues message is due for the given metering interval. */
    public synchronized boolean isMeterValuesDue(long nowEpochMillis, int frequencySeconds) {
        return lastMeterValuesEpochMillis == 0 || nowEpochMillis - lastMeterValuesEpochMillis >= frequencySeconds * 1000L;
    }
}
