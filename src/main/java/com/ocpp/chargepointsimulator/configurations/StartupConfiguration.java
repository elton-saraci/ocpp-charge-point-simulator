package com.ocpp.chargepointsimulator.configurations;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.services.ChargePointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Registers the charge point described by the environment on startup.
 *
 * <p>This is the backwards compatible fallback for single charge point deployments. When
 * {@code CHARGE_POINT_ID} is not set the simulator starts with an empty registry and charge points
 * are created over HTTP with {@code POST /api/charge-points}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StartupConfiguration implements ApplicationRunner {

    private final SimulatorDefaults defaults;
    private final ChargePointService chargePointService;

    @Override
    public void run(ApplicationArguments args) {
        if (!defaults.isChargePointConfigured()) {
            log.info("No default charge point configured (CHARGE_POINT_ID is not set). "
                    + "Create charge points with POST /api/charge-points.");
            return;
        }
        try {
            ChargePointConfig config = defaults.toChargePointConfig();
            chargePointService.register(config, true);
            log.info("Default charge point '{}' registered, connecting to {}.",
                    config.chargePointId(), config.webSocketUrl());
        } catch (RuntimeException e) {
            // Logged with its stack trace on purpose: the HTTP API stays available so the charge
            // point can be registered again once the configuration or the central system is fixed.
            log.error("Could not register the charge point from the environment. The simulator keeps "
                    + "running, register it with POST /api/charge-points instead.", e);
        }
    }
}
