package com.ocpp.chargepointsimulator.controllers.dto;

import com.ocpp.chargepointsimulator.domain.ConnectorState;
import eu.chargetime.ocpp.model.core.ChargePointStatus;

/**
 * State of one connector of a simulated charge point.
 *
 * @param connectorId    connector id, always greater than zero
 * @param status         current OCPP status of the connector
 * @param idTag          id tag of the running transaction, {@code null} when there is none
 * @param transactionId  id of the running transaction, {@code null} when there is none
 * @param meterValueWh   current value of the simulated energy register in Wh
 */
public record ConnectorResponse(
        int connectorId,
        ChargePointStatus status,
        String idTag,
        Integer transactionId,
        int meterValueWh) {

    public static ConnectorResponse from(ConnectorState connector) {
        return new ConnectorResponse(
                connector.getConnectorId(),
                connector.getStatus(),
                connector.getIdTag(),
                connector.getTransactionId(),
                connector.getCurrentMeterValueWh());
    }
}
