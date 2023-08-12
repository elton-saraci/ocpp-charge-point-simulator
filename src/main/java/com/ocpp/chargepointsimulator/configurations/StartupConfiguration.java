package com.ocpp.chargepointsimulator.configurations;

import eu.chargetime.ocpp.JSONClient;
import eu.chargetime.ocpp.feature.profile.ClientCoreProfile;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
@Import({ClientCoreProfile.class, ClientCoreEventConfiguration.class, String.class})
public class StartupConfiguration implements ApplicationRunner {

    private final ChargePointConfiguration chargePointConfiguration;
    private final JSONClient jsonClient;
    @Value("${central-system-url}")
    private String centralSystemUrl;
    @Value("${connector-id}")
    private String connectorId;
    @Value("${charge-point-id}")
    private String chargePointId;
    @Value("${meter-values.step}")
    private String meterValuesStep;
    @Value("${meter-values.frequency}")
    private String meterValuesFrequency;
    @Value("${charging-power}")
    private String chargingPower;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Connecting the simulator to the OCPP server.");
        chargePointConfiguration.initializeChargePointConfigurations(
                centralSystemUrl,
                Integer.parseInt(connectorId),
                chargePointId,
                Integer.parseInt(meterValuesStep),
                Integer.parseInt(meterValuesFrequency),
                Integer.parseInt(chargingPower));
        String websocketUrl = chargePointConfiguration.getCentralSystemUrl() + "/" + chargePointConfiguration.getChargePointId();
        chargePointConfiguration.setChargePointConnected(true);
        jsonClient.connect(websocketUrl, null);
    }

}
