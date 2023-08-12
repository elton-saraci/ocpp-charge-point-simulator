package com.ocpp.chargepointsimulator.configurations;

import eu.chargetime.ocpp.model.core.ChargePointStatus;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@Data
public class ChargePointConfiguration {

    private String centralSystemUrl;
    private int connectorId ;
    private String chargePointId;
    private ChargePointStatus chargePointStatus = ChargePointStatus.Available;
    private int currentMeterValue = 0;
    private int meterValuesStep = 200;
    private int chargingPower;
    private int meterValuesFrequency;
    private String idTag;
    private Integer transactionId;
    private boolean isChargePointConnected = false;


    public void initializeChargePointConfigurations (
            String centralSystemUrl,
            int connectorId,
            String chargePointId,
            int meterValuesStep,
            int meterValuesFrequency,
            int chargingPower
    ) {
        this.centralSystemUrl = centralSystemUrl;
        this.connectorId = connectorId;
        this.chargePointId = chargePointId;
        this.meterValuesStep = meterValuesStep;
        this.meterValuesFrequency = meterValuesFrequency;
        this.chargingPower = chargingPower;
    }

}
