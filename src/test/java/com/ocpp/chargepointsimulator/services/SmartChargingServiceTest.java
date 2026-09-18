package com.ocpp.chargepointsimulator.services;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.domain.StoredChargingProfile;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.ChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfileKindType;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ChargingSchedule;
import eu.chargetime.ocpp.model.core.ChargingSchedulePeriod;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks what the charging profiles do to the connectors: the power each one may draw, what the REST
 * layer reads back, and the suspension of a connector that a profile holds at zero.
 */
class SmartChargingServiceTest {

    /** 16 A over three phases at 230 V, the nominal power of the simulated charge point. */
    private static final int NOMINAL_W = 11_040;
    private static final ChargePointConfig CONFIG = new ChargePointConfig(
            "CP_1", "ws://localhost:8080", null, null, NOMINAL_W, 230, 3, 60, List.of(1, 2));

    private final SmartChargingService service = new SmartChargingService();
    private ChargePointSession session;

    @BeforeEach
    void setUp() {
        session = new ChargePointSession(CONFIG);
    }

    @Test
    void withoutProfilesTheNominalPowerApplies() {
        assertTrue(service.applyProfiles(session).isEmpty());
        assertNull(session.getConnector(1).getChargingLimitW());
        assertFalse(session.getConnector(1).isSuspended());
    }

    @Test
    void aSixAmpereProfileLimitsAChargingConnectorToThreePhasePower() {
        charge(1);
        storeProfile(1, 1, ChargingProfilePurposeType.TxDefaultProfile, 6, ChargingRateUnitType.A);

        Map<Integer, Integer> allowed = service.applyProfiles(session);

        assertEquals(4_140, allowed.get(1));
        assertEquals(4_140, session.getConnector(1).getChargingLimitW());
        assertFalse(session.getConnector(1).isSuspended());
    }

    @Test
    void aZeroLimitSuspendsAChargingConnector() {
        charge(1);
        storeProfile(2, 1, ChargingProfilePurposeType.TxDefaultProfile, 0, ChargingRateUnitType.A);

        assertEquals(0, service.applyProfiles(session).get(1));
        assertTrue(session.getConnector(1).isSuspended());
    }

    @Test
    void aChargePointWideLimitIsSharedBetweenChargingConnectors() {
        charge(1);
        charge(2);
        storeProfile(3, 0, ChargingProfilePurposeType.ChargePointMaxProfile, 6_000, ChargingRateUnitType.W);

        Map<Integer, Integer> allowed = service.applyProfiles(session);

        assertEquals(3_000, allowed.get(1));
        assertEquals(3_000, allowed.get(2));
    }

    @Test
    void aChargePointWideLimitLeavesAnIdleConnectorAlone() {
        charge(1);
        storeProfile(4, 0, ChargingProfilePurposeType.ChargePointMaxProfile, 6_000, ChargingRateUnitType.W);

        Map<Integer, Integer> allowed = service.applyProfiles(session);

        // connector 1 charges within the cap, connector 2 has no session to limit
        assertEquals(6_000, allowed.get(1));
        assertEquals(NOMINAL_W, allowed.get(2));
        assertFalse(session.getConnector(2).isSuspended());
    }

    @Test
    void theTranscriptProfileOfTheConnectorWinsOverItsDefault() {
        charge(1);
        storeProfile(5, 1, ChargingProfilePurposeType.TxDefaultProfile, 16, ChargingRateUnitType.A);
        ConnectorState connector = session.getConnector(1);
        storeTransactionProfile(connector.getTransactionId());

        assertEquals(4_140, service.applyProfiles(session).get(1));
    }

    private void charge(int connectorId) {
        session.getConnector(connectorId).startTransaction(4_700 + connectorId, "tag1", ChargePointStatus.Charging);
    }

    private void storeProfile(int profileId,
                              int connectorId,
                              ChargingProfilePurposeType purpose,
                              double limit,
                              ChargingRateUnitType unit) {
        store(profileId, connectorId, purpose, limit, unit, null);
    }

    private void storeTransactionProfile(Integer transactionId) {
        store(6, 1, ChargingProfilePurposeType.TxProfile, 6, ChargingRateUnitType.A, transactionId);
    }

    private void store(int profileId,
                       int connectorId,
                       ChargingProfilePurposeType purpose,
                       double limit,
                       ChargingRateUnitType unit,
                       Integer transactionId) {
        ChargingSchedule schedule = new ChargingSchedule(unit, new ChargingSchedulePeriod[] {
                new ChargingSchedulePeriod(0, limit) });
        ChargingProfile profile = new ChargingProfile();
        profile.setChargingProfileId(profileId);
        profile.setTransactionId(transactionId);
        profile.setChargingProfilePurpose(purpose);
        profile.setChargingProfileKind(ChargingProfileKindType.Absolute);
        profile.setChargingSchedule(schedule);
        session.getChargingProfiles().put(StoredChargingProfile.from(
                new SetChargingProfileRequest(connectorId, profile),
                Set.copyOf(CONFIG.connectorIds()),
                session.runningTransactions(),
                Instant.now()));
    }
}
