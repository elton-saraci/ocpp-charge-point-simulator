package com.ocpp.chargepointsimulator.domain;

import eu.chargetime.ocpp.model.core.ChargingProfileKindType;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.RecurrencyKindType;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A charging profile as the charge point holds it, with the OCPP rules of 1.6 already applied and
 * the time bookkeeping that the specification leaves to the charge point.
 *
 * <p>Time semantics of the three profile kinds, where {@code t} is the elapsed time inside the
 * schedule:
 *
 * <ul>
 *   <li>{@code Absolute} — the schedule starts at {@code startSchedule}, or when the profile was
 *       received if the schedule carries no start. Before that instant the profile is not active.
 *   <li>{@code Relative} — the schedule starts when the transaction starts, so the profile only
 *       applies while a transaction runs.
 *   <li>{@code Recurring} — the schedule repeats every {@code duration} seconds, or every day or
 *       week when no duration is given.
 * </ul>
 *
 * <p>{@code duration} always ends the schedule: once {@code t} passes it, the profile no longer
 * applies. A profile also stops applying outside {@code validFrom}/{@code validTo}.
 *
 * <p>Instances are created through {@link #from}, which hands the payload to
 * {@link ChargingProfileValidator}: a profile that violates the specification never reaches this
 * record, the validator refuses it first and the charge point answers {@code Rejected}.
 */
public record StoredChargingProfile(
        int chargingProfileId,
        int connectorId,
        Integer transactionId,
        int stackLevel,
        ChargingProfilePurposeType purpose,
        ChargingProfileKindType kind,
        RecurrencyKindType recurrencyKind,
        Instant validFrom,
        Instant validTo,
        ChargingRateUnitType unit,
        Integer durationSeconds,
        Instant startSchedule,
        double minChargingRate,
        List<Period> periods,
        Instant receivedAt) {

    /** Constant of the recurrence of a profile that has no duration of its own. */
    private static final long DAILY_SECONDS = 86_400L;
    private static final long WEEKLY_SECONDS = 604_800L;

    /**
     * One {@code chargingSchedulePeriod}: from {@code startPeriod} seconds onwards the connector may
     * draw {@code limit}, expressed in the unit of the schedule.
     */
    public record Period(int startPeriodSeconds, double limit, int numberPhases) {
    }

    /**
     * Builds a stored profile from an incoming {@code SetChargingProfile} request.
     *
     * <p>The rules of the specification live in {@link ChargingProfileValidator}; this method is the
     * single entry point of the domain so callers do not have to know that.
     *
     * @param request             the incoming request
     * @param knownConnectorIds   the connectors of the charge point, {@code 0} is always allowed
     * @param runningTransactions connector id to the id of its running transaction
     * @param now                 when the profile was received, the fallback start of an Absolute schedule
     * @throws com.ocpp.chargepointsimulator.exceptions.InvalidChargingProfileException when the request is invalid
     */
    public static StoredChargingProfile from(SetChargingProfileRequest request,
                                             Set<Integer> knownConnectorIds,
                                             Map<Integer, Integer> runningTransactions,
                                             Instant now) {
        return ChargingProfileValidator.validate(request, knownConnectorIds, runningTransactions, now);
    }

    /** @return whether this profile can limit the given connector (connector 0 profiles apply to all). */
    public boolean appliesTo(int otherConnectorId) {
        return connectorId == 0 || connectorId == otherConnectorId;
    }

    /**
     * @return the period that applies at the given moment, or empty when the profile is not active
     *     (not started yet, expired, outside its validity window)
     */
    public Optional<Period> periodAt(Instant now, Instant transactionStart) {
        return elapsedSeconds(now, transactionStart).flatMap(this::periodAtElapsed);
    }

    private Optional<Period> periodAtElapsed(long elapsedSeconds) {
        if (elapsedSeconds < 0 || !withinWindow(elapsedSeconds)) {
            return Optional.empty();
        }
        Period applicable = periods.getFirst();
        for (Period period : periods) {
            if (period.startPeriodSeconds() <= elapsedSeconds) {
                applicable = period;
            } else {
                break;
            }
        }
        return Optional.of(applicable);
    }

    private boolean withinWindow(long elapsedSeconds) {
        return durationSeconds == null || elapsedSeconds < durationSeconds;
    }

    /** @return whether this profile is inside its validity window right now. */
    public boolean isValidAt(Instant now) {
        return (validFrom == null || !now.isBefore(validFrom)) && (validTo == null || !now.isAfter(validTo));
    }

    /**
     * @return seconds since the schedule started, negative when it starts in the future, empty when
     *     the schedule has no anchor yet (a Relative profile without a transaction)
     */
    public Optional<Long> elapsedSeconds(Instant now, Instant transactionStart) {
        return switch (kind) {
            case Absolute -> elapsedSince(anchor(receivedAt, startSchedule), now);
            case Relative -> elapsedSince(Optional.ofNullable(transactionStart), now);
            case Recurring -> anchor(receivedAt, startSchedule)
                    .map(base -> elapsedSince(base, now) % recurrenceSeconds());
        };
    }

    /** Seconds since one base instant, negative while the schedule still starts in the future. */
    private static long elapsedSince(Instant base, Instant now) {
        return now.getEpochSecond() - base.getEpochSecond();
    }

    private static Optional<Long> elapsedSince(Optional<Instant> base, Instant now) {
        return base.map(start -> elapsedSince(start, now));
    }

    /** @return the length of one recurrence of a Recurring profile. */
    public long recurrenceSeconds() {
        if (durationSeconds != null) {
            return durationSeconds;
        }
        return RecurrencyKindType.Weekly == recurrencyKind ? WEEKLY_SECONDS : DAILY_SECONDS;
    }

    private static Optional<Instant> anchor(Instant fallback, Instant scheduleStart) {
        return Optional.of(scheduleStart == null ? fallback : scheduleStart);
    }

    /** Human readable form used in logs and in the REST responses. */
    public String describe() {
        return "profile " + chargingProfileId + " (" + purpose + ", " + kind + ", stack " + stackLevel
                + ", connector " + connectorId + ", " + unit + ", " + periods.size() + " period(s))";
    }
}
