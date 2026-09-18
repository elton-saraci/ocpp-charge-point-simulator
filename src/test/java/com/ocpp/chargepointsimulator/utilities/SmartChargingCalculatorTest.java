package com.ocpp.chargepointsimulator.utilities;

import com.ocpp.chargepointsimulator.domain.StoredChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfileKindType;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ChargingSchedule;
import eu.chargetime.ocpp.model.core.ChargingSchedulePeriod;
import eu.chargetime.ocpp.model.core.RecurrencyKindType;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the physics of smart charging: the Ampere to Watt conversion with the phases of the
 * station, the period a moment falls into, the precedence between purposes and stack levels, and
 * the sharing of a charge point wide cap between the charging connectors.
 */
class SmartChargingCalculatorTest {

    private static final Instant RECEIVED = Instant.parse("2026-09-18T10:00:00Z");
    private static final Instant TX_START = RECEIVED.plusSeconds(30);
    private static final SmartChargingCalculator.Electrical THREE_PHASE_230 = new SmartChargingCalculator.Electrical(230, 3);
    private static final SmartChargingCalculator.Electrical SINGLE_PHASE_230 = new SmartChargingCalculator.Electrical(230, 1);
    private static final int TRANSACTION_ID = 4711;

    @Test
    void convertsAmpsIntoWattsWithThePhasesOfTheStation() {
        assertEquals(11_040d, SmartChargingCalculator.ampsToWatts(16, 3, 230), 0.01);
        assertEquals(3_680d, SmartChargingCalculator.ampsToWatts(16, 1, 230), 0.01);
        assertEquals(16d, SmartChargingCalculator.wattsToAmps(11_040, 3, 230), 0.01);
    }

    @Test
    void convertsAnAmpereLimitOfAProfileIntoWatts() {
        StoredChargingProfile txDefault = profile(1,
                null, 0, period(0, 6));

        assertEquals(4_140d, wattsAt(List.of(txDefault), RECEIVED.plusSeconds(1)).orElseThrow(), 0.01);
    }

    @Test
    void usesThePhasesThePeriodAsksForWhenTheStationHasMore() {
        ChargingSchedulePeriod singlePhase = period(0, 16);
        singlePhase.setNumberPhases(1);
        StoredChargingProfile txDefault = profile(1,
                null, 0, singlePhase);

        // 16 A on one phase of a three phase station is 3.68 kW, not 11.04 kW.
        assertEquals(3_680d, wattsAt(List.of(txDefault), RECEIVED.plusSeconds(1)).orElseThrow(), 0.01);
    }

    @Test
    void neverUsesMorePhasesThanTheStationHas() {
        // A single phase station cannot turn a three phase limit into three phase power.
        StoredChargingProfile txDefault = profile(1,
                null, 0, period(0, 16));

        assertEquals(3_680d, wattsAt(List.of(txDefault), RECEIVED.plusSeconds(1), SINGLE_PHASE_230).orElseThrow(), 0.01);
    }

    @Test
    void picksThePeriodTheMomentFallsInto() {
        StoredChargingProfile txDefault = profile(1,
                600, 0,
                period(0, 16), period(60, 6), period(120, 0));

        assertEquals(11_040d, wattsAt(List.of(txDefault), RECEIVED).orElseThrow(), 0.01);
        assertEquals(4_140d, wattsAt(List.of(txDefault), RECEIVED.plusSeconds(90)).orElseThrow(), 0.01);
        assertEquals(0d, wattsAt(List.of(txDefault), RECEIVED.plusSeconds(150)).orElseThrow(), 0.01);
    }

    @Test
    void stopsApplyingWhenTheDurationIsOver() {
        StoredChargingProfile txDefault = profile(1,
                60, 0, period(0, 16));

        assertTrue(wattsAt(List.of(txDefault), RECEIVED.plusSeconds(59)).isPresent());
        assertTrue(wattsAt(List.of(txDefault), RECEIVED.plusSeconds(61)).isEmpty());
    }

    @Test
    void ignoresAnAbsoluteScheduleThatStartsInTheFuture() {
        StoredChargingProfile txDefault = profile(
                RECEIVED.plusSeconds(3600),
                period(0, 16));

        assertTrue(wattsAt(List.of(txDefault), RECEIVED).isEmpty());
        assertEquals(11_040d, wattsAt(List.of(txDefault), RECEIVED.plusSeconds(3601)).orElseThrow(), 0.01);
    }

    @Test
    void anchorsARelativeScheduleAtTheStartOfTheTransaction() {
        StoredChargingProfile txProfile = txProfile(2, ChargingProfileKindType.Relative, 900,
                period(0, 16), period(300, 6));

        assertEquals(11_040d, wattsAt(List.of(txProfile), TX_START.plusSeconds(100)).orElseThrow(), 0.01);
        assertEquals(4_140d, wattsAt(List.of(txProfile), TX_START.plusSeconds(400)).orElseThrow(), 0.01);
        // relative profiles only exist while the transaction runs
        assertTrue(SmartChargingCalculator.limitAt(List.of(txProfile), 1, RECEIVED, null,
                THREE_PHASE_230).isEmpty());
    }

    @Test
    void repeatsARecurringScheduleEveryDay() {
        // 16 A until one hour into the day, then a quarter of an hour later nothing for the rest of the day.
        StoredChargingProfile recurring = profile(3,
                ChargingProfileKindType.Recurring, null, 0,
                0, RecurrencyKindType.Daily, period(0, 16), period(3600, 6));

        assertEquals(11_040d, wattsAt(List.of(recurring), RECEIVED.plusSeconds(1800)).orElseThrow(), 0.01);
        assertEquals(4_140d, wattsAt(List.of(recurring), RECEIVED.plusSeconds(7200)).orElseThrow(), 0.01);
        assertEquals(11_040d, wattsAt(List.of(recurring), RECEIVED.plusSeconds(86_400 + 1800)).orElseThrow(), 0.01);
        assertEquals(4_140d, wattsAt(List.of(recurring), RECEIVED.plusSeconds(86_400 + 7200)).orElseThrow(), 0.01);
    }

    @Test
    void treatsALimitBelowTheMinimumChargingRateAsSuspended() {
        StoredChargingProfile txDefault = profile(4,
                null, 6, period(0, 3));

        assertEquals(0d, wattsAt(List.of(txDefault), RECEIVED).orElseThrow(), 0.01);
    }

    @Test
    void txProfileOverrulesTxDefaultProfile() {
        StoredChargingProfile txDefault = profile(5,
                null, 0, period(0, 16));
        StoredChargingProfile txProfile = txProfile(6, ChargingProfileKindType.Absolute, null, period(0, 6));

        assertEquals(4_140d, wattsAt(List.of(txDefault, txProfile), RECEIVED).orElseThrow(), 0.01);
    }

    @Test
    void higherStackLevelWinsInsideAPurpose() {
        StoredChargingProfile stack0 = profile(7,
                null, 0, period(0, 16));
        StoredChargingProfile stack1 = profile(8,
                ChargingProfileKindType.Absolute, null, 0, 1, null, period(0, 6));

        assertEquals(4_140d, wattsAt(List.of(stack0, stack1), RECEIVED).orElseThrow(), 0.01);
    }

    @Test
    void chargePointWideLimitIsSharedBetweenTheChargingConnectors() {
        Map<Integer, Double> demand = Map.of(1, 11_040d, 2, 11_040d);

        Map<Integer, Double> allocated = SmartChargingCalculator.allocate(11_040d, demand);

        assertEquals(5_520d, allocated.get(1), 0.01);
        assertEquals(5_520d, allocated.get(2), 0.01);
    }

    @Test
    void chargePointWideLimitDoesNotTouchConnectorsThatFit() {
        Map<Integer, Double> demand = Map.of(1, 4_000d, 2, 4_000d);

        assertEquals(demand, SmartChargingCalculator.allocate(11_040d, demand));
        assertEquals(demand, SmartChargingCalculator.allocate(null, demand));
    }

    private static Optional<Double> wattsAt(List<StoredChargingProfile> profiles, Instant now) {
        return wattsAt(profiles, now, THREE_PHASE_230);
    }

    private static Optional<Double> wattsAt(List<StoredChargingProfile> profiles,
                                           Instant now,
                                           SmartChargingCalculator.Electrical electrical) {
        return SmartChargingCalculator.limitAt(profiles, 1, now, TX_START, electrical)
                .map(SmartChargingCalculator.ApplicableLimit::watts);
    }

    private static ChargingSchedulePeriod period(int startPeriod, double limit) {
        return new ChargingSchedulePeriod(startPeriod, limit);
    }

    private static StoredChargingProfile txProfile(int id,
                                                   ChargingProfileKindType kind,
                                                  Integer durationSeconds,
                                                   ChargingSchedulePeriod... periods) {
        ChargingSchedule schedule = new ChargingSchedule(ChargingRateUnitType.A, periods);
        schedule.setDuration(durationSeconds);
        schedule.setMinChargingRate((double) 0);
        ChargingProfile profile = new ChargingProfile();
        profile.setChargingProfileId(id);
        profile.setTransactionId(TRANSACTION_ID);
        profile.setChargingProfilePurpose(ChargingProfilePurposeType.TxProfile);
        profile.setChargingProfileKind(kind);
        profile.setChargingSchedule(schedule);
        return StoredChargingProfile.from(new SetChargingProfileRequest(1, profile),
                java.util.Set.of(0, 1, 2), Map.of(1, TRANSACTION_ID), RECEIVED);
    }

    private static StoredChargingProfile profile(int id,
                                                 Integer durationSeconds,
                                                double minChargingRate,
                                                ChargingSchedulePeriod... periods) {
        return profile(id, ChargingProfileKindType.Absolute, durationSeconds, minChargingRate, 0, null, periods);
    }

    private static StoredChargingProfile profile(int id,
                                                 ChargingProfileKindType kind,
                                                 Integer durationSeconds,
                                                double minChargingRate,
                                                int stackLevel,
                                                RecurrencyKindType recurrencyKind,
                                                ChargingSchedulePeriod... periods) {
        return profile(id, kind, durationSeconds, minChargingRate, stackLevel,
                recurrencyKind, null, periods);
    }

    private static StoredChargingProfile profile(Instant startSchedule,
                                                 ChargingSchedulePeriod... periods) {
        return profile(1, ChargingProfileKindType.Absolute, null, 0, 0, null,
                startSchedule, periods);
    }

    private static StoredChargingProfile profile(int id,
                                                 ChargingProfileKindType kind,
                                                 Integer durationSeconds,
                                                double minChargingRate,
                                                int stackLevel,
                                                RecurrencyKindType recurrencyKind,
                                                Instant startSchedule,
                                                ChargingSchedulePeriod... periods) {
        ChargingSchedule schedule = new ChargingSchedule(ChargingRateUnitType.A, periods);
        schedule.setDuration(durationSeconds);
        schedule.setMinChargingRate(minChargingRate);
        schedule.setStartSchedule(startSchedule == null ? null : java.time.ZonedDateTime.ofInstant(
                startSchedule, java.time.ZoneOffset.UTC));
        ChargingProfile profile = new ChargingProfile();
        profile.setChargingProfileId(id);
        profile.setChargingProfilePurpose(ChargingProfilePurposeType.TxDefaultProfile);
        profile.setChargingProfileKind(kind);
        profile.setStackLevel(stackLevel);
        profile.setRecurrencyKind(recurrencyKind);
        profile.setTransactionId(TRANSACTION_ID);
        profile.setChargingSchedule(schedule);
        return StoredChargingProfile.from(new SetChargingProfileRequest(1, profile),
                java.util.Set.of(0, 1, 2), Map.of(1, TRANSACTION_ID), RECEIVED);
    }
}
