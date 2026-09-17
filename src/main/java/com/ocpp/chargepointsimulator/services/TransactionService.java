package com.ocpp.chargepointsimulator.services;

import com.ocpp.chargepointsimulator.domain.AuthorizationOutcome;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.exceptions.OcppRequestException;
import com.ocpp.chargepointsimulator.ocpp.OcppMessageFactory;
import com.ocpp.chargepointsimulator.ocpp.OcppRequestSender;
import eu.chargetime.ocpp.model.core.AuthorizationStatus;
import eu.chargetime.ocpp.model.core.AuthorizeConfirmation;
import eu.chargetime.ocpp.model.core.AuthorizeRequest;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.IdTagInfo;
import eu.chargetime.ocpp.model.core.Reason;
import eu.chargetime.ocpp.model.core.StartTransactionConfirmation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Drives the connector state machine: plugging in and out, RFID authorization and the transactions
 * the central system triggers remotely.
 *
 * <p>Operations requested over HTTP run synchronously so failures reach the caller. Operations
 * triggered by an incoming OCPP request run in the background, because the central system has to get
 * a confirmation immediately; failures there are logged with their stack trace and recorded on the
 * charge point so they stay visible through the REST API.
 */
@Service
@Slf4j
public class TransactionService {

    /** Statuses a connector may be in when the central system wants to start a transaction. */
    private static final List<ChargePointStatus> REMOTE_START_ALLOWED_STATUSES =
            List.of(ChargePointStatus.Available, ChargePointStatus.Preparing,
                    ChargePointStatus.Reserved, ChargePointStatus.Finishing);

    /** Mirrors the original simulator, which answered a remote start/stop and acted a moment later. */
    private static final long REMOTE_TRANSACTION_DELAY_MILLIS = 3_000L;

    private final OcppRequestSender requestSender;
    private final OcppMessageFactory messageFactory;
    private final ExecutorService remoteTransactionExecutor;

    public TransactionService(OcppRequestSender requestSender,
                              OcppMessageFactory messageFactory,
                              ExecutorService remoteTransactionExecutor) {
        this.requestSender = requestSender;
        this.messageFactory = messageFactory;
        this.remoteTransactionExecutor = remoteTransactionExecutor;
    }

    /** Connector an operation applies to when the caller did not name one. */
    public ConnectorState selectDefaultConnector(ChargePointSession session) {
        return session.getConnectors().stream()
                .filter(connector -> ChargePointStatus.Available == connector.getStatus())
                .findFirst()
                .orElseGet(() -> session.getConnectors().iterator().next());
    }

    /**
     * @throws com.ocpp.chargepointsimulator.exceptions.ConnectorNotFoundException when an explicit
     *         connector id is not exposed by the charge point
     */
    public ConnectorState resolveConnector(ChargePointSession session, Integer connectorId) {
        return connectorId == null ? selectDefaultConnector(session) : session.getConnector(connectorId);
    }

    public ConnectorState plugIn(ChargePointSession session, Integer connectorId) {
        ConnectorState connector = resolveConnector(session, connectorId);
        ChargePointStatus status = connector.getStatus();
        log.info("[{}] Plug-in on connector {} (status {}, idTag {})",
                session.getChargePointId(), connector.getConnectorId(), status, connector.getIdTag());

        if (ChargePointStatus.Charging == status) {
            log.warn("[{}] Connector {} is already charging, plug-in ignored.",
                    session.getChargePointId(), connector.getConnectorId());
            return connector;
        }
        if (ChargePointStatus.Available == status && connector.getIdTag() != null) {
            startTransaction(session, connector, connector.getIdTag());
            return connector;
        }
        return notifyStatus(session, connector, ChargePointStatus.Preparing);
    }

    public ConnectorState plugOut(ChargePointSession session, Integer connectorId) {
        ConnectorState connector = resolveConnector(session, connectorId);
        ChargePointStatus status = connector.getStatus();
        log.info("[{}] Plug-out on connector {} (status {})",
                session.getChargePointId(), connector.getConnectorId(), status);

        if (ChargePointStatus.Charging == status) {
            return stopTransaction(session, connector, ChargePointStatus.Available, Reason.EVDisconnected);
        }
        if (ChargePointStatus.Preparing == status || ChargePointStatus.Finishing == status) {
            return notifyStatus(session, connector, ChargePointStatus.Available);
        }
        log.info("[{}] Connector {} is {}, plug-out has no effect.",
                session.getChargePointId(), connector.getConnectorId(), status);
        return connector;
    }

    public AuthorizationOutcome authorize(ChargePointSession session, Integer connectorId, String idTag) {
        ConnectorState connector = resolveConnector(session, connectorId);
        AuthorizeConfirmation confirmation =
                requestSender.sendAndExpect(session, new AuthorizeRequest(idTag), AuthorizeConfirmation.class);
        IdTagInfo idTagInfo = confirmation.getIdTagInfo();
        AuthorizationStatus authorizationStatus = idTagInfo == null ? null : idTagInfo.getStatus();

        if (AuthorizationStatus.Accepted != authorizationStatus) {
            log.warn("[{}] Connector {} rejected the authorization of idTag '{}' (status {}).",
                    session.getChargePointId(), connector.getConnectorId(), idTag, authorizationStatus);
            return new AuthorizationOutcome(connector.getConnectorId(), false, authorizationStatus);
        }

        log.info("[{}] Connector {} authorized idTag '{}'.", session.getChargePointId(),
                connector.getConnectorId(), idTag);
        if (ChargePointStatus.Preparing == connector.getStatus()) {
            startTransaction(session, connector, idTag);
        } else {
            connector.setIdTag(idTag);
        }
        return new AuthorizationOutcome(connector.getConnectorId(), true, authorizationStatus);
    }

    public boolean canRemoteStart(ConnectorState connector) {
        return REMOTE_START_ALLOWED_STATUSES.contains(connector.getStatus());
    }

    public void startTransactionAsync(ChargePointSession session, ConnectorState connector, String idTag) {
        executeAsync("RemoteStartTransaction", session, () -> {
            if (connector.hasTransaction()) {
                log.warn("[{}] Connector {} already has transaction {}, remote start ignored.",
                        session.getChargePointId(), connector.getConnectorId(), connector.getTransactionId());
                return;
            }
            Thread.sleep(REMOTE_TRANSACTION_DELAY_MILLIS);
            startTransaction(session, connector, idTag);
        });
    }

    public void stopTransactionAsync(ChargePointSession session, ConnectorState connector,
                                     ChargePointStatus targetStatus, Reason reason) {
        if (!connector.hasTransaction()) {
            log.info("[{}] Connector {} has no transaction, no StopTransaction to send.",
                    session.getChargePointId(), connector.getConnectorId());
            return;
        }
        executeAsync("StopTransaction", session, () -> {
            Thread.sleep(REMOTE_TRANSACTION_DELAY_MILLIS);
            stopTransaction(session, connector, targetStatus, reason);
        });
    }

    public void stopAllTransactionsAsync(ChargePointSession session, ChargePointStatus targetStatus, Reason reason) {
        for (ConnectorState connector : session.getConnectors()) {
            stopTransactionAsync(session, connector, targetStatus, reason);
        }
    }

    private ConnectorState notifyStatus(ChargePointSession session, ConnectorState connector, ChargePointStatus status) {
        requestSender.send(session, messageFactory.statusNotification(connector, status));
        connector.setStatus(status);
        return connector;
    }

    /**
     * Sends StartTransaction and only moves the connector to Charging once the central system
     * confirmed the transaction. On failure the previous status is restored so the connector does not
     * get stuck in an inconsistent state.
     */
    private void startTransaction(ChargePointSession session, ConnectorState connector, String idTag) {
        ChargePointStatus previousStatus = connector.getStatus();
        try {
            StartTransactionConfirmation confirmation = requestSender.sendAndExpect(session,
                    messageFactory.startTransaction(connector, idTag), StartTransactionConfirmation.class);
            if (confirmation.getTransactionId() == null) {
                throw new OcppRequestException("The central system accepted the transaction of charge point '"
                        + session.getChargePointId() + "' without returning a transaction id.");
            }
            connector.startTransaction(confirmation.getTransactionId(), idTag, ChargePointStatus.Charging);
            notifyStatus(session, connector, ChargePointStatus.Charging);
            log.info("[{}] Connector {} started transaction {} for idTag '{}'.",
                    session.getChargePointId(), connector.getConnectorId(), confirmation.getTransactionId(), idTag);
        } catch (RuntimeException e) {
            connector.setStatus(previousStatus);
            throw e;
        }
    }

    private ConnectorState stopTransaction(ChargePointSession session, ConnectorState connector,
                                          ChargePointStatus targetStatus, Reason reason) {
        if (!connector.hasTransaction()) {
            log.warn("[{}] Connector {} has no active transaction, skipping StopTransaction.",
                    session.getChargePointId(), connector.getConnectorId());
            return connector;
        }
        Integer transactionId = connector.getTransactionId();
        requestSender.send(session, messageFactory.stopTransaction(session.getConfig(), connector, reason));
        connector.clearTransaction();
        notifyStatus(session, connector, targetStatus);
        log.info("[{}] Connector {} stopped transaction {} ({}) and is now {}.",
                session.getChargePointId(), connector.getConnectorId(), transactionId, reason, targetStatus);
        return connector;
    }

    /**
     * Runs a background operation and makes sure a failure is never silent: the message is logged with
     * its stack trace and stored on the charge point.
     */
    private void executeAsync(String operation, ChargePointSession session, DelayedOperation delayedOperation) {
        remoteTransactionExecutor.execute(() -> {
            try {
                delayedOperation.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}] {} was interrupted.", session.getChargePointId(), operation);
            } catch (RuntimeException e) {
                session.recordError(operation + " failed: " + e.getMessage());
                log.error("[{}] {} failed, connectors are now [{}].",
                        session.getChargePointId(), operation, session.describeConnectors(), e);
            }
        });
    }

    @FunctionalInterface
    private interface DelayedOperation {
        void run() throws InterruptedException;
    }
}
