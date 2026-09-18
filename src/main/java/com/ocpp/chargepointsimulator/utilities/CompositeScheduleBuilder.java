package com.ocpp.chargepointsimulator.utilities;

import com.ocpp.chargepointsimulator.domain.StoredChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfileKindType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ChargingSchedule;
import eu.chargetime.ocpp.model.core.ChargingSchedulePeriod;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds the answer to {@code GetCompositeSchedule}: a single piecewise constant schedule that already
 * merged every profile that applies to the connector, expressed in the requested unit.
 *
 * <p>Instead of merging the profiles by hand, the builder asks {@link SmartChargingCalculator#limitAt}
 * for the effective limit at every instant where a limit can change — the period starts of each
 * profile, the ends of their durations, the boundaries of the repetitions of a recurring profile and
 * the end of the window. Sampling and live behavior therefore share one code path and cannot drift
 * apart.
 */
public final class CompositeScheduleBuilder {

    /** Guard against pathological schedules when the repetitions of a recurring profile are expanded. */
    private static final int MAX_BOUNDARIES = 512;

    private CompositeScheduleBuilder() {
    }

    /** What the composite schedule is asked for: the connector, the window and the electrical setup. */
    public record Request(int connectorId,
                          Instant now,
                          Instant transactionStart,
                          int durationSeconds,
                          ChargingRateUnitType unit,
                          SmartChargingCalculator.Electrical electrical,
                          int nominalWatts) {

        public Request {
            if (durationSeconds <= 0) {
                throw new IllegalArgumentException(
                        "durationSeconds must be positive, but was " + durationSeconds + ".");
            }
        }

        Instant end() {
            return now.plusSeconds(durationSeconds);
        }
    }

    public static ChargingSchedule build(List<StoredChargingProfile> profiles, Request request) {
        List<ChargingSchedulePeriod> periods = new ArrayList<>();
        for (Instant boundary : boundaries(profiles, request)) {
            appendPeriod(periods, profiles, request, boundary);
        }
        return schedule(periods, request);
    }

    /** Adds the period that starts at the boundary, unless the limit did not change since the last one. */
    private static void appendPeriod(List<ChargingSchedulePeriod> periods,
                                     List<StoredChargingProfile> profiles,
                                     Request request,
                                     Instant boundary) {
        double limit = round(toUnit(wattsAt(profiles, request, boundary), request));
        if (!periods.isEmpty() && periods.getLast().getLimit() == limit) {
            return;
        }
        ChargingSchedulePeriod period =
                new ChargingSchedulePeriod(startPeriod(boundary, request), limit);
        if (ChargingRateUnitType.A == request.unit()) {
            period.setNumberPhases(request.electrical().phases());
        }
        periods.add(period);
    }

    /** The power the connector may draw at a moment: what the profiles allow, capped by the charge point. */
    private static double wattsAt(List<StoredChargingProfile> profiles, Request request, Instant at) {
        double watts = SmartChargingCalculator
                .limitAt(profiles, request.connectorId(), at, request.transactionStart(), request.electrical())
                .map(SmartChargingCalculator.ApplicableLimit::watts)
                .orElse((double) request.nominalWatts());
        Double stationCap = SmartChargingCalculator.stationCapWatts(
                profiles, at, request.transactionStart(), request.electrical());
        return stationCap == null ? watts : Math.min(watts, stationCap);
    }

    private static ChargingSchedule schedule(List<ChargingSchedulePeriod> periods, Request request) {
        ChargingSchedule schedule = new ChargingSchedule(
                request.unit(), periods.toArray(ChargingSchedulePeriod[]::new));
        schedule.setDuration(request.durationSeconds());
        schedule.setStartSchedule(ZonedDateTime.ofInstant(request.now(), ZoneOffset.UTC));
        return schedule;
    }

    /** Every instant inside the window where the composite limit can change. */
    private static List<Instant> boundaries(List<StoredChargingProfile> profiles, Request request) {
        TreeSet<Instant> boundaries = new TreeSet<>();
        boundaries.add(request.now());
        boundaries.add(request.end());
        for (StoredChargingProfile profile : profiles) {
            if (profile.appliesTo(request.connectorId()) && profile.isValidAt(request.now())) {
                boundaries.addAll(boundariesOf(profile, request));
            }
        }
        return insideWindow(boundaries, request);
    }

    private static List<Instant> boundariesOf(StoredChargingProfile profile, Request request) {
        return anchor(profile, request.transactionStart())
                .map(anchor -> boundariesFrom(profile, anchor, request))
                .orElseGet(List::of);
    }

    /** The boundaries of one profile, repeated over every recurrence that reaches into the window. */
    private static List<Instant> boundariesFrom(StoredChargingProfile profile, Instant anchor, Request request) {
        Recurrence recurrence = recurrenceOf(profile, anchor, request);
        List<Instant> boundaries = new ArrayList<>();
        for (long repetition = recurrence.first();
             repetition <= recurrence.last() && boundaries.size() < MAX_BOUNDARIES;
             repetition++) {
            Instant base = anchor.plusSeconds(recurrence.windowSeconds() * repetition);
            boundaries.addAll(boundariesFromBase(profile, base));
        }
        return boundaries;
    }

    /** Which repetitions of a profile reach into the window, and how long a single repetition lasts. */
    private record Recurrence(long windowSeconds, long first, long last) {
    }

    private static Recurrence recurrenceOf(StoredChargingProfile profile, Instant anchor, Request request) {
        if (ChargingProfileKindType.Recurring != profile.kind()) {
            // Every other kind of profile applies once, so a single repetition covers it.
            return new Recurrence(1L, 0L, 0L);
        }
        long windowSeconds = profile.recurrenceSeconds();
        // One repetition before the window, so a period that started earlier still carves it up.
        long first = Math.max(0L, secondsBetween(anchor, request.now()) / windowSeconds - 1L);
        long last = secondsBetween(anchor, request.end()) / windowSeconds + 1L;
        return new Recurrence(windowSeconds, first, Math.max(first, last));
    }

    /** The start of every period of a profile and the end of its duration, relative to one base instant. */
    private static List<Instant> boundariesFromBase(StoredChargingProfile profile, Instant base) {
        List<Instant> boundaries = profile.periods().stream()
                .map(period -> base.plusSeconds(period.startPeriodSeconds()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (profile.durationSeconds() != null) {
            boundaries.add(base.plusSeconds(profile.durationSeconds()));
        }
        return boundaries;
    }

    private static List<Instant> insideWindow(TreeSet<Instant> boundaries, Request request) {
        return boundaries.stream()
                .filter(boundary -> !boundary.isBefore(request.now()) && !boundary.isAfter(request.end()))
                .limit(MAX_BOUNDARIES)
                .toList();
    }

    /** The instant a schedule of this profile counts from, empty when it has no anchor yet. */
    private static Optional<Instant> anchor(StoredChargingProfile profile, Instant transactionStart) {
        return switch (profile.kind()) {
            case Relative -> Optional.ofNullable(transactionStart);
            case Absolute, Recurring -> Optional.of(
                    profile.startSchedule() == null ? profile.receivedAt() : profile.startSchedule());
        };
    }

    private static int startPeriod(Instant boundary, Request request) {
        return (int) secondsBetween(request.now(), boundary);
    }

    private static double toUnit(double watts, Request request) {
        return ChargingRateUnitType.A == request.unit()
                ? SmartChargingCalculator.wattsToAmps(
                        watts, request.electrical().phases(), request.electrical().phaseVoltageVolts())
                : watts;
    }

    private static long secondsBetween(Instant from, Instant to) {
        return to.getEpochSecond() - from.getEpochSecond();
    }

    private static double round(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
