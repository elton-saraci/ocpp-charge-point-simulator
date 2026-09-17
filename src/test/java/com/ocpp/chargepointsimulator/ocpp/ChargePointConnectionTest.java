package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.registry.ChargePointRegistry;
import com.ocpp.chargepointsimulator.services.ChargePointService;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Brings up a minimal WebSocket endpoint and lets real charge point sessions dial it, so the
 * connection handling, the parallel operation of several charge points and the HTTP Basic
 * credentials of the OCPP handshake are verified against a real socket.
 */
@SpringBootTest(properties = "simulator.defaults.charge-point-id=")
class ChargePointConnectionTest {

    private static final int AWAIT_TIMEOUT_SECONDS = 10;

    @Autowired
    private ChargePointService chargePointService;

    @Autowired
    private ChargePointRegistry registry;

    private RecordingWebSocketServer centralSystem;
    private int centralSystemPort;

    @BeforeEach
    void startCentralSystem() throws IOException, InterruptedException {
        centralSystemPort = freePort();
        centralSystem = new RecordingWebSocketServer(centralSystemPort);
        centralSystem.startInBackground();
        awaitListening(centralSystemPort);
    }

    @AfterEach
    void stopCentralSystem() throws InterruptedException {
        registry.all().forEach(session -> chargePointService.remove(session.getChargePointId()));
        centralSystem.stop();
    }

    @Test
    void connectsSeveralChargePointsInParallel() {
        chargePointService.register(config("CP_CONN_1", null, null, List.of(1, 2)), true);
        chargePointService.register(config("CP_CONN_2", null, null, List.of(1)), true);

        await(() -> registry.get("CP_CONN_1").isConnected() && registry.get("CP_CONN_2").isConnected());

        // Both charge points share one simulator instance, and each sends its own BootNotification.
        await(() -> bootNotifications().size() >= 2);
        List<String> bootNotifications = bootNotifications();
        assertTrue(bootNotifications.stream().anyMatch(message -> message.contains("CP_CONN_1")),
                "expected a BootNotification for CP_CONN_1 in " + bootNotifications);
        assertTrue(bootNotifications.stream().anyMatch(message -> message.contains("CP_CONN_2")),
                "expected a BootNotification for CP_CONN_2 in " + bootNotifications);

        // Connectors are kept per charge point.
        assertEquals(2, registry.get("CP_CONN_1").getConnectors().size());
        assertEquals(1, registry.get("CP_CONN_2").getConnectors().size());
    }

    @Test
    void sendsHttpBasicCredentialsWhenTheyAreConfigured() {
        chargePointService.register(config("CP_CONN_AUTH", "user", "secret", List.of(1)), true);

        await(() -> !centralSystem.authorizationHeaders.isEmpty());

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, centralSystem.authorizationHeaders.getFirst());
    }

    @Test
    void doesNotSendCredentialsWhenNoneAreConfigured() {
        ChargePointSession session = chargePointService.register(config("CP_CONN_ANON", null, null, List.of(1)), true);

        await(() -> !centralSystem.authorizationHeaders.isEmpty());

        assertNull(centralSystem.authorizationHeaders.getFirst(),
                "expected the OCPP handshake without an Authorization header");
        assertTrue(session.isConnected());
    }

    private List<String> bootNotifications() {
        return centralSystem.messages.stream()
                .filter(message -> message.contains("BootNotification"))
                .toList();
    }

    @Test
    void reportsTheConnectionStateOfEachChargePoint() {
        ChargePointSession session = chargePointService.register(config("CP_CONN_STATE", null, null, List.of(1)), true);

        await(session::isConnected);
        assertTrue(session.isConnected());

        chargePointService.disconnect("CP_CONN_STATE");
        assertFalse(session.isConnected());
    }

    private ChargePointConfig config(String chargePointId, String username, String password, List<Integer> connectorIds) {
        return new ChargePointConfig(chargePointId, "ws://localhost:" + centralSystemPort,
                username, password, 5000, 60, connectorIds);
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the expected state.", e);
            }
        }
        throw new AssertionError("Condition was not met within " + AWAIT_TIMEOUT_SECONDS + " seconds.");
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitListening(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (Socket _ = new Socket("localhost", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("The test central system did not start listening on port " + port + ".");
    }

    /** Minimal OCPP endpoint: records what charge points send and how they authenticate. */
    private static final class RecordingWebSocketServer extends WebSocketServer {

        private final List<String> messages = new CopyOnWriteArrayList<>();
        /** Authorization header of every handshake, {@code null} when the client sent none. */
        private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

        private RecordingWebSocketServer(int port) {
            // The OCPP client offers the ocpp1.6 subprotocol, the server has to accept it.
            // Second parameter is the decoder count and has to be at least 1.
            super(new InetSocketAddress("localhost", port), 1, List.of(
                    new Draft_6455(Collections.emptyList(), List.of(new Protocol("ocpp1.6")))));
        }

        private void startInBackground() {
            Thread thread = new Thread(this::start, "test-central-system");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void onOpen(WebSocket webSocket, ClientHandshake handshake) {
            // HandshakeImpl1Server returns an empty string for headers that were not sent.
            String authorization = handshake.getFieldValue("Authorization");
            authorizationHeaders.add(authorization == null || authorization.isEmpty() ? null : authorization);
        }

        @Override
        public void onClose(WebSocket webSocket, int code, String reason, boolean remote) {
            // nothing to record
        }

        @Override
        public void onMessage(WebSocket webSocket, String message) {
            messages.add(message);
        }

        @Override
        public void onError(WebSocket webSocket, Exception ex) {
            // connection level errors are irrelevant for these assertions
        }

        @Override
        public void onStart() {
            // nothing to do
        }
    }
}
