package com.ocpp.chargepointsimulator.schedulers;

import com.ocpp.chargepointsimulator.configurations.ChargePointConfiguration;
import com.ocpp.chargepointsimulator.factories.MessageRequestFactory;
import com.ocpp.chargepointsimulator.utilities.JsonClientUtility;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.HeartbeatRequest;
import lombok.AllArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class MeterValuesScheduler {

    private final ChargePointConfiguration chargePointConfiguration;
    private final JsonClientUtility jsonClientUtility;
    private final MessageRequestFactory messageRequestFactory;

    @Scheduled(fixedDelay = 15_000)
    public void generateMeterValues() {
        if(chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Charging)) {
            jsonClientUtility.sendJsonClientRequest(messageRequestFactory.createMeterValuesRequest());
        }
        if(chargePointConfiguration.isChargePointConnected()) {
            jsonClientUtility.sendJsonClientRequest(new HeartbeatRequest());
        }
    }

}
