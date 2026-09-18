package com.ocpp.chargepointsimulator.schedulers;

import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.ocpp.OcppMessageFactory;
import com.ocpp.chargepointsimulator.ocpp.OcppRequestSender;
import com.ocpp.chargepointsimulator.registry.ChargePointRegistry;
import com.ocpp.chargepointsimulator.services.SmartChargingService;
import eu.chargetime.ocpp.model.Request;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Sends the periodic messages of every connected charge point: heartbeats and, for charging
 * connectors, MeterValues.
 *
 * <p>Each charge point decides when it is due based on its own metering frequency, so one charge
 * point can report every 10 seconds while another reports every minute.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MeterValuesScheduler {

    /** The tick only checks what is due, so it can be short independently of the metering frequency. */
    private static final long TICK_MILLIS = 1_000L;

    private final ChargePointRegistry registry;
    private final OcppRequestSender requestSender;
    private final OcppMessageFactory messageFactory;
    private final SmartChargingService smartChargingService;

    @Scheduled(fixedDelay = TICK_MILLIS)
    public void generateMeterValues() {
        long now = System.currentTimeMillis();
        for (ChargePointSession session : registry.all()) {
            if (!session.isConnected()) {
                continue;
            }
            sendHeartbeatIfDue(session, now);
            reportConnectors(session, now);
        }
    }

    /** The Heartbeat of OCPP, sent at the interval the central system configured. */
    private void sendHeartbeatIfDue(ChargePointSession session, long now) {
        if (!session.isHeartbeatDue(now)) {
            return;
        }
        session.markHeartbeatSent(now);
        send(session, messageFactory.heartbeat(), "Heartbeat");
    }

    /**
     * Re-evaluates the charging profiles of a session and reports what its connectors do, so a
     * period boundary of a schedule takes effect without any further message from the central system.
     */
    private void reportConnectors(ChargePointSession session, long now) {
        Map<Integer, Integer> allowedPowerW = smartChargingService.applyProfiles(session);
        for (ConnectorState connector : session.getConnectors()) {
            reportMeterValues(session, connector, allowedPowerW, now);
        }
    }

    /** Sends the MeterValues of one charging connector, after reporting a suspension if there is one. */
    private void reportMeterValues(ChargePointSession session,
                                   ConnectorState connector,
                                   Map<Integer, Integer> allowedPowerW,
                                   long now) {
        if (!connector.hasTransaction()) {
            return;
        }
        int powerW = allowedPowerW.getOrDefault(connector.getConnectorId(),
                session.getConfig().chargingPower());
        reportSuspension(session, connector, powerW);
        if (!connector.isMeterValuesDue(now, session.getConfig().meterValuesFrequency())) {
            return;
        }
        // Marked as sent before the actual send so a failing central system is retried at the
        // next metering interval instead of on every tick.
        connector.markMeterValuesSent(now);
        send(session, messageFactory.meterValues(session.getConfig(), connector, powerW), "MeterValues");
    }

    /**
     * A connector whose limit became zero is reported as {@code SuspendedEVSE}; it returns to
     * {@code Charging} when the profiles allow power again. The transaction keeps running either way,
     * and other statuses (for example an inoperative connector) are left untouched.
     */
    private void reportSuspension(ChargePointSession session, ConnectorState connector, int powerW) {
        ChargePointStatus current = connector.getStatus();
        if (ChargePointStatus.Charging != current && ChargePointStatus.SuspendedEVSE != current) {
            return;
        }
        ChargePointStatus target = powerW == 0 ? ChargePointStatus.SuspendedEVSE : ChargePointStatus.Charging;
        if (current == target) {
            return;
        }
        connector.setStatus(target);
        log.info("[{}] Connector {} is now {} ({} W allowed).",
                session.getChargePointId(), connector.getConnectorId(), target, powerW);
        send(session, messageFactory.statusNotification(connector, target), "StatusNotification");
    }

    /**
     * Scheduled failures have no caller to report to, so they are logged with their stack trace and
     * recorded on the charge point, where they can be read back over the REST API.
     */
    private void send(ChargePointSession session, Request request, String operation) {
        try {
            requestSender.send(session, request);
        } catch (RuntimeException e) {
            session.recordError(operation + " failed: " + e.getMessage());
            log.error("[{}] Scheduled {} failed, connectors are [{}].",
                    session.getChargePointId(), operation, session.describeConnectors(), e);
        }
    }
}
