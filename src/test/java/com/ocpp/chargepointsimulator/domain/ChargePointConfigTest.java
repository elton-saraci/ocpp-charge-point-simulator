package com.ocpp.chargepointsimulator.domain;

import com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a charge point definition has to satisfy: what is accepted, what is normalised and what is
 * rejected before a session is ever created.
 */
class ChargePointConfigTest {

    @Test
    void buildsTheWebSocketUrlFromTheCentralSystemUrl() {
        ChargePointConfig config = config("ws://localhost:8080", List.of(1), 5000, 60);

        assertEquals("ws://localhost:8080/CP_1", config.webSocketUrl());
        assertFalse(config.usesSecureWebSocket());
        assertFalse(config.hasCredentials());
    }

    @Test
    void toleratesATrailingSlashAndDetectsWss() {
        ChargePointConfig config = config("wss://csms.example.com/OCPP16/", List.of(1), 5000, 60);

        assertEquals("wss://csms.example.com/OCPP16/CP_1", config.webSocketUrl());
        assertTrue(config.usesSecureWebSocket());
    }

    @Test
    void supportsMultipleConnectors() {
        ChargePointConfig config = config("ws://localhost:8080", List.of(1, 2, 3), 22000, 30);

        assertEquals(List.of(1, 2, 3), config.connectorIds());
        assertEquals(22000, config.chargingPower());
    }

    @Test
    void supportsBasicAuthWhenBothCredentialsAreGiven() {
        ChargePointConfig config = new ChargePointConfig(
                "CP_1", "ws://localhost:8080", "user", "secret", 5000, 60, List.of(1));

        assertTrue(config.hasCredentials());
        assertEquals("user", config.username());
        assertEquals("secret", config.password());
    }

    @Test
    void blanksOutEmptyCredentials() {
        ChargePointConfig config = new ChargePointConfig(
                "CP_1", "ws://localhost:8080", "  ", "", 5000, 60, List.of(1));

        assertFalse(config.hasCredentials());
        assertNull(config.username());
        assertNull(config.password());
    }

    @Test
    void rejectsAMissingChargePointId() {
        assertThrows(InvalidChargePointConfigException.class, () -> new ChargePointConfig(
                null, "ws://localhost:8080", null, null, 5000, 60, List.of(1)));
    }

    @Test
    void rejectsAChargePointIdThatWouldCorruptTheSessionUrl() {
        // a slash or a space would silently change which resource is dialled
        assertThrows(InvalidChargePointConfigException.class, () -> new ChargePointConfig(
                "CP/1", "ws://localhost:8080", null, null, 5000, 60, List.of(1)));
        assertThrows(InvalidChargePointConfigException.class, () -> new ChargePointConfig(
                "CP 1", "ws://localhost:8080", null, null, 5000, 60, List.of(1)));
    }

    @Test
    void rejectsAnUnsupportedCentralSystemScheme() {
        assertThrows(InvalidChargePointConfigException.class, () -> new ChargePointConfig(
                "CP_1", "http://localhost:8080", null, null, 5000, 60, List.of(1)));
        assertThrows(InvalidChargePointConfigException.class, () -> new ChargePointConfig(
                "CP_1", null, null, null, 5000, 60, List.of(1)));
    }

    @Test
    void rejectsHalfConfiguredCredentials() {
        assertThrows(InvalidChargePointConfigException.class, () -> new ChargePointConfig(
                "CP_1", "ws://localhost:8080", "user", null, 5000, 60, List.of(1)));
        assertThrows(InvalidChargePointConfigException.class, () -> new ChargePointConfig(
                "CP_1", "ws://localhost:8080", null, "secret", 5000, 60, List.of(1)));
    }

    @Test
    void rejectsNonPositivePowerOrMeteringInterval() {
        assertThrows(InvalidChargePointConfigException.class,
                () -> config("ws://localhost:8080", List.of(1), 0, 60));
        assertThrows(InvalidChargePointConfigException.class,
                () -> config("ws://localhost:8080", List.of(1), 5000, 0));
    }

    @Test
    void rejectsConnectorsThatAreMissingDuplicateOrNotPositive() {
        assertThrows(InvalidChargePointConfigException.class,
                () -> config("ws://localhost:8080", List.of(), 5000, 60));
        assertThrows(InvalidChargePointConfigException.class,
                () -> config("ws://localhost:8080", List.of(0), 5000, 60));
        assertThrows(InvalidChargePointConfigException.class,
                () -> config("ws://localhost:8080", List.of(1, 1), 5000, 60));
    }

    private ChargePointConfig config(String centralSystemUrl, List<Integer> connectorIds, int power, int frequency) {
        return new ChargePointConfig("CP_1", centralSystemUrl, null, null, power, frequency, connectorIds);
    }
}
