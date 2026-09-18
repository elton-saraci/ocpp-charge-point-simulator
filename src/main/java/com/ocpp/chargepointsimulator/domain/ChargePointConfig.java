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
 * @param phaseVoltage         nominal voltage of one phase in Volt, converts Ampere limits to Watt
 * @param numberPhases         phases a connector of this charge point can use, 1 to 3
 * @param meterValuesFrequency seconds between two MeterValues messages
 * @param connectorIds         connector ids exposed by this charge point, all greater than zero
 */
public record ChargePointConfig(
        String chargePointId,
        String centralSystemUrl,
        String username,
        String password,
        int chargingPower,
        int phaseVoltage,
        int numberPhases,
        int meterValuesFrequency,
        List<Integer> connectorIds) {

    /** Default phase voltage in Volt, the European low voltage grid. */
    public static final int DEFAULT_PHASE_VOLTAGE = 230;
    /** Default number of phases, which is also the OCPP default of a charging schedule period. */
    public static final int DEFAULT_PHASES = 3;
    private static final int MIN_PHASE_VOLTAGE = 100;
    private static final int MAX_PHASE_VOLTAGE = 1_000;

    private static final String WEB_SOCKET_SCHEME = "ws://";
    private static final String SECURE_WEB_SOCKET_SCHEME = "wss://";

    /**
     * Builds a configuration with the default electrical properties, {@value #DEFAULT_PHASE_VOLTAGE} V
     * per phase and {@value #DEFAULT_PHASES} phases.
     */
    public ChargePointConfig(String chargePointId,
                            String centralSystemUrl,
                            String username,
                            String password,
                            int chargingPower,
                            int meterValuesFrequency,
                            List<Integer> connectorIds) {
        this(chargePointId, centralSystemUrl, username, password, chargingPower,
                DEFAULT_PHASE_VOLTAGE, DEFAULT_PHASES, meterValuesFrequency, connectorIds);
    }

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
        if (phaseVoltage < MIN_PHASE_VOLTAGE || phaseVoltage > MAX_PHASE_VOLTAGE) {
            throw new InvalidChargePointConfigException(
                    "phaseVoltage must be between " + MIN_PHASE_VOLTAGE + " and " + MAX_PHASE_VOLTAGE
                            + " Volt, but was " + phaseVoltage + ".");
        }
        if (numberPhases < 1 || numberPhases > DEFAULT_PHASES) {
            throw new InvalidChargePointConfigException(
                    "numberPhases must be between 1 and " + DEFAULT_PHASES + ", but was " + numberPhases + ".");
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
