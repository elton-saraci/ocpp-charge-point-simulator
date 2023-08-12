package com.ocpp.chargepointsimulator.schedulers;

import com.ocpp.chargepointsimulator.configurations.ChargePointConfiguration;
import com.ocpp.chargepointsimulator.factories.MessageRequestFactory;
import com.ocpp.chargepointsimulator.utilities.JsonClientUtility;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.HeartbeatRequest;
import eu.chargetime.ocpp.model.core.MeterValue;
import eu.chargetime.ocpp.model.core.MeterValuesRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Slf4j
@AllArgsConstructor
public class MeterValuesScheduler {

    private final ChargePointConfiguration chargePointConfiguration;
    private final JsonClientUtility jsonClientUtility;
    private final MessageRequestFactory messageRequestFactory;

    @Scheduled(fixedDelay = 60_000)
    public void generateMeterValues() {
        if(chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Charging)) {
            MeterValuesRequest meterValuesRequest = new MeterValuesRequest();
            meterValuesRequest.setConnectorId(1);
            meterValuesRequest.setTransactionId(chargePointConfiguration.getTransactionId());
            meterValuesRequest.setMeterValue(new MeterValue[]{messageRequestFactory.meterValue()});
            jsonClientUtility.sendJsonClientRequest(meterValuesRequest);
        }
        if(chargePointConfiguration.isChargePointConnected()) {
            jsonClientUtility.sendJsonClientRequest(new HeartbeatRequest());
        }
    }

}
