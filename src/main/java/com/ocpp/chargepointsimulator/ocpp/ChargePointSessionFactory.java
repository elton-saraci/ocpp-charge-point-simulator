package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException;
import com.ocpp.chargepointsimulator.services.TransactionService;
import eu.chargetime.ocpp.JSONClient;
import eu.chargetime.ocpp.JSONConfiguration;
import eu.chargetime.ocpp.feature.profile.ClientCoreProfile;
import eu.chargetime.ocpp.feature.profile.ClientRemoteTriggerProfile;
import eu.chargetime.ocpp.feature.profile.ClientSmartChargingProfile;
import eu.chargetime.ocpp.wss.BaseWssSocketBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

/**
 * Builds a ready to connect charge point session: its own {@link JSONClient}, its own event handlers
 * and, when credentials are configured, HTTP Basic authentication for the OCPP handshake.
 *
 * <p>Authentication uses the built-in support of the OCPP library: {@code USERNAME} and
 * {@code PASSWORD} in the {@link JSONConfiguration} are turned into the {@code Authorization: Basic}
 * header of the WebSocket handshake. The parameters are read when {@code connect} is called, so each
 * charge point can use its own credentials.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChargePointSessionFactory {

    private final OcppMessageFactory messageFactory;
    private final OcppRequestSender requestSender;
    private final TransactionService transactionService;

    public ChargePointSession create(ChargePointConfig config) {
        ChargePointSession session = new ChargePointSession(config);

        ClientCoreProfile coreProfile =
                new ClientCoreProfile(new ChargePointCoreEventHandler(session, transactionService));
        ClientRemoteTriggerProfile remoteTriggerProfile = new ClientRemoteTriggerProfile(
                new ChargePointRemoteTriggerHandler(session, messageFactory, requestSender));
        ClientSmartChargingProfile smartChargingProfile =
                new ClientSmartChargingProfile(new ChargePointSmartChargingHandler(session));

        JSONClient jsonClient = new JSONClient(coreProfile, null, jsonConfiguration(config));
        jsonClient.addFeatureProfile(remoteTriggerProfile);
        jsonClient.addFeatureProfile(smartChargingProfile);
        // wss:// needs a socket builder, the library refuses the scheme otherwise.
        if (config.usesSecureWebSocket()) {
            enableSecureWebSocket(jsonClient);
        }

        session.attachClient(jsonClient);
        log.info("Created charge point '{}' with connectors {} targeting {}{}.",
                config.chargePointId(), config.connectorIds(), config.webSocketUrl(),
                config.hasCredentials() ? " using HTTP Basic auth user '" + config.username() + "'" : "");
        return session;
    }

    private JSONConfiguration jsonConfiguration(ChargePointConfig config) {
        JSONConfiguration configuration = JSONConfiguration.get();
        if (config.hasCredentials()) {
            configuration.setParameter(JSONConfiguration.USERNAME_PARAMETER, config.username());
            configuration.setParameter(JSONConfiguration.PASSWORD_PARAMETER, config.password());
        }
        return configuration;
    }

    private void enableSecureWebSocket(JSONClient jsonClient) {
        try {
            jsonClient.enableWSS(BaseWssSocketBuilder.builder()
                    .sslSocketFactory(SSLContext.getDefault().getSocketFactory()));
        } catch (NoSuchAlgorithmException | IllegalStateException e) {
            throw new InvalidChargePointConfigException(
                    "Could not enable wss:// support for the OCPP client.", e);
        }
    }
}
