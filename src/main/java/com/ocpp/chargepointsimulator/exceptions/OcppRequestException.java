package com.ocpp.chargepointsimulator.exceptions;

/**
 * Thrown when an OCPP message could not be exchanged with the central system, either because the
 * library reported a protocol violation, because the central system never answered, or because of
 * any other transport level failure.
 *
 * <p>Mapped to HTTP 502: the simulator itself is fine, the upstream central system interaction was
 * not.
 */
public class OcppRequestException extends RuntimeException {

    public OcppRequestException(String message) {
        super(message);
    }

    public OcppRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
