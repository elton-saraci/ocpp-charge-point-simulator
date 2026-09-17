package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.exceptions.ChargePointNotConnectedException;
import com.ocpp.chargepointsimulator.exceptions.OcppRequestException;
import eu.chargetime.ocpp.ClientEvents;
import eu.chargetime.ocpp.NotConnectedException;
import eu.chargetime.ocpp.OccurenceConstraintException;
import eu.chargetime.ocpp.UnsupportedFeatureException;
import eu.chargetime.ocpp.model.Confirmation;
import eu.chargetime.ocpp.model.Request;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends OCPP requests over the session of a single charge point and translates every library failure
 * into a meaningful exception.
 *
 * <p>This class replaces the old {@code JsonClientUtility}, which returned {@code null} and logged a
 * message whenever something went wrong, making it impossible for callers to tell a successful
 * exchange from a failed one.
 */
@Slf4j
@Component
public class OcppRequestSender {

    /** The central system is expected to answer an OCPP call within this time. */
    private static final long CONFIRMATION_TIMEOUT_SECONDS = 30L;

    /**
     * Sends a request and blocks until the central system confirms it.
     *
     * @throws ChargePointNotConnectedException when the session has no live WebSocket connection
     * @throws OcppRequestException             for every other protocol or transport failure
     */
    public Confirmation send(ChargePointSession session, Request request) {
        String operation = request.getClass().getSimpleName();
        if (!session.isConnected()) {
            // Checked up front on purpose: the OCPP library throws a NullPointerException instead of a
            // NotConnectedException when a client that was never connected is asked to send.
            throw notConnected(session, operation, null);
        }
        try {
            log.info("[{}] Sending {} -> {}", session.getChargePointId(), operation, request);
            Confirmation confirmation = session.getClient()
                    .send(request)
                    .toCompletableFuture()
                    .get(CONFIRMATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("[{}] {} confirmed with {}", session.getChargePointId(), operation, confirmation);
            return confirmation;
        } catch (OccurenceConstraintException e) {
            throw new OcppRequestException(
                    "Charge point '" + session.getChargePointId() + "' sent an invalid " + operation + ".", e);
        } catch (UnsupportedFeatureException e) {
            throw new OcppRequestException(
                    "The central system does not support " + operation + " for charge point '"
                            + session.getChargePointId() + "'.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcppRequestException(
                    "Interrupted while waiting for the " + operation + " confirmation of charge point '"
                            + session.getChargePointId() + "'.", e);
        } catch (TimeoutException e) {
            throw new OcppRequestException(
                    "No " + operation + " confirmation from the central system within "
                            + CONFIRMATION_TIMEOUT_SECONDS + " seconds for charge point '"
                            + session.getChargePointId() + ".", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (isNotConnected(cause)) {
                throw notConnected(session, operation, cause);
            }
            throw new OcppRequestException(
                    "The central system failed to process " + operation + " for charge point '"
                            + session.getChargePointId() + "'.", cause);
        } catch (WebsocketNotConnectedException e) {
            throw notConnected(session, operation, e);
        }
    }

    /**
     * Sends a request and casts the confirmation to the expected type.
     *
     * @throws OcppRequestException when no confirmation, or an unexpected one, is received
     */
    public <T extends Confirmation> T sendAndExpect(ChargePointSession session, Request request, Class<T> expectedType) {
        Confirmation confirmation = send(session, request);
        if (confirmation == null) {
            throw new OcppRequestException("The central system answered "
                    + request.getClass().getSimpleName() + " for charge point '" + session.getChargePointId()
                    + "' without a confirmation payload.");
        }
        if (!expectedType.isInstance(confirmation)) {
            throw new OcppRequestException("Unexpected confirmation " + confirmation.getClass().getSimpleName()
                    + " received for " + request.getClass().getSimpleName() + " from charge point '"
                    + session.getChargePointId() + "', expected " + expectedType.getSimpleName() + ".");
        }
        return expectedType.cast(confirmation);
    }

    /** Opens the WebSocket connection of the session. The connection state is reported via events. */
    public void connect(ChargePointSession session, ClientEvents events) {
        String webSocketUrl = session.webSocketUrl();
        log.info("[{}] Opening WebSocket connection to {}", session.getChargePointId(), webSocketUrl);
        try {
            session.getClient().connect(webSocketUrl, events);
        } catch (RuntimeException e) {
            session.markDisconnected();
            throw new OcppRequestException("Could not open the WebSocket connection of charge point '"
                    + session.getChargePointId() + "' to " + webSocketUrl + ".", e);
        }
    }

    /** Closes the WebSocket connection of the session. */
    public void disconnect(ChargePointSession session) {
        try {
            session.getClient().disconnect();
        } catch (RuntimeException e) {
            throw new OcppRequestException("Could not close the WebSocket connection of charge point '"
                    + session.getChargePointId() + "'.", e);
        } finally {
            session.markDisconnected();
        }
    }

    private boolean isNotConnected(Throwable cause) {
        return cause instanceof NotConnectedException || cause instanceof WebsocketNotConnectedException;
    }

    private ChargePointNotConnectedException notConnected(ChargePointSession session, String operation, Throwable cause) {
        session.markDisconnected();
        return new ChargePointNotConnectedException("Charge point '" + session.getChargePointId()
                + "' is not connected to " + session.webSocketUrl() + ", " + operation + " was not sent.", cause);
    }
}
