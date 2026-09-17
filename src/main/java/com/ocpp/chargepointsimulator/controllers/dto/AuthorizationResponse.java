package com.ocpp.chargepointsimulator.controllers.dto;

import com.ocpp.chargepointsimulator.domain.AuthorizationOutcome;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import eu.chargetime.ocpp.model.core.AuthorizationStatus;

/**
 * Result of an RFID authorization request.
 *
 * @param accepted             whether the central system accepted the id tag
 * @param centralSystemStatus  status the central system answered with, {@code null} when it sent none
 * @param connector            state of the connector after the request
 */
public record AuthorizationResponse(
        boolean accepted,
        AuthorizationStatus centralSystemStatus,
        ConnectorResponse connector) {

    public static AuthorizationResponse from(AuthorizationOutcome outcome, ConnectorState connector) {
        return new AuthorizationResponse(
                outcome.accepted(),
                outcome.centralSystemStatus(),
                ConnectorResponse.from(connector));
    }
}
