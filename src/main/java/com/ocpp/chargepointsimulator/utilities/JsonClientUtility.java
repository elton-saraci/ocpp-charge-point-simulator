package com.ocpp.chargepointsimulator.utilities;

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
            return null;
        }
    }

}
