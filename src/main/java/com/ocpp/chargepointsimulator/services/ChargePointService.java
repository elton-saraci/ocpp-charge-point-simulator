package com.ocpp.chargepointsimulator.services;

import com.ocpp.chargepointsimulator.domain.AuthorizationOutcome;
import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.ocpp.ChargePointConnectionManager;
import com.ocpp.chargepointsimulator.ocpp.ChargePointSessionFactory;
import com.ocpp.chargepointsimulator.registry.ChargePointRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lifecycle of the simulated charge points: registering, listing, connecting, disconnecting and
 * removing them, plus the connector operations exposed over HTTP.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChargePointService {

    private final ChargePointRegistry registry;
    private final ChargePointSessionFactory sessionFactory;
    private final ChargePointConnectionManager connectionManager;
    private final TransactionService transactionService;

    /**
     * Creates and registers a charge point, optionally opening its WebSocket connection right away.
     *
     * @throws com.ocpp.chargepointsimulator.exceptions.ChargePointAlreadyExistsException when the id is taken
     */
    public ChargePointSession register(ChargePointConfig config, boolean connect) {
        ChargePointSession session = sessionFactory.create(config);
        registry.add(session);
        log.info("Charge point '{}' registered with connectors {}, charging power {} W, metering every {}s.",
                config.chargePointId(), config.connectorIds(), config.chargingPower(), config.meterValuesFrequency());
        if (connect) {
            connectionManager.connect(session);
        }
        return session;
    }

    public ChargePointSession get(String chargePointId) {
        return registry.get(chargePointId);
    }

    public List<ChargePointSession> list() {
        return List.copyOf(registry.all());
    }

    public void connect(String chargePointId) {
        connectionManager.connect(registry.get(chargePointId));
    }

    /** @throws com.ocpp.chargepointsimulator.exceptions.ConnectorNotFoundException when the connector is not exposed. */
    public ConnectorState getConnector(String chargePointId, int connectorId) {
        return registry.get(chargePointId).getConnector(connectorId);
    }

    public void disconnect(String chargePointId) {
        connectionManager.disconnect(registry.get(chargePointId));
    }

    /** Disconnects and forgets a charge point. */
    public void remove(String chargePointId) {
        ChargePointSession session = registry.remove(chargePointId);
        connectionManager.disconnectQuietly(session);
        log.info("Charge point '{}' removed.", chargePointId);
    }

    public ConnectorState plugIn(String chargePointId, Integer connectorId) {
        return transactionService.plugIn(registry.get(chargePointId), connectorId);
    }

    public ConnectorState plugOut(String chargePointId, Integer connectorId) {
        return transactionService.plugOut(registry.get(chargePointId), connectorId);
    }

    public AuthorizationOutcome authorize(String chargePointId, Integer connectorId, String idTag) {
        return transactionService.authorize(registry.get(chargePointId), connectorId, idTag);
    }
}
