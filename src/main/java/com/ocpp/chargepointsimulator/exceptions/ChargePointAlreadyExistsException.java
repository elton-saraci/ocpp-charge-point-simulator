package com.ocpp.chargepointsimulator.exceptions;

/** Thrown when a charge point is registered twice. Mapped to HTTP 409. */
public class ChargePointAlreadyExistsException extends RuntimeException {

    public ChargePointAlreadyExistsException(String message) {
        super(message);
    }
}
