package com.ocpp.chargepointsimulator.exceptions;

/**
 * Thrown when a charge point definition is incomplete or contains values the simulator cannot work
 * with. Mapped to HTTP 400 by the API layer.
 */
public class InvalidChargePointConfigException extends RuntimeException {

    public InvalidChargePointConfigException(String message) {
        super(message);
    }

    public InvalidChargePointConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
