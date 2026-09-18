package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import eu.chargetime.ocpp.ClientEvents;
import eu.chargetime.ocpp.model.core.BootNotificationConfirmation;
import eu.chargetime.ocpp.model.core.RegistrationStatus;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the WebSocket lifecycle of a charge point session: connecting, disconnecting and sending the
 * BootNotification once the connection is up.
 *
 * <p>Connection failures are reported through {@link ChargePointSession#isConnected()} and
 * {@link ChargePointSession#getLastError()} instead of being swallowed, and a closed connection is
 * not retried automatically: call {@code POST /api/charge-points/connect?cpId=...} to reconnect.
 */
@Component
@Slf4j
public class ChargePointConnectionManager {

    /** The OCPP library only flips its internal "connected" flag a moment after the callback. */
    private static final long BOOT_NOTIFICATION_DELAY_SECONDS = 1L;

    /** Used to notice connection attempts that never complete, e.g. an unreachable central system. */
    private static final long CONNECT_CHECK_DELAY_SECONDS = 10L;

    private final OcppRequestSender requestSender;
    private final OcppMessageFactory messageFactory;

    /**
     * Schedules the delayed BootNotification and the connect check. Scheduling never blocks, so a small
     * pool is enough for every charge point of this instance. It does not use the application task
     * scheduler, because that one also drives the scheduled metering.
     */
    private final ScheduledExecutorService bootNotificationScheduler =
            Executors.newScheduledThreadPool(2, namedThreadFactory());

    /**
     * Runs the work that blocks until the central system answers. Sending a BootNotification waits for
     * the confirmation, and the OCPP library blocks the calling thread while it does. A cached pool
     * gives every booting charge point its own thread, so a central system that never confirms cannot
     * delay the boot notification of all the other charge points.
     */
    private final ExecutorService bootNotificationExecutor = Executors.newCachedThreadPool(namedThreadFactory());

    public ChargePointConnectionManager(OcppRequestSender requestSender, OcppMessageFactory messageFactory) {
        this.requestSender = requestSender;
        this.messageFactory = messageFactory;
    }

    @PreDestroy
    void shutdown() {
        bootNotificationScheduler.shutdownNow();
        bootNotificationExecutor.shutdownNow();
    }

    public void connect(ChargePointSession session) {
        if (session.isConnected()) {
            log.info("[{}] Already connected to {}, connect request ignored.",
                    session.getChargePointId(), session.webSocketUrl());
            return;
        }
        String webSocketUrl = session.webSocketUrl();
        requestSender.connect(session, new ClientEvents() {
            @Override
            public void connectionOpened() {
                session.markConnected();
                log.info("[{}] WebSocket connection opened to {}.", session.getChargePointId(), webSocketUrl);
                scheduleBootNotification(session);
            }

            @Override
            public void connectionClosed() {
                session.markDisconnected();
                log.warn("[{}] WebSocket connection to {} closed. Reconnect with "
                                + "POST /api/charge-points/connect?cpId={}.",
                        session.getChargePointId(), webSocketUrl, session.getChargePointId());
            }
        });
        scheduleConnectCheck(session, webSocketUrl);
    }

    /**
     * A connection attempt that fails before the WebSocket is open produces no callback of the OCPP
     * library, so the session would silently stay disconnected. This check makes that state visible.
     */
    private void scheduleConnectCheck(ChargePointSession session, String webSocketUrl) {
        bootNotificationScheduler.schedule(() -> {
            if (!session.isConnected()) {
                session.recordError("No WebSocket connection to " + webSocketUrl + ".");
                log.warn("[{}] Still not connected to {} {}s after the connect request, "
                                + "the central system may be unreachable.",
                        session.getChargePointId(), webSocketUrl, CONNECT_CHECK_DELAY_SECONDS);
            }
        }, CONNECT_CHECK_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public void disconnect(ChargePointSession session) {
        if (!session.isConnected()) {
            log.info("[{}] Is not connected, disconnect request ignored.", session.getChargePointId());
        }
        try {
            requestSender.disconnect(session);
            log.info("[{}] Disconnected from {}.", session.getChargePointId(), session.webSocketUrl());
        } finally {
            session.markDisconnected();
        }
    }

    /** Disconnects without failing the caller, used when a charge point is removed from the registry. */
    public void disconnectQuietly(ChargePointSession session) {
        try {
            disconnect(session);
        } catch (RuntimeException e) {
            session.recordError("Could not close the WebSocket connection: " + e.getMessage());
            log.warn("[{}] Could not close the WebSocket connection while removing the charge point: {}",
                    session.getChargePointId(), e.getMessage(), e);
        }
    }

    private void scheduleBootNotification(ChargePointSession session) {
        bootNotificationScheduler.schedule(
                () -> bootNotificationExecutor.execute(() -> sendBootNotification(session)),
                BOOT_NOTIFICATION_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void sendBootNotification(ChargePointSession session) {
        try {
            BootNotificationConfirmation confirmation = requestSender.sendAndExpect(session,
                    messageFactory.bootNotification(session.getConfig()), BootNotificationConfirmation.class);
            if (RegistrationStatus.Accepted == confirmation.getStatus()) {
                log.info("[{}] BootNotification accepted (heartbeat interval {}s).",
                        session.getChargePointId(), confirmation.getInterval());
            } else {
                session.recordError("BootNotification was not accepted: " + confirmation.getStatus());
                log.warn("[{}] BootNotification was not accepted: {}",
                        session.getChargePointId(), confirmation.getStatus());
            }
        } catch (RuntimeException e) {
            session.recordError("BootNotification failed: " + e.getMessage());
            log.error("[{}] BootNotification failed.", session.getChargePointId(), e);
        }
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "ocpp-boot-notification-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
