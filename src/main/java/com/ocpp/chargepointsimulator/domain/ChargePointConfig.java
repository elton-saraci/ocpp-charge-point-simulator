package com.ocpp.chargepointsimulator.domain;

import com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable definition of a simulated charge point: where it connects to, how it authenticates and
 * which connectors it exposes.
 *
 * <p>The charging power is defined on charge point level on purpose: every connector of a charge
 * point charges with the same power. The meter value step is not configured anymore, it is derived
 * from the power and the metering frequency, see {@code EnergyMeterCalculator}.
 *
 * @param chargePointId        identity of the charge point, appended to the central system URL
 * @param centralSystemUrl     base WebSocket URL of the central system, {@code ws://} or {@code wss://}
 * @param username             HTTP Basic username used for the OCPP handshake, {@code null} to disable auth
 * @param password             HTTP Basic password used for the OCPP handshake, {@code null} to disable auth
 * @param chargingPower        charging power in Watt, used to derive the meter value step
 * @param meterValuesFrequency seconds between two MeterValues messages
 * @param connectorIds         connector ids exposed by this charge point, all greater than zero
 */
public record ChargePointConfig(
        String chargePointId,
        String centralSystemUrl,
        String username,
        String password,
        int chargingPower,
        int meterValuesFrequency,
        List<Integer> connectorIds) {

    private static final String WEB_SOCKET_SCHEME = "ws://";
    private static final String SECURE_WEB_SOCKET_SCHEME = "wss://";

    public ChargePointConfig {
        chargePointId = trimToNull(chargePointId);
        centralSystemUrl = trimToNull(centralSystemUrl);
        username = trimToNull(username);
        password = password == null || password.isEmpty() ? null : password;

        if (chargePointId == null) {
            throw new InvalidChargePointConfigException("chargePointId is required.");
        }
        if (chargePointId.contains("/") || chargePointId.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidChargePointConfigException(
                    "chargePointId must not contain slashes or whitespace, but was '" + chargePointId + "'.");
        }
        if (centralSystemUrl == null) {
            throw new InvalidChargePointConfigException("centralSystemUrl is required.");
        }
        if (!centralSystemUrl.startsWith(WEB_SOCKET_SCHEME) && !centralSystemUrl.startsWith(SECURE_WEB_SOCKET_SCHEME)) {
            throw new InvalidChargePointConfigException(
                    "centralSystemUrl must start with ws:// or wss://, but was '" + centralSystemUrl + "'.");
        }
        if ((username == null) != (password == null)) {
            throw new InvalidChargePointConfigException(
                    "username and password must either both be set (HTTP Basic auth) or both be empty.");
        }
        if (chargingPower <= 0) {
            throw new InvalidChargePointConfigException(
                    "chargingPower must be greater than 0 Watt, but was " + chargingPower + ".");
        }
        if (meterValuesFrequency <= 0) {
            throw new InvalidChargePointConfigException(
                    "meterValuesFrequency must be greater than 0 seconds, but was " + meterValuesFrequency + ".");
        }
        if (connectorIds == null || connectorIds.isEmpty()) {
            throw new InvalidChargePointConfigException("At least one connector id is required.");
        }
        connectorIds = List.copyOf(connectorIds);
        for (Integer connectorId : connectorIds) {
            if (connectorId == null || connectorId < 1) {
                throw new InvalidChargePointConfigException(
                        "Connector ids must be greater than 0, but found '" + connectorId + "'.");
            }
        }
        Set<Integer> distinctConnectorIds = new LinkedHashSet<>(connectorIds);
        if (distinctConnectorIds.size() != connectorIds.size()) {
            throw new InvalidChargePointConfigException(
                    "Connector ids must be unique, but were " + connectorIds + ".");
        }
    }

    /** @return the URL the WebSocket client dials, {@code <centralSystemUrl>/<chargePointId>}. */
    public String webSocketUrl() {
        return centralSystemUrl.endsWith("/")
                ? centralSystemUrl + chargePointId
                : centralSystemUrl + "/" + chargePointId;
    }

    public boolean usesSecureWebSocket() {
        return centralSystemUrl.startsWith(SECURE_WEB_SOCKET_SCHEME);
    }

    public boolean hasCredentials() {
        return username != null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
