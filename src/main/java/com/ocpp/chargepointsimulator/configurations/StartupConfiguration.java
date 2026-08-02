package com.ocpp.chargepointsimulator.configurations;

import com.ocpp.chargepointsimulator.factories.MessageRequestFactory;
import eu.chargetime.ocpp.ClientEvents;
import eu.chargetime.ocpp.JSONClient;
import eu.chargetime.ocpp.feature.profile.ClientCoreProfile;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@AllArgsConstructor
@Slf4j
@Import({ClientCoreProfile.class, ClientCoreEventConfiguration.class, String.class})
public class StartupConfiguration implements ApplicationRunner {

    private static final ScheduledExecutorService bootNotificationExecutor = Executors.newSingleThreadScheduledExecutor();

    private final ChargePointConfiguration chargePointConfiguration;
    private final JSONClient jsonClient;
    private final MessageRequestFactory messageRequestFactory;
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
        jsonClient.connect(websocketUrl, new ClientEvents() {
            @Override
            public void connectionOpened() {
                log.info("WebSocket connection opened to the OCPP central system: {}", websocketUrl);
                // Give the OCPP session a moment to mark the connection as established
                // before sending the BootNotification, to avoid a 'Not connected' race.
                bootNotificationExecutor.schedule(() -> {
                    try {
                        log.info("Sending BootNotification to the OCPP central system.");
                        jsonClient.send(messageRequestFactory.createBootNotification());
                    } catch (Exception e) {
                        log.error("Error occurred while sending BootNotification: {}", e.getLocalizedMessage());
                    }
                }, 1, TimeUnit.SECONDS);
            }

            @Override
            public void connectionClosed() {
                log.warn("WebSocket connection closed to the OCPP central system.");
            }
        });
    }

}
