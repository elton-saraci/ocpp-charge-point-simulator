package com.ocpp.chargepointsimulator.controllers.dto;

import com.ocpp.chargepointsimulator.configurations.SimulatorDefaults;
import com.ocpp.chargepointsimulator.domain.ChargePointConfig;

import java.util.List;

/**
 * Body of {@code POST /api/charge-points}.
 *
 * <p>Every field is optional: whatever is left out falls back to the configured defaults
 * ({@code simulator.defaults} in {@code application.yml} / environment variables).
 *
 * @param chargePointId        identity of the charge point, appended to the central system URL
 * @param centralSystemUrl     central system base URL, {@code ws://} or {@code wss://}
 * @param username             HTTP Basic username for the OCPP handshake, omit to connect without auth
 * @param password             HTTP Basic password for the OCPP handshake
 * @param chargingPower        charging power in Watt, shared by all connectors of the charge point
 * @param meterValuesFrequency seconds between two MeterValues messages
 * @param connectorIds         connector ids this charge point exposes, e.g. {@code [1,2]}
 * @param connect              whether to open the WebSocket connection immediately, default {@code true}
 */
public record ChargePointRequest(
        String chargePointId,
        String centralSystemUrl,
        String username,
        String password,
        Integer chargingPower,
        Integer meterValuesFrequency,
        List<Integer> connectorIds,
        Boolean connect) {

    /** @throws com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException when the result is invalid */
    public ChargePointConfig toConfig(SimulatorDefaults defaults) {
        return new ChargePointConfig(
                chargePointId != null ? chargePointId : defaults.chargePointId(),
                centralSystemUrl != null ? centralSystemUrl : defaults.centralSystemUrl(),
                username != null ? username : defaults.username(),
                password != null ? password : defaults.password(),
                chargingPower != null ? chargingPower : defaults.chargingPower(),
                meterValuesFrequency != null ? meterValuesFrequency : defaults.meterValuesFrequency(),
                connectorIds != null && !connectorIds.isEmpty() ? connectorIds : defaults.connectorIds());
    }

    public boolean shouldConnect() {
        return connect == null || connect;
    }
}
