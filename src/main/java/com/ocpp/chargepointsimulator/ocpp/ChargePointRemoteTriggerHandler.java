package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.exceptions.ConnectorNotFoundException;
import eu.chargetime.ocpp.feature.profile.ClientRemoteTriggerEventHandler;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageConfirmation;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageRequest;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/** Handles TriggerMessage requests sent by the central system to one charge point session. */
@Slf4j
public class ChargePointRemoteTriggerHandler implements ClientRemoteTriggerEventHandler {

    private final ChargePointSession session;
    private final OcppMessageFactory messageFactory;
    private final OcppRequestSender requestSender;

    public ChargePointRemoteTriggerHandler(ChargePointSession session,
                                           OcppMessageFactory messageFactory,
                                           OcppRequestSender requestSender) {
        this.session = session;
        this.messageFactory = messageFactory;
        this.requestSender = requestSender;
    }

    @Override
    public TriggerMessageConfirmation handleTriggerMessageRequest(TriggerMessageRequest request) {
        log.info("[{}] Incoming TriggerMessageRequest -> {}", session.getChargePointId(), request);
        try {
            switch (request.getRequestedMessage()) {
                case BootNotification -> requestSender.send(session,
                        messageFactory.bootNotification(session.getConfig()));
                case StatusNotification -> sendStatusNotifications(request.getConnectorId());
                case MeterValues -> sendMeterValues(request.getConnectorId());
                case Heartbeat -> requestSender.send(session, messageFactory.heartbeat());
                default -> {
                    log.warn("[{}] Requested message {} is not implemented, rejecting the trigger.",
                            session.getChargePointId(), request.getRequestedMessage());
                    return new TriggerMessageConfirmation(TriggerMessageStatus.Rejected);
                }
            }
            return new TriggerMessageConfirmation(TriggerMessageStatus.Accepted);
        } catch (RuntimeException e) {
            session.recordError("TriggerMessage " + request.getRequestedMessage() + " failed: " + e.getMessage());
            log.error("[{}] TriggerMessage {} failed.", session.getChargePointId(),
                    request.getRequestedMessage(), e);
            return new TriggerMessageConfirmation(TriggerMessageStatus.Rejected);
        }
    }

    private void sendStatusNotifications(Integer requestedConnectorId) {
        for (ConnectorState connector : targetConnectors(requestedConnectorId)) {
            requestSender.send(session, messageFactory.statusNotification(connector, connector.getStatus()));
        }
    }

    private void sendMeterValues(Integer requestedConnectorId) {
        List<ConnectorState> chargingConnectors = targetConnectors(requestedConnectorId).stream()
                .filter(ConnectorState::isCharging)
                .toList();
        if (chargingConnectors.isEmpty()) {
            log.info("[{}] No charging connector, no MeterValues to trigger.", session.getChargePointId());
            return;
        }
        for (ConnectorState connector : chargingConnectors) {
            connector.markMeterValuesSent(System.currentTimeMillis());
            requestSender.send(session, messageFactory.meterValues(session.getConfig(), connector));
        }
    }

    /** A trigger without a connector id applies to every connector of the charge point. */
    private List<ConnectorState> targetConnectors(Integer requestedConnectorId) {
        if (requestedConnectorId == null || requestedConnectorId <= 0) {
            return List.copyOf(session.getConnectors());
        }
        Optional<ConnectorState> connector = session.findConnector(requestedConnectorId);
        if (connector.isEmpty()) {
            throw new ConnectorNotFoundException("Charge point '" + session.getChargePointId()
                    + "' has no connector " + requestedConnectorId + ", configured connectors are "
                    + session.getConfig().connectorIds() + ".");
        }
        return List.of(connector.get());
    }
}
