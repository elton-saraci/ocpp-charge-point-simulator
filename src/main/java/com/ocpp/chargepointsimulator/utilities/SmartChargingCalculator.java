package com.ocpp.chargepointsimulator.utilities;

import com.ocpp.chargepointsimulator.domain.StoredChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The physics and the limit rules of OCPP 1.6 smart charging, kept as pure functions so the behavior
 * can be tested without a charge point.
 *
 * <p>Power of a three-phase connection, where the limit is a current per phase:
 *
 * <pre>
 *   P[W] = I[A] * phases * U_phase[V]
 * </pre>
 *
 * <p>A composite limit is the smallest of all limits that apply at that moment, in the precedence
 * the specification defines:
 *
 * <ul>
 *   <li>inside one purpose the profile with the highest {@code stackLevel} wins,
 *   <li>{@code TxProfile} overrules {@code TxDefaultProfile},
 *   <li>{@code ChargePointMaxProfile} limits the charge point as a whole, so it is shared by the
 *       connectors that are actually charging (see {@link #allocate}),
 *   <li>a limit below the {@code minChargingRate} of its schedule means the EV cannot charge at
 *       all, so it counts as zero — a suspended connector.
 * </ul>
 *
 * <p>The schedule that answers {@code GetCompositeSchedule} is assembled by
 * {@link CompositeScheduleBuilder}, which samples these functions.
 */
public final class SmartChargingCalculator {

    private SmartChargingCalculator() {
    }

    /** The electrical properties of a simulated charge point. */
    public record Electrical(int phaseVoltageVolts, int phases) {

        public Electrical {
            if (phaseVoltageVolts <= 0) {
                throw new IllegalArgumentException(
                        "phaseVoltageVolts must be greater than 0, but was " + phaseVoltageVolts + ".");
            }
            if (phases < 1) {
                throw new IllegalArgumentException("phases must be at least 1, but was " + phases + ".");
            }
        }
    }

    /**
     * The limit that applies to a connector right now.
     *
     * @param watts      the limit in Watt
     * @param source     the purpose of the profile the limit came from
     * @param stackLevel the stack level of that profile, which decides between two profiles of one purpose
     */
    public record ApplicableLimit(double watts, ChargingProfilePurposeType source, int stackLevel) {
    }

    /** {@code P = I * phases * U_phase}. */
    public static double ampsToWatts(double amps, int phases, int phaseVoltageVolts) {
        return amps * phases * phaseVoltageVolts;
    }

    /** {@code I = P / (phases * U_phase)}. */
    public static double wattsToAmps(double watts, int phases, int phaseVoltageVolts) {
        return watts / ((double) phases * phaseVoltageVolts);
    }

    /**
     * The phases a period can really use: the ones it asks for, never more than the station has.
     * A three-phase charge point charging with {@code numberPhases = 1} is a normal single phase
     * session, the other way round is not physically possible.
     */
    public static int effectivePhases(StoredChargingProfile.Period period, int stationPhases) {
        return Math.clamp(period.numberPhases(), 1, stationPhases);
    }

    /** Converts a schedule limit into Watt, honoring the phases the period asks for. */
    public static double toWatts(double limit, ChargingRateUnitType unit, int phases, int phaseVoltageVolts) {
        return ChargingRateUnitType.A == unit ? ampsToWatts(limit, phases, phaseVoltageVolts) : limit;
    }

    /**
     * The limit that applies to one connector at a given moment.
     *
     * @param connectorId the connector to evaluate; {@code 0} answers with the charge point wide cap,
     *                    which is how OCPP models the charge point as a whole
     * @return the effective limit in Watt, empty when no profile applies at all
     */
    public static Optional<ApplicableLimit> limitAt(List<StoredChargingProfile> profiles,
                                                    int connectorId,
                                                    Instant now,
                                                    Instant transactionStart,
                                                    Electrical electrical) {
        if (connectorId == 0) {
            return binding(profiles, ChargingProfilePurposeType.ChargePointMaxProfile, 0,
                    now, transactionStart, electrical);
        }
        // Both transaction purposes limit the same connector, so the tighter of the two wins.
        return tighter(
                binding(profiles, ChargingProfilePurposeType.TxProfile, connectorId, now, transactionStart, electrical),
                binding(profiles, ChargingProfilePurposeType.TxDefaultProfile, connectorId, now, transactionStart, electrical));
    }

    private static Optional<ApplicableLimit> tighter(Optional<ApplicableLimit> left, Optional<ApplicableLimit> right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left.get().watts() <= right.get().watts() ? left : right;
    }

    /**
     * The profile of one purpose that limits the connector most, or empty when that purpose has no
     * active profile. Inside a purpose the highest {@code stackLevel} wins, as the specification
     * requires.
     */
    private static Optional<ApplicableLimit> binding(List<StoredChargingProfile> profiles,
                                                     ChargingProfilePurposeType purpose,
                                                     int connectorId,
                                                     Instant now,
                                                     Instant transactionStart,
                                                     Electrical electrical) {
        return profiles.stream()
                .filter(profile -> limits(profile, purpose, connectorId, now))
                .map(profile -> limitOf(profile, now, transactionStart, electrical))
                .flatMap(Optional::stream)
                .reduce(SmartChargingCalculator::preferred);
    }

    /** @return whether a profile of this purpose can limit this connector right now. */
    private static boolean limits(StoredChargingProfile profile,
                                  ChargingProfilePurposeType purpose,
                                  int connectorId,
                                  Instant now) {
        return purpose == profile.purpose() && profile.appliesTo(connectorId) && profile.isValidAt(now);
    }

    private static Optional<ApplicableLimit> limitOf(StoredChargingProfile profile,
                                                     Instant now,
                                                     Instant transactionStart,
                                                     Electrical electrical) {
        return profile.periodAt(now, transactionStart)
                .map(period -> new ApplicableLimit(
                        limitWatts(profile, period, electrical), profile.purpose(), profile.stackLevel()));
    }

    /** A higher stack level wins; on the same level the tighter limit does. */
    private static ApplicableLimit preferred(ApplicableLimit left, ApplicableLimit right) {
        if (left.stackLevel() != right.stackLevel()) {
            return left.stackLevel() > right.stackLevel() ? left : right;
        }
        return left.watts() <= right.watts() ? left : right;
    }

    /**
     * Converts one schedule period into Watt. A limit below the {@code minChargingRate} of the
     * schedule cannot be executed by the EV, so it becomes zero — the specification defines that
     * value as the lowest rate the EV accepts.
     */
    private static double limitWatts(StoredChargingProfile profile,
                                     StoredChargingProfile.Period period,
                                     Electrical electrical) {
        double limit = period.limit();
        if (limit <= 0 || limit < profile.minChargingRate()) {
            return 0d;
        }
        // A period may charge on fewer phases than the station has, never on more.
        int phases = effectivePhases(period, electrical.phases());
        return toWatts(limit, profile.unit(), phases, electrical.phaseVoltageVolts());
    }

    /** The charge point wide cap of the active {@code ChargePointMaxProfile}, if there is one. */
    public static Double stationCapWatts(List<StoredChargingProfile> profiles,
                                          Instant now,
                                          Instant transactionStart,
                                          Electrical electrical) {
        return binding(profiles, ChargingProfilePurposeType.ChargePointMaxProfile, 0, now, transactionStart, electrical)
                .map(ApplicableLimit::watts)
                .orElse(null);
    }

    /**
     * Splits the station wide cap of a {@code ChargePointMaxProfile} over the connectors that charge.
     * As long as the demand fits, nobody is limited. When it does not, every connector is scaled
     * down by the same factor, which keeps the ratio the profiles asked for and never exceeds the cap.
     *
     * @param stationCapWatts the cap of the charge point, or {@code null} when no profile sets one
     * @param demandWatts     connector id to the power the connector would like to draw
     * @return connector id to the power it may draw
     */
    public static Map<Integer, Double> allocate(Double stationCapWatts, Map<Integer, Double> demandWatts) {
        double total = demandWatts.values().stream().mapToDouble(Double::doubleValue).sum();
        if (stationCapWatts == null || total <= stationCapWatts || total <= 0) {
            return Map.copyOf(demandWatts);
        }
        return scaledBy(demandWatts, stationCapWatts / total);
    }

    /** Scales every demand by the same factor, which keeps their ratio and never exceeds the cap. */
    private static Map<Integer, Double> scaledBy(Map<Integer, Double> demandWatts, double factor) {
        Map<Integer, Double> scaled = new LinkedHashMap<>();
        demandWatts.forEach((connectorId, watts) -> scaled.put(connectorId, watts * factor));
        return Map.copyOf(scaled);
    }
}
