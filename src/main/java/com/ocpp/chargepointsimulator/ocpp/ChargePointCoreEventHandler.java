package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.services.TransactionService;
import eu.chargetime.ocpp.feature.profile.ClientCoreEventHandler;
import eu.chargetime.ocpp.model.core.AvailabilityStatus;
import eu.chargetime.ocpp.model.core.AvailabilityType;
import eu.chargetime.ocpp.model.core.ChangeAvailabilityConfirmation;
import eu.chargetime.ocpp.model.core.ChangeAvailabilityRequest;
import eu.chargetime.ocpp.model.core.ChangeConfigurationConfirmation;
import eu.chargetime.ocpp.model.core.ChangeConfigurationRequest;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.ClearCacheConfirmation;
import eu.chargetime.ocpp.model.core.ClearCacheRequest;
import eu.chargetime.ocpp.model.core.ClearCacheStatus;
import eu.chargetime.ocpp.model.core.DataTransferConfirmation;
import eu.chargetime.ocpp.model.core.DataTransferRequest;
import eu.chargetime.ocpp.model.core.GetConfigurationConfirmation;
import eu.chargetime.ocpp.model.core.GetConfigurationRequest;
import eu.chargetime.ocpp.model.core.Reason;
import eu.chargetime.ocpp.model.core.RemoteStartStopStatus;
import eu.chargetime.ocpp.model.core.RemoteStartTransactionConfirmation;
import eu.chargetime.ocpp.model.core.RemoteStartTransactionRequest;
import eu.chargetime.ocpp.model.core.RemoteStopTransactionConfirmation;
import eu.chargetime.ocpp.model.core.RemoteStopTransactionRequest;
import eu.chargetime.ocpp.model.core.ResetConfirmation;
import eu.chargetime.ocpp.model.core.ResetRequest;
import eu.chargetime.ocpp.model.core.ResetStatus;
import eu.chargetime.ocpp.model.core.ResetType;
import eu.chargetime.ocpp.model.core.UnlockConnectorConfirmation;
import eu.chargetime.ocpp.model.core.UnlockConnectorRequest;
import eu.chargetime.ocpp.model.core.UnlockStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Handles the requests a central system sends to one specific charge point.
 *
 * <p>One instance exists per charge point session, which is what makes multiple charge points with
 * independent connectors possible: the old implementation handled every request against a single
 * global configuration.
 *
 * <p>Returning {@code null} from a handler is the documented way of the OCPP library to answer a call
 * with a {@code NotSupported} call error; it is used for the features this simulator does not
 * implement.
 */
@Slf4j
public class ChargePointCoreEventHandler implements ClientCoreEventHandler {

    private final ChargePointSession session;
    private final TransactionService transactionService;

    public ChargePointCoreEventHandler(ChargePointSession session, TransactionService transactionService) {
        this.session = session;
        this.transactionService = transactionService;
    }

    @Override
    public ChangeAvailabilityConfirmation handleChangeAvailabilityRequest(ChangeAvailabilityRequest request) {
        log.info("[{}] Incoming ChangeAvailabilityRequest -> {}", session.getChargePointId(), request);
        ChargePointStatus requestedStatus = AvailabilityType.Operative == request.getType()
                ? ChargePointStatus.Available
                : ChargePointStatus.Unavailable;
        List<ConnectorState> connectors = request.getConnectorId() <= 0
                ? List.copyOf(session.getConnectors())
                : session.findConnector(request.getConnectorId()).map(List::of).orElse(List.of());

        if (connectors.isEmpty()) {
            log.warn("[{}] ChangeAvailabilityRequest rejected, connector {} is not exposed. Configured connectors: {}",
                    session.getChargePointId(), request.getConnectorId(), session.getConfig().connectorIds());
            return new ChangeAvailabilityConfirmation(AvailabilityStatus.Rejected);
        }
        connectors.forEach(connector -> connector.setStatus(requestedStatus));
        log.info("[{}] Connectors {} are now {}.", session.getChargePointId(),
                connectors.stream().map(ConnectorState::getConnectorId).toList(), requestedStatus);
        return new ChangeAvailabilityConfirmation(AvailabilityStatus.Accepted);
    }

    @Override
    public GetConfigurationConfirmation handleGetConfigurationRequest(GetConfigurationRequest request) {
        log.info("[{}] Incoming GetConfigurationRequest -> {}", session.getChargePointId(), request);
        // Not implemented: answering with null makes the library reply with a NotSupported call error.
        return null;
    }

    @Override
    public ChangeConfigurationConfirmation handleChangeConfigurationRequest(ChangeConfigurationRequest request) {
        log.info("[{}] Incoming ChangeConfigurationRequest -> {}", session.getChargePointId(), request);
        // Not implemented: answering with null makes the library reply with a NotSupported call error.
        return null;
    }

    @Override
    public ClearCacheConfirmation handleClearCacheRequest(ClearCacheRequest request) {
        log.info("[{}] Incoming ClearCacheRequest -> {}", session.getChargePointId(), request);
        // Accepted as a mock response, the simulator keeps no cached authorization data.
        return new ClearCacheConfirmation(ClearCacheStatus.Accepted);
    }

    @Override
    public DataTransferConfirmation handleDataTransferRequest(DataTransferRequest request) {
        log.info("[{}] Incoming DataTransferRequest -> {}", session.getChargePointId(), request);
        // Not implemented: answering with null makes the library reply with a NotSupported call error.
        return null;
    }

    @Override
    public RemoteStartTransactionConfirmation handleRemoteStartTransactionRequest(RemoteStartTransactionRequest request) {
        log.info("[{}] Incoming RemoteStartTransactionRequest -> {}", session.getChargePointId(), request);
        Optional<ConnectorState> connector = resolveConnector(request.getConnectorId());
        if (connector.isEmpty()) {
            log.warn("[{}] RemoteStartTransactionRequest rejected, connector {} is not exposed.",
                    session.getChargePointId(), request.getConnectorId());
            return new RemoteStartTransactionConfirmation(RemoteStartStopStatus.Rejected);
        }
        if (!transactionService.canRemoteStart(connector.get())) {
            log.warn("[{}] RemoteStartTransactionRequest rejected, connector {} is {}.",
                    session.getChargePointId(), connector.get().getConnectorId(), connector.get().getStatus());
            return new RemoteStartTransactionConfirmation(RemoteStartStopStatus.Rejected);
        }
        transactionService.startTransactionAsync(session, connector.get(), request.getIdTag());
        return new RemoteStartTransactionConfirmation(RemoteStartStopStatus.Accepted);
    }

    @Override
    public RemoteStopTransactionConfirmation handleRemoteStopTransactionRequest(RemoteStopTransactionRequest request) {
        log.info("[{}] Incoming RemoteStopTransactionRequest -> {}", session.getChargePointId(), request);
        Optional<ConnectorState> connector = session.findConnectorByTransactionId(request.getTransactionId());
        if (connector.isEmpty()) {
            log.warn("[{}] RemoteStopTransactionRequest rejected, transaction {} is unknown.",
                    session.getChargePointId(), request.getTransactionId());
            return new RemoteStopTransactionConfirmation(RemoteStartStopStatus.Rejected);
        }
        if (!connector.get().isCharging()) {
            log.warn("[{}] RemoteStopTransactionRequest rejected, connector {} is {}.",
                    session.getChargePointId(), connector.get().getConnectorId(), connector.get().getStatus());
            return new RemoteStopTransactionConfirmation(RemoteStartStopStatus.Rejected);
        }
        transactionService.stopTransactionAsync(session, connector.get(),
                ChargePointStatus.Finishing, Reason.Remote);
        return new RemoteStopTransactionConfirmation(RemoteStartStopStatus.Accepted);
    }

    @Override
    public ResetConfirmation handleResetRequest(ResetRequest request) {
        log.info("[{}] Incoming ResetRequest -> {}", session.getChargePointId(), request);
        Reason reason = ResetType.Soft == request.getType() ? Reason.SoftReset : Reason.HardReset;
        transactionService.stopAllTransactionsAsync(session, ChargePointStatus.Available, reason);
        return new ResetConfirmation(ResetStatus.Accepted);
    }

    @Override
    public UnlockConnectorConfirmation handleUnlockConnectorRequest(UnlockConnectorRequest request) {
        log.info("[{}] Incoming UnlockConnectorRequest -> {}", session.getChargePointId(), request);
        Optional<ConnectorState> connector = resolveConnector(request.getConnectorId());
        if (connector.isEmpty()) {
            log.warn("[{}] UnlockConnectorRequest is not supported for connector {}.",
                    session.getChargePointId(), request.getConnectorId());
            return new UnlockConnectorConfirmation(UnlockStatus.NotSupported);
        }
        transactionService.stopTransactionAsync(session, connector.get(),
                ChargePointStatus.Available, Reason.UnlockCommand);
        return new UnlockConnectorConfirmation(UnlockStatus.Unlocked);
    }

    /**
     * Resolves the connector a request targets. A missing or zero connector id means "the charge
     * point decides", then the default connector is picked.
     */
    private Optional<ConnectorState> resolveConnector(Integer connectorId) {
        if (connectorId == null || connectorId <= 0) {
            return Optional.of(transactionService.selectDefaultConnector(session));
        }
        return session.findConnector(connectorId);
    }
}
