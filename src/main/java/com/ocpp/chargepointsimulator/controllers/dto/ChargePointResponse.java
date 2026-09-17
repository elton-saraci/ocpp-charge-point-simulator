package com.ocpp.chargepointsimulator.controllers.dto;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import lombok.Builder;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Current state of a simulated charge point. Credentials are never returned, only whether they are
 * configured.
 *
 * <p>Built through {@link #builder()}: with this many fields a positional constructor is easy to get
 * wrong, named arguments are not.
 *
 * @param connected            whether the OCPP WebSocket connection is up
 * @param authenticated        whether HTTP Basic credentials are configured for the connection
 * @param connectors           state of every configured connector
 * @param lastError            last failure that could not be reported to a caller, {@code null} when clear
 * @param lastErrorAt          when that failure happened
 */
@Builder
public record ChargePointResponse(
        String chargePointId,
        String centralSystemUrl,
        String webSocketUrl,
        boolean connected,
        boolean authenticated,
        int chargingPower,
        int meterValuesFrequency,
        List<ConnectorResponse> connectors,
        String lastError,
        Instant lastErrorAt) {

    public static ChargePointResponse from(ChargePointSession session) {
        ChargePointConfig config = session.getConfig();
        return ChargePointResponse.builder()
                .chargePointId(session.getChargePointId())
                .centralSystemUrl(config.centralSystemUrl())
                .webSocketUrl(session.webSocketUrl())
                .connected(session.isConnected())
                .authenticated(config.hasCredentials())
                .chargingPower(config.chargingPower())
                .meterValuesFrequency(config.meterValuesFrequency())
                .connectors(session.getConnectors().stream().map(ConnectorResponse::from).toList())
                .lastError(session.getLastError().orElse(null))
                .lastErrorAt(session.getLastErrorAt().orElse(null))
                .build();
    }

    public static List<ChargePointResponse> from(Collection<ChargePointSession> sessions) {
        return sessions.stream().map(ChargePointResponse::from).toList();
    }
}
