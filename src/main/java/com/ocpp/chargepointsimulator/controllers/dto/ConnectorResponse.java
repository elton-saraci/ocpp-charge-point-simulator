package com.ocpp.chargepointsimulator.controllers.dto;

import com.ocpp.chargepointsimulator.domain.ConnectorState;
import eu.chargetime.ocpp.model.core.ChargePointStatus;

import java.time.Instant;

/**
 * State of one connector of a simulated charge point.
 *
 * @param connectorId         connector id, always greater than zero
 * @param status              current OCPP status of the connector
 * @param idTag               id tag of the running transaction, {@code null} when there is none
 * @param transactionId       id of the running transaction, {@code null} when there is none
 * @param transactionStartedAt when the running transaction started, {@code null} when there is none
 * @param meterValueWh        current value of the simulated energy register in Wh
 * @param chargingLimitW      power the stored charging profiles allow right now, {@code null} when
 *                            no profile applies and the connector uses the nominal power
 * @param suspended           whether a charging profile holds the connector at zero power
 */
public record ConnectorResponse(
        int connectorId,
        ChargePointStatus status,
        String idTag,
        Integer transactionId,
        Instant transactionStartedAt,
        int meterValueWh,
        Integer chargingLimitW,
        boolean suspended) {

    public static ConnectorResponse from(ConnectorState connector) {
        return new ConnectorResponse(
                connector.getConnectorId(),
                connector.getStatus(),
                connector.getIdTag(),
                connector.getTransactionId(),
                connector.getTransactionStartedAt(),
                connector.getCurrentMeterValueWh(),
                connector.getChargingLimitW(),
                connector.isSuspended());
    }
}
