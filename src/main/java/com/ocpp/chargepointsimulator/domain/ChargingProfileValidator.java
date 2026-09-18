package com.ocpp.chargepointsimulator.domain;

import com.ocpp.chargepointsimulator.exceptions.InvalidChargingProfileException;
import eu.chargetime.ocpp.model.core.ChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfileKindType;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ChargingSchedule;
import eu.chargetime.ocpp.model.core.ChargingSchedulePeriod;
import eu.chargetime.ocpp.model.core.RecurrencyKindType;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an incoming {@code SetChargingProfile} request into a {@link StoredChargingProfile}, enforcing
 * the validity rules of OCPP 1.6 smart charging on the way.
 *
 * <p>Every rule the specification calls invalid is reported by throwing
 * {@link InvalidChargingProfileException} with the reason, which the charge point answers with a
 * {@code Rejected} charging profile status. A caller therefore only has to deal with two outcomes:
 * a stored profile, or an exception that explains the refusal.
 *
 * <p>The methods mirror the shape of the message — payload, connector, purpose, kind, schedule,
 * periods — so each rule can be read on its own.
 */
public final class ChargingProfileValidator {

    /** Phases assumed when a period does not state them, which is the OCPP default. */
    private static final int DEFAULT_PHASES = 3;
    private static final int MAX_PHASES = 3;

    private ChargingProfileValidator() {
    }

    /**
     * @param request             the incoming request
     * @param connectorIds        the connectors of the charge point, connector {@code 0} is always allowed
     * @param runningTransactions connector id to the id of its running transaction
     * @param receivedAt          when the profile arrived, the fallback start of an Absolute schedule
     * @throws InvalidChargingProfileException when the request breaks the rules of the specification
     */
    public static StoredChargingProfile validate(SetChargingProfileRequest request,
                                                 Set<Integer> connectorIds,
                                                 Map<Integer, Integer> runningTransactions,
                                                 Instant receivedAt) {
        ChargingProfile profile = requiredProfile(request);
        int connectorId = connectorId(request, connectorIds);
        ChargingSchedule schedule = requiredSchedule(profile);

        requirePurposeFitsConnector(profile.getChargingProfilePurpose(), connectorId);
        requireTransactionMatches(profile, connectorId, runningTransactions);

        return new StoredChargingProfile(
                profile.getChargingProfileId(),
                connectorId,
                profile.getTransactionId(),
                stackLevel(profile),
                profile.getChargingProfilePurpose(),
                profile.getChargingProfileKind(),
                recurrencyKind(profile),
                instant(profile.getValidFrom()),
                instant(profile.getValidTo()),
                schedule.getChargingRateUnit(),
                schedule.getDuration(),
                instant(schedule.getStartSchedule()),
                minChargingRate(schedule),
                periods(schedule),
                receivedAt);
    }

    /** @return the profile of the request, with everything the specification requires present. */
    private static ChargingProfile requiredProfile(SetChargingProfileRequest request) {
        if (request == null || request.getCsChargingProfiles() == null) {
            throw new InvalidChargingProfileException("csChargingProfiles is required.");
        }
        ChargingProfile profile = request.getCsChargingProfiles();
        if (profile.getChargingProfileId() == null) {
            throw new InvalidChargingProfileException("chargingProfileId is required.");
        }
        if (profile.getChargingProfilePurpose() == null) {
            throw new InvalidChargingProfileException("chargingProfilePurpose is required.");
        }
        if (profile.getChargingProfileKind() == null) {
            throw new InvalidChargingProfileException("chargingProfileKind is required.");
        }
        return profile;
    }

    private static int connectorId(SetChargingProfileRequest request, Set<Integer> connectorIds) {
        Integer connectorId = request.getConnectorId();
        if (connectorId == null || connectorId < 0) {
            throw new InvalidChargingProfileException("connectorId is required but was " + connectorId + ".");
        }
        if (connectorId > 0 && !connectorIds.contains(connectorId)) {
            throw new InvalidChargingProfileException(
                    "connectorId " + connectorId + " is not one of the connectors " + connectorIds + ".");
        }
        return connectorId;
    }

    /** A station wide cap belongs to connector 0, a transaction profile needs a real connector. */
    private static void requirePurposeFitsConnector(ChargingProfilePurposeType purpose, int connectorId) {
        if (ChargingProfilePurposeType.ChargePointMaxProfile == purpose && connectorId != 0) {
            throw new InvalidChargingProfileException(
                    "a ChargePointMaxProfile must be sent for connectorId 0, but was " + connectorId + ".");
        }
        if (ChargingProfilePurposeType.TxProfile == purpose && connectorId == 0) {
            throw new InvalidChargingProfileException("a TxProfile needs a connectorId greater than 0.");
        }
    }

    /** A TxProfile has to name the transaction that runs on that connector, and that one only. */
    private static void requireTransactionMatches(ChargingProfile profile,
                                                  int connectorId,
                                                  Map<Integer, Integer> runningTransactions) {
        if (ChargingProfilePurposeType.TxProfile != profile.getChargingProfilePurpose()) {
            return;
        }
        Integer runningTransactionId = runningTransactions.get(connectorId);
        if (runningTransactionId == null) {
            throw new InvalidChargingProfileException(
                    "connector " + connectorId + " has no running transaction to apply a TxProfile to.");
        }
        if (profile.getTransactionId() == null) {
            throw new InvalidChargingProfileException("a TxProfile needs the transactionId of connector "
                    + connectorId + ", which is running transaction " + runningTransactionId + ".");
        }
        if (!runningTransactionId.equals(profile.getTransactionId())) {
            throw new InvalidChargingProfileException("the TxProfile is for transaction "
                    + profile.getTransactionId() + " but connector " + connectorId + " runs transaction "
                    + runningTransactionId + ".");
        }
    }

    private static ChargingSchedule requiredSchedule(ChargingProfile profile) {
        ChargingSchedule schedule = profile.getChargingSchedule();
        if (schedule == null) {
            throw new InvalidChargingProfileException("chargingSchedule is required.");
        }
        if (schedule.getChargingRateUnit() == null) {
            throw new InvalidChargingProfileException("chargingSchedule.chargingRateUnit is required (W or A).");
        }
        if (schedule.getDuration() != null && schedule.getDuration() <= 0) {
            throw new InvalidChargingProfileException("chargingSchedule.duration must be positive when present, "
                    + "but was " + schedule.getDuration() + ".");
        }
        return schedule;
    }

    private static int stackLevel(ChargingProfile profile) {
        int stackLevel = profile.getStackLevel() == null ? 0 : profile.getStackLevel();
        if (stackLevel < 0) {
            throw new InvalidChargingProfileException(
                    "stackLevel must not be negative, but was " + stackLevel + ".");
        }
        return stackLevel;
    }

    /** {@code recurrencyKind} exists for recurring profiles, and only for those. */
    private static RecurrencyKindType recurrencyKind(ChargingProfile profile) {
        RecurrencyKindType recurrencyKind = profile.getRecurrencyKind();
        boolean recurring = ChargingProfileKindType.Recurring == profile.getChargingProfileKind();
        if (recurring && recurrencyKind == null) {
            throw new InvalidChargingProfileException("a Recurring profile needs recurrencyKind (Daily or Weekly).");
        }
        if (!recurring && recurrencyKind != null) {
            throw new InvalidChargingProfileException("recurrencyKind only applies to a Recurring profile, "
                    + "but the kind is " + profile.getChargingProfileKind() + ".");
        }
        return recurrencyKind;
    }

    private static double minChargingRate(ChargingSchedule schedule) {
        return schedule.getMinChargingRate() == null ? 0d : schedule.getMinChargingRate();
    }

    private static Instant instant(ZonedDateTime moment) {
        return moment == null ? null : moment.toInstant();
    }

    /**
     * @return the periods of the schedule, ordered by start period — the order the evaluation of a
     *     profile relies on
     */
    private static List<StoredChargingProfile.Period> periods(ChargingSchedule schedule) {
        ChargingSchedulePeriod[] raw = schedule.getChargingSchedulePeriod();
        if (raw == null || raw.length == 0) {
            throw new InvalidChargingProfileException(
                    "chargingSchedule.chargingSchedulePeriod needs at least one period.");
        }
        List<StoredChargingProfile.Period> periods = new ArrayList<>(raw.length);
        Integer previousStart = null;
        for (ChargingSchedulePeriod period : raw) {
            int startPeriod = startPeriod(period, previousStart);
            periods.add(new StoredChargingProfile.Period(startPeriod, limit(period),
                    phases(period, schedule.getChargingRateUnit())));
            previousStart = startPeriod;
        }
        return List.copyOf(periods);
    }

    /** @return the start of a period, which has to be higher than the start of the period before it. */
    private static int startPeriod(ChargingSchedulePeriod period, Integer previousStart) {
        if (period == null || period.getStartPeriod() == null) {
            throw new InvalidChargingProfileException("every chargingSchedulePeriod needs a startPeriod.");
        }
        int startPeriod = period.getStartPeriod();
        if (startPeriod < 0) {
            throw new InvalidChargingProfileException(
                    "a chargingSchedulePeriod startPeriod must not be negative, but was " + startPeriod + ".");
        }
        if (previousStart != null && startPeriod <= previousStart) {
            throw new InvalidChargingProfileException(
                    "startPeriod values must increase, but " + startPeriod + " follows " + previousStart + ".");
        }
        return startPeriod;
    }

    private static double limit(ChargingSchedulePeriod period) {
        if (period.getLimit() == null) {
            throw new InvalidChargingProfileException("every chargingSchedulePeriod needs a limit.");
        }
        if (period.getLimit() < 0) {
            throw new InvalidChargingProfileException(
                    "a chargingSchedulePeriod limit must not be negative, but was " + period.getLimit() + ".");
        }
        return period.getLimit();
    }

    /**
     * {@code numberPhases} is only used when the limit is a current. Some central systems send it with
     * a Watt limit anyway — the OCPP library even defaults the field to 3 — so it is ignored there
     * instead of being refused.
     */
    private static int phases(ChargingSchedulePeriod period, ChargingRateUnitType unit) {
        Integer numberPhases = period.getNumberPhases();
        if (numberPhases == null || ChargingRateUnitType.W == unit) {
            return DEFAULT_PHASES;
        }
        if (numberPhases < 1 || numberPhases > MAX_PHASES) {
            throw new InvalidChargingProfileException(
                    "numberPhases must be between 1 and " + MAX_PHASES + ", but was " + numberPhases + ".");
        }
        return numberPhases;
    }
}
