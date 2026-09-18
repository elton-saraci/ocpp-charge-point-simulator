package com.ocpp.chargepointsimulator.configurations;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Defaults for charge point configuration, bound from the {@code simulator.defaults} section of
 * {@code application.yml} (every value can still be overridden with its environment variable).
 *
 * <p>They serve two purposes: they fill the gaps with charge points created over HTTP, and they
 * describe the optional charge point that is registered automatically on startup when
 * {@code chargePointId} is set.
 */
@ConfigurationProperties(prefix = "simulator.defaults")
public record SimulatorDefaults(
        String chargePointId,
        String centralSystemUrl,
        String username,
        String password,
        int chargingPower,
        Integer phaseVoltage,
        Integer numberPhases,
        int meterValuesFrequency,
        List<Integer> connectorIds) {

    /** @return {@code true} when a charge point id is configured, i.e. when one should be started. */
    public boolean isChargePointConfigured() {
        return chargePointId != null && !chargePointId.isBlank();
    }

    /** @throws com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException when the values are invalid. */
    public ChargePointConfig toChargePointConfig() {
        return new ChargePointConfig(chargePointId, centralSystemUrl, username, password,
                chargingPower,
                phaseVoltage == null ? ChargePointConfig.DEFAULT_PHASE_VOLTAGE : phaseVoltage,
                numberPhases == null ? ChargePointConfig.DEFAULT_PHASES : numberPhases,
                meterValuesFrequency, connectorIds);
    }
}
