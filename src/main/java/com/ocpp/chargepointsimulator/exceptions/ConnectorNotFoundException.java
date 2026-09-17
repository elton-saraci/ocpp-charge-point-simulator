package com.ocpp.chargepointsimulator.exceptions;

/** Thrown when an operation targets a connector that the charge point does not expose. Mapped to HTTP 404. */
public class ConnectorNotFoundException extends RuntimeException {

    public ConnectorNotFoundException(String message) {
        super(message);
    }
}
