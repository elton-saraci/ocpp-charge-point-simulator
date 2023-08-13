package com.ocpp.chargepointsimulator.services;

import com.ocpp.chargepointsimulator.configurations.ChargePointConfiguration;
import com.ocpp.chargepointsimulator.factories.MessageRequestFactory;
import com.ocpp.chargepointsimulator.utilities.JsonClientUtility;
import eu.chargetime.ocpp.model.core.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@AllArgsConstructor
public class ChargePointService {

    private final JsonClientUtility jsonClientUtility;
    private final ChargePointConfiguration chargePointConfiguration;
    private final MessageRequestFactory messageRequestFactory;

    public void plugTheChargerIn() {
        try {
            if (chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Available)
                    && chargePointConfiguration.getIdTag() != null) {
                chargePointConfiguration.setChargePointStatus(ChargePointStatus.Charging);
                StartTransactionConfirmation startTransactionConfirmation = (StartTransactionConfirmation) jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createStartTransactionRequest(chargePointConfiguration.getIdTag()));
                chargePointConfiguration.setTransactionId(startTransactionConfirmation.getTransactionId());
                Thread.sleep(500);
                jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createStatusNotification());
            } else {
                chargePointConfiguration.setChargePointStatus(ChargePointStatus.Preparing);
                jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createStatusNotification());
            }
        } catch (Exception e) {
            log.error("Error happened while plugging in the charger, error message: {}", e.getLocalizedMessage());
        }
    }

    public void triggerAuthorizationRequest(String idTag) {
        try {
            AuthorizeConfirmation authorizeConfirmation = (AuthorizeConfirmation) jsonClientUtility
                    .sendJsonClientRequest(new AuthorizeRequest(idTag));
            if(authorizeConfirmation.getIdTagInfo().getStatus().equals(AuthorizationStatus.Accepted)) {
                log.info("AuthorizeRequest accepted by the server.");
                conductChargePointStatusTransitions(idTag);
            } else {
                log.warn("The server rejected the AuthorizationRequest.");
            }
        } catch (Exception e) {
            log.error("Error happened while handling authorization request for rfid use-case, error message: " + e.getLocalizedMessage());
        }
    }

    private void conductChargePointStatusTransitions(String idTag) throws InterruptedException {
        if(chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Preparing)) {
            jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createStartTransactionRequest(idTag));
            Thread.sleep(500);
            jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createStatusNotification());
        }
    }

    public void plugOutTheCharger() {
        try {
            if (chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Preparing)) {
                chargePointConfiguration.setChargePointStatus(ChargePointStatus.Available);
                jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createStatusNotification());
            } else if (chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Charging)) {
                chargePointConfiguration.setChargePointStatus(ChargePointStatus.Available);
                jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createStopTransactionRequest());
                Thread.sleep(500);
                jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createStatusNotification());
                chargePointConfiguration.setTransactionId(null);
                chargePointConfiguration.setIdTag(null);
            }
        } catch (Exception e) {
            log.error("Error happened while plugging out the charger, error message: {}", e.getLocalizedMessage());
        }
    }

}
