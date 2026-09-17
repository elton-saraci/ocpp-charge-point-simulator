package com.ocpp.chargepointsimulator.schedulers;

import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.ocpp.OcppMessageFactory;
import com.ocpp.chargepointsimulator.ocpp.OcppRequestSender;
import com.ocpp.chargepointsimulator.registry.ChargePointRegistry;
import eu.chargetime.ocpp.model.Request;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    @Scheduled(fixedDelay = TICK_MILLIS)
    public void generateMeterValues() {
        long now = System.currentTimeMillis();
        for (ChargePointSession session : registry.all()) {
            if (!session.isConnected()) {
                continue;
            }
            if (session.isHeartbeatDue(now)) {
                session.markHeartbeatSent(now);
                send(session, messageFactory.heartbeat(), "Heartbeat");
            }
            for (ConnectorState connector : session.getConnectors()) {
                if (!connector.isCharging()
                        || !connector.isMeterValuesDue(now, session.getConfig().meterValuesFrequency())) {
                    continue;
                }
                // Marked as sent before the actual send so a failing central system is retried at the
                // next metering interval instead of on every tick.
                connector.markMeterValuesSent(now);
                send(session, messageFactory.meterValues(session.getConfig(), connector), "MeterValues");
            }
        }
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
