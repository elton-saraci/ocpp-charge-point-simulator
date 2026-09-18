package com.ocpp.chargepointsimulator.services;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.domain.StoredChargingProfile;
import com.ocpp.chargepointsimulator.utilities.SmartChargingCalculator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the stored charging profiles into the power each connector may draw right now.
 *
 * <p>Called once per tick by the metering loop and again whenever the state is read over the REST
 * API, so the answer always follows the clock: a profile whose next period starts in a minute starts
 * limiting on its own, without any message from the central system.
 *
 * <p>The result is cached on the connectors ({@link ConnectorState#getChargingLimitW()},
 * {@link ConnectorState#isSuspended()}), which keeps the REST layer free of any smart charging logic.
 */
@Service
public class SmartChargingService {

    /**
     * Evaluates the stored profiles and caches the outcome on the connectors.
     *
     * <p>Called on every metering tick, so a schedule that moves into its next period takes effect on
     * its own, without any further message from the central system.
     *
     * @return connector id to the power it may draw right now, in Watt; {@code 0} for a connector a
     *     profile suspended
     */
    public Map<Integer, Integer> applyProfiles(ChargePointSession session) {
        List<StoredChargingProfile> profiles = session.getChargingProfiles().all();
        if (profiles.isEmpty()) {
            return withoutProfiles(session);
        }
        Evaluation evaluation = evaluate(session, profiles);
        Map<Integer, Double> allowedWatts =
                SmartChargingCalculator.allocate(evaluation.stationCapWatts(), evaluation.demandWatts());
        return publish(session, evaluation.ownWatts(), allowedWatts);
    }

    /** Without a profile from the central system every connector keeps the nominal power. */
    private Map<Integer, Integer> withoutProfiles(ChargePointSession session) {
        session.getConnectors().forEach(connector -> {
            connector.setChargingLimitW(null);
            connector.setSuspended(false);
        });
        return Map.of();
    }

    private Evaluation evaluate(ChargePointSession session, List<StoredChargingProfile> profiles) {
        Moment moment = momentOf(session);
        Map<Integer, Double> ownWatts = new LinkedHashMap<>();
        Map<Integer, Double> demandWatts = new LinkedHashMap<>();
        for (ConnectorState connector : session.getConnectors()) {
            double limit = ownLimitWatts(profiles, connector, moment);
            ownWatts.put(connector.getConnectorId(), limit);
            if (charges(connector)) {
                // Only the sessions that charge compete for the charge point wide cap.
                demandWatts.put(connector.getConnectorId(), limit);
            }
        }
        Double stationCapWatts = SmartChargingCalculator.stationCapWatts(
                profiles, moment.now(), moment.transactionStart(), moment.electrical());
        return new Evaluation(ownWatts, demandWatts, stationCapWatts);
    }

    /** Writes the outcome onto the connectors, where the metering loop and the REST layer read it. */
    private Map<Integer, Integer> publish(ChargePointSession session,
                                          Map<Integer, Double> ownWatts,
                                          Map<Integer, Double> allowedWatts) {
        Map<Integer, Integer> powerByConnector = new LinkedHashMap<>();
        for (ConnectorState connector : session.getConnectors()) {
            int connectorId = connector.getConnectorId();
            int watts = grantedWatts(connectorId, ownWatts, allowedWatts);
            connector.setChargingLimitW(watts);
            connector.setSuspended(connector.hasTransaction() && watts == 0);
            powerByConnector.put(connectorId, watts);
        }
        return powerByConnector;
    }

    /** The power a connector ends up with: its own limit, reduced to its share of the cap. */
    private static int grantedWatts(int connectorId,
                                    Map<Integer, Double> ownWatts,
                                    Map<Integer, Double> allowedWatts) {
        double own = ownWatts.get(connectorId);
        double share = allowedWatts.getOrDefault(connectorId, own);
        return (int) Math.round(Math.min(share, own));
    }

    /** The limit of this connector alone, ignoring the cap of the charge point. */
    private static double ownLimitWatts(List<StoredChargingProfile> profiles,
                                       ConnectorState connector,
                                       Moment moment) {
        return SmartChargingCalculator
                .limitAt(profiles, connector.getConnectorId(), moment.now(),
                        moment.transactionStart(), moment.electrical())
                .map(SmartChargingCalculator.ApplicableLimit::watts)
                .orElse((double) moment.nominalWatts());
    }

    /** A connector competes for the cap of the charge point once it has a session. */
    private static boolean charges(ConnectorState connector) {
        return connector.hasTransaction() || connector.isCharging();
    }

    private static Moment momentOf(ChargePointSession session) {
        ChargePointConfig config = session.getConfig();
        return new Moment(Instant.now(), session.transactionStart(),
                new SmartChargingCalculator.Electrical(config.phaseVoltage(), config.numberPhases()),
                config.chargingPower());
    }

    /** When the evaluation happens, on which transaction and with which electrical setup. */
    private record Moment(Instant now,
                          Instant transactionStart,
                          SmartChargingCalculator.Electrical electrical,
                          int nominalWatts) {
    }

    /** What the connectors would like to draw, and the cap that has to be shared between them. */
    private record Evaluation(Map<Integer, Double> ownWatts,
                              Map<Integer, Double> demandWatts,
                              Double stationCapWatts) {
    }
}
