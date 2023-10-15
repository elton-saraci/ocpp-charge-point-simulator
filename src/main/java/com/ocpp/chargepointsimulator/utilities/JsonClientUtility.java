package com.ocpp.chargepointsimulator.utilities;

import com.ocpp.chargepointsimulator.configurations.ChargePointConfiguration;
import eu.chargetime.ocpp.JSONClient;
import eu.chargetime.ocpp.OccurenceConstraintException;
import eu.chargetime.ocpp.UnsupportedFeatureException;
import eu.chargetime.ocpp.model.Confirmation;
import eu.chargetime.ocpp.model.Request;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@AllArgsConstructor
@Component
public class JsonClientUtility {

    private final JSONClient jsonClient;
    private final ChargePointConfiguration chargePointConfiguration;

    public Confirmation sendJsonClientRequest(Request request) {
        try {
            log.info("Triggering JsonClientRequest -> {}", request);
            return jsonClient.send(request).toCompletableFuture().get();
        } catch (OccurenceConstraintException e) {
            log.error("OccurrenceConstraintException occurred while sending request -> {}", e.getLocalizedMessage());
            return null;
        } catch (UnsupportedFeatureException e) {
            log.error("UnsupportedFeatureException occurred while sending request -> {}", e.getLocalizedMessage());
            return null;
        } catch (Exception e) {
            log.error("Unknown error occurred while sending request -> {}", e.getLocalizedMessage());
            retryServerConnection();
            return null;
        }
    }

    private void retryServerConnection() {
        log.info("Retrying connection request to the server.");
        jsonClient.connect(chargePointConfiguration.getCentralSystemUrl() + "/" + chargePointConfiguration.getChargePointId(), null);
    }

}
