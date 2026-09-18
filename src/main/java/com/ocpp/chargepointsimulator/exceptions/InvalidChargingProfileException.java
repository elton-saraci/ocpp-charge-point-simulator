package com.ocpp.chargepointsimulator.exceptions;

/**
 * Thrown when an incoming {@code SetChargingProfile} request breaks the rules of OCPP 1.6 smart
 * charging. The smart charging handler turns this into a {@code Rejected} charging profile status,
 * so the reason is always carried in the message.
 */
public class InvalidChargingProfileException extends RuntimeException {

    public InvalidChargingProfileException(String message) {
        super(message);
    }
}
