package com.ocpp.chargepointsimulator.utilities;

import com.ocpp.chargepointsimulator.domain.StoredChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfileKindType;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ChargingSchedule;
import eu.chargetime.ocpp.model.core.ChargingSchedulePeriod;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the schedule a charge point answers {@code GetCompositeSchedule} with: one period per
 * moment the effective limit changes, converted into the unit the central system asked for.
 */
class CompositeScheduleBuilderTest {

    private static final Instant RECEIVED = Instant.parse("2026-09-18T10:00:00Z");
    private static final Instant TX_START = RECEIVED.plusSeconds(30);
    private static final SmartChargingCalculator.Electrical THREE_PHASE_230 =
            new SmartChargingCalculator.Electrical(230, 3);
    private static final int NOMINAL_11KW = 11_040;
    private static final int TRANSACTION_ID = 4711;
    private static final Set<Integer> CONNECTOR_IDS = Set.of(0, 1, 2);

    @Test
    void mergesTheProfilesOfAConnectorIntoOneSchedule() {
        StoredChargingProfile txDefault = profile(9, 1, ChargingProfilePurposeType.TxDefaultProfile,
                ChargingRateUnitType.A, period(0, 16));
        StoredChargingProfile txProfile = txProfile(period(0, 6), period(300, 0));

        ChargingSchedule composite = composite(List.of(txDefault, txProfile), 1, 600, ChargingRateUnitType.A);

        ChargingSchedulePeriod[] periods = composite.getChargingSchedulePeriod();
        assertEquals(3, periods.length);
        assertEquals(0, periods[0].getStartPeriod().intValue());
        assertEquals(6d, periods[0].getLimit(), 0.01);
        assertEquals(300, periods[1].getStartPeriod().intValue());
        assertEquals(0d, periods[1].getLimit(), 0.01);
        // after the TxProfile is over its default applies again, so the limit returns to 16 A
        assertEquals(600, periods[2].getStartPeriod().intValue());
        assertEquals(16d, periods[2].getLimit(), 0.01);
        assertEquals(ChargingRateUnitType.A, composite.getChargingRateUnit());
        assertEquals(600, composite.getDuration().intValue());
    }

    @Test
    void reportsOnePeriodOnlyWhenTheLimitChanges() {
        StoredChargingProfile flat = profile(15, 1, ChargingProfilePurposeType.TxDefaultProfile,
                ChargingRateUnitType.W, period(0, 9_000));

        ChargingSchedule composite = composite(List.of(flat), 1, 300, ChargingRateUnitType.W);

        assertEquals(1, composite.getChargingSchedulePeriod().length);
        assertEquals(9_000d, composite.getChargingSchedulePeriod()[0].getLimit(), 0.01);
    }

    @Test
    void fallsBackToTheNominalPowerWhenNoProfileApplies() {
        ChargingSchedule composite = composite(List.of(), 1, 300, ChargingRateUnitType.W);

        assertEquals(1, composite.getChargingSchedulePeriod().length);
        assertEquals(NOMINAL_11KW, composite.getChargingSchedulePeriod()[0].getLimit(), 0.01);
    }

    @Test
    void connectorZeroReportsTheChargePointLimit() {
        StoredChargingProfile stationCap = profile(11, 0, ChargingProfilePurposeType.ChargePointMaxProfile,
                ChargingRateUnitType.W, period(0, 5_000));
        StoredChargingProfile txDefault = profile(12, 1, ChargingProfilePurposeType.TxDefaultProfile,
                ChargingRateUnitType.W, period(0, 9_000));

        ChargingSchedule composite = composite(List.of(stationCap, txDefault), 0, 300, ChargingRateUnitType.W);

        assertEquals(5_000d, composite.getChargingSchedulePeriod()[0].getLimit(), 0.01);
    }

    @Test
    void connectorNeverExceedsTheChargePointLimit() {
        StoredChargingProfile stationCap = profile(13, 0, ChargingProfilePurposeType.ChargePointMaxProfile,
                ChargingRateUnitType.W, period(0, 5_000));
        StoredChargingProfile txDefault = profile(14, 1, ChargingProfilePurposeType.TxDefaultProfile,
                ChargingRateUnitType.W, period(0, 9_000));

        ChargingSchedule composite = composite(List.of(stationCap, txDefault), 1, 300, ChargingRateUnitType.W);

        assertEquals(5_000d, composite.getChargingSchedulePeriod()[0].getLimit(), 0.01);
    }

    @Test
    void refusesADurationOfZero() {
        assertThrows(IllegalArgumentException.class, () -> new CompositeScheduleBuilder.Request(
                1, RECEIVED, TX_START, 0, ChargingRateUnitType.W, THREE_PHASE_230, NOMINAL_11KW));
    }

    private static ChargingSchedule composite(List<StoredChargingProfile> profiles,
                                             int connectorId,
                                             int durationSeconds,
                                             ChargingRateUnitType unit) {
        return CompositeScheduleBuilder.build(profiles, new CompositeScheduleBuilder.Request(
                connectorId, RECEIVED, TX_START, durationSeconds, unit, THREE_PHASE_230, NOMINAL_11KW));
    }

    private static ChargingSchedulePeriod period(int startPeriod, double limit) {
        return new ChargingSchedulePeriod(startPeriod, limit);
    }

    private static StoredChargingProfile txProfile(ChargingSchedulePeriod... periods) {
        ChargingSchedule schedule = new ChargingSchedule(ChargingRateUnitType.A, periods);
        schedule.setDuration(600);
        ChargingProfile profile = new ChargingProfile();
        profile.setChargingProfileId(10);
        profile.setTransactionId(TRANSACTION_ID);
        profile.setChargingProfilePurpose(ChargingProfilePurposeType.TxProfile);
        profile.setChargingProfileKind(ChargingProfileKindType.Absolute);
        profile.setChargingSchedule(schedule);
        return StoredChargingProfile.from(new SetChargingProfileRequest(1, profile),
                CONNECTOR_IDS, Map.of(1, TRANSACTION_ID), RECEIVED);
    }

    private static StoredChargingProfile profile(int id,
                                                int connectorId,
                                                ChargingProfilePurposeType purpose,
                                                ChargingRateUnitType unit,
                                                 ChargingSchedulePeriod... periods) {
        ChargingSchedule schedule = new ChargingSchedule(unit, periods);
        schedule.setMinChargingRate((double) 0);
        ChargingProfile profile = new ChargingProfile();
        profile.setChargingProfileId(id);
        profile.setChargingProfilePurpose(purpose);
        profile.setChargingProfileKind(ChargingProfileKindType.Absolute);
        profile.setChargingSchedule(schedule);
        return StoredChargingProfile.from(new SetChargingProfileRequest(connectorId, profile),
                CONNECTOR_IDS, Map.of(1, TRANSACTION_ID), RECEIVED);
    }
}
