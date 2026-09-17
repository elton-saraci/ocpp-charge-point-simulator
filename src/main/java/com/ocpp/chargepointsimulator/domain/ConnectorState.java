package com.ocpp.chargepointsimulator.domain;

import eu.chargetime.ocpp.model.core.ChargePointStatus;

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
    private long lastMeterValuesEpochMillis;

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
    }

    public synchronized void clearTransaction() {
        this.transactionId = null;
        this.idTag = null;
    }

    public synchronized int getCurrentMeterValueWh() {
        return currentMeterValueWh;
    }

    /** Advances the energy register by one metering interval and returns the new register value. */
    public synchronized int advanceMeterValueWh(int stepWh) {
        currentMeterValueWh += stepWh;
        return currentMeterValueWh;
    }

    public synchronized void markMeterValuesSent(long epochMillis) {
        this.lastMeterValuesEpochMillis = epochMillis;
    }

    /** @return {@code true} when a MeterValues message is due for the given metering interval. */
    public synchronized boolean isMeterValuesDue(long nowEpochMillis, int frequencySeconds) {
        return lastMeterValuesEpochMillis == 0 || nowEpochMillis - lastMeterValuesEpochMillis >= frequencySeconds * 1000L;
    }
}
