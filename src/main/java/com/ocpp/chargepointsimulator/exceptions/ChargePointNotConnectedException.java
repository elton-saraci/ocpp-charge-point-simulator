package com.ocpp.chargepointsimulator.exceptions;

/**
 * Thrown when a message is sent over a charge point session that has no live WebSocket connection to
 * the central system. Mapped to HTTP 409.
 */
public class ChargePointNotConnectedException extends OcppRequestException {

    public ChargePointNotConnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
