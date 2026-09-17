package com.ocpp.chargepointsimulator.exceptions;

/** Thrown when an operation targets a charge point that is not registered. Mapped to HTTP 404. */
public class ChargePointNotFoundException extends RuntimeException {

    public ChargePointNotFoundException(String message) {
        super(message);
    }
}
