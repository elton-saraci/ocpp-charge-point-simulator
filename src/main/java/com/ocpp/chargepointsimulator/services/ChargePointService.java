package com.ocpp.chargepointsimulator.services;

import com.ocpp.chargepointsimulator.domain.AuthorizationOutcome;
import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.domain.StoredChargingProfile;
import com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException;
import com.ocpp.chargepointsimulator.ocpp.ChargePointConnectionManager;
import com.ocpp.chargepointsimulator.ocpp.ChargePointSessionFactory;
import com.ocpp.chargepointsimulator.registry.ChargePointRegistry;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.Reason;
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

    /**
     * Rebuilds a charge point with a new definition. The id cannot change and the connection intent is
     * kept: a station that was connected is connected again with the new settings, a station that was
     * down stays down. Energy registers of connectors that exist in both definitions are carried over,
     * because the simulated meter keeps counting across a reconfiguration.
     *
     * @throws com.ocpp.chargepointsimulator.exceptions.ChargePointNotFoundException when the id is unknown
     * @throws com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException when the body tries
     *         to rename the charge point
     */
    public ChargePointSession update(String chargePointId, ChargePointConfig config, boolean connect) {
        ChargePointSession existing = registry.get(chargePointId);
        if (!chargePointId.equals(config.chargePointId())) {
            throw new InvalidChargePointConfigException("The chargePointId of '" + chargePointId + "' cannot be changed "
                    + "to '" + config.chargePointId() + "'. Remove the charge point and create it again instead.");
        }
        ChargePointSession replacement = sessionFactory.create(config);
        endRunningTransactions(existing);
        carryOverMeterValues(existing, replacement);
        carryOverChargingProfiles(existing, replacement);
        registry.replace(replacement);
        connectionManager.disconnectQuietly(existing);
        if (connect) {
            connectionManager.connect(replacement);
        }
        log.info("Charge point '{}' redefined: connectors {}, charging power {} W, metering every {}s, {}.",
                chargePointId, config.connectorIds(), config.chargingPower(), config.meterValuesFrequency(),
                connect ? "reconnecting" : "staying disconnected");
        return replacement;
    }

    /**
     * Bows out of running transactions before the session is torn down, so the central system does not
     * end up with a transaction that no charge point will ever close. Best effort: a failure is logged
     * and the redefinition continues, because the alternative is a half updated charge point.
     */
    private void endRunningTransactions(ChargePointSession session) {
        for (ConnectorState connector : session.getConnectors()) {
            if (!connector.hasTransaction()) {
                continue;
            }
            try {
                transactionService.stopTransaction(session, connector, ChargePointStatus.Available, Reason.Other);
            } catch (RuntimeException e) {
                log.error("Could not close transaction {} of charge point '{}' before redefining it.",
                        connector.getTransactionId(), session.getChargePointId(), e);
            }
        }
    }

    private void carryOverMeterValues(ChargePointSession existing, ChargePointSession replacement) {
        for (ConnectorState connector : existing.getConnectors()) {
            replacement.findConnector(connector.getConnectorId())
                    .ifPresent(newConnector -> newConnector.restoreMeterValueWh(connector.getCurrentMeterValueWh()));
        }
    }

    /**
     * Charging profiles live in the memory of the charge point, a central system does not resend them
     * after a redefinition, so they are moved to the new session instead of being lost. Profiles for
     * connectors that no longer exist are dropped.
     */
    private void carryOverChargingProfiles(ChargePointSession existing, ChargePointSession replacement) {
        List<StoredChargingProfile> keeping = existing.getChargingProfiles().all().stream()
                .filter(profile -> profile.connectorId() == 0
                        || replacement.findConnector(profile.connectorId()).isPresent())
                .toList();
        if (!keeping.isEmpty()) {
            replacement.getChargingProfiles().replaceAll(keeping);
            log.info("Charge point '{}' kept {} charging profile(s) across the redefinition.",
                    replacement.getChargePointId(), keeping.size());
        }
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
