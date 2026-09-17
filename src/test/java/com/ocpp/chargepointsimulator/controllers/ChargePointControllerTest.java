package com.ocpp.chargepointsimulator.controllers;

import com.ocpp.chargepointsimulator.controllers.dto.ChargePointRequest;
import com.ocpp.chargepointsimulator.controllers.dto.ChargePointResponse;
import com.ocpp.chargepointsimulator.controllers.dto.ConnectorResponse;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the configuration API. Charge points are registered without connecting, so no central
 * system is needed; operations that need the OCPP connection are asserted to fail with 409.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "simulator.defaults.charge-point-id=")
class ChargePointControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void registersAChargePointWithSeveralConnectors() {
        ChargePointResponse response = register(new ChargePointRequest(
                "CP_TEST_1", "ws://localhost:9999", null, null, 7400, 30, List.of(1, 2), false));

        assertEquals("CP_TEST_1", response.chargePointId());
        assertEquals("ws://localhost:9999/CP_TEST_1", response.webSocketUrl());
        assertEquals(2, response.connectors().size());
        assertEquals(ChargePointStatus.Available, response.connectors().getFirst().status());
        assertEquals(7400, response.chargingPower());
        assertEquals(30, response.meterValuesFrequency());

        remove("CP_TEST_1");
    }

    @Test
    void fallsBackToTheConfiguredDefaults() {
        ChargePointResponse response = register(new ChargePointRequest(
                "CP_TEST_2", null, null, null, null, null, null, false));

        assertEquals("ws://localhost:8080/CP_TEST_2", response.webSocketUrl());
        assertEquals(List.of(1), response.connectors().stream().map(ConnectorResponse::connectorId).toList());
        assertEquals(5000, response.chargingPower());
        assertEquals(60, response.meterValuesFrequency());
        assertFalse(response.authenticated());
        assertNull(response.lastError());

        remove("CP_TEST_2");
    }

    @Test
    void reportsWhenCredentialsAreConfigured() {
        ChargePointResponse response = register(new ChargePointRequest(
                "CP_TEST_3", "wss://csms.example.com/OCPP16", "user", "secret", 11000, 15, List.of(2), false));

        assertTrue(response.authenticated());
        assertEquals("wss://csms.example.com/OCPP16/CP_TEST_3", response.webSocketUrl());

        remove("CP_TEST_3");
    }

    @Test
    void listsAndReadsBackChargePoints() {
        register(new ChargePointRequest("CP_TEST_4", null, null, null, null, null, List.of(1, 2), false));

        ResponseEntity<ChargePointResponse[]> list =
                restTemplate.getForEntity("/api/charge-points", ChargePointResponse[].class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertNotNull(list.getBody());
        assertTrue(Stream.of(list.getBody())
                .anyMatch(chargePoint -> "CP_TEST_4".equals(chargePoint.chargePointId())));

        ResponseEntity<ChargePointResponse> detail =
                restTemplate.getForEntity("/api/charge-points/detail?cpId=CP_TEST_4", ChargePointResponse.class);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertNotNull(detail.getBody());
        assertEquals(2, detail.getBody().connectors().size());

        remove("CP_TEST_4");
    }

    @Test
    void rejectsADuplicateChargePointId() {
        register(new ChargePointRequest("CP_TEST_5", null, null, null, null, null, null, false));

        ResponseEntity<ProblemDetail> duplicate = restTemplate.postForEntity("/api/charge-points",
                new ChargePointRequest("CP_TEST_5", null, null, null, null, null, null, false), ProblemDetail.class);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());

        remove("CP_TEST_5");
    }

    @Test
    void rejectsAnInvalidConfiguration() {
        // charging power of 0 W cannot produce meter values
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity("/api/charge-points",
                new ChargePointRequest("CP_TEST_6", null, null, null, 0, null, null, false), ProblemDetail.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getDetail());
        assertTrue(response.getBody().getDetail().contains("chargingPower"));
    }

    @Test
    void reportsUnknownChargePointsAndConnectors() {
        ResponseEntity<ProblemDetail> unknownChargePoint = restTemplate.getForEntity(
                "/api/charge-points/detail?cpId=CP_MISSING", ProblemDetail.class);
        assertEquals(HttpStatus.NOT_FOUND, unknownChargePoint.getStatusCode());

        register(new ChargePointRequest("CP_TEST_7", null, null, null, null, null, List.of(1), false));
        ResponseEntity<ProblemDetail> unknownConnector = restTemplate.postForEntity(
                "/api/charge-points/connectors/plug-in?cpId=CP_TEST_7&connectorId=9", null, ProblemDetail.class);
        assertEquals(HttpStatus.NOT_FOUND, unknownConnector.getStatusCode());

        remove("CP_TEST_7");
    }

    @Test
    void updatesAChargePointInPlace() {
        register(new ChargePointRequest("CP_TEST_10", "ws://localhost:9999", null, null, 5000, 60, List.of(1), false));

        ResponseEntity<ChargePointResponse> response = restTemplate.exchange(
                "/api/charge-points?cpId=CP_TEST_10", HttpMethod.PUT,
                new HttpEntity<>(new ChargePointRequest("CP_TEST_10", "ws://localhost:9999", null, null,
                        22000, 15, List.of(1, 2), null)),
                ChargePointResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(22000, response.getBody().chargingPower());
        assertEquals(15, response.getBody().meterValuesFrequency());
        assertEquals(List.of(1, 2), response.getBody().connectors().stream()
                .map(ConnectorResponse::connectorId).toList());
        // connect was omitted, so the charge point keeps its current state: still disconnected
        assertFalse(response.getBody().connected());

        remove("CP_TEST_10");
    }

    @Test
    void rejectsAnUpdateThatRenamesAChargePointOrTargetsAnUnknownOne() {
        register(new ChargePointRequest("CP_TEST_11", null, null, null, null, null, null, false));

        ResponseEntity<ProblemDetail> renamed = restTemplate.exchange(
                "/api/charge-points?cpId=CP_TEST_11", HttpMethod.PUT,
                new HttpEntity<>(new ChargePointRequest("CP_OTHER", null, null, null, null, null, null, null)),
                ProblemDetail.class);
        assertEquals(HttpStatus.BAD_REQUEST, renamed.getStatusCode());

        ResponseEntity<ProblemDetail> unknown = restTemplate.exchange(
                "/api/charge-points?cpId=CP_MISSING", HttpMethod.PUT,
                new HttpEntity<>(new ChargePointRequest("CP_MISSING", null, null, null, null, null, null, null)),
                ProblemDetail.class);
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode());

        remove("CP_TEST_11");
    }

    @Test
    void reportsAConflictWhenTheChargePointIsNotConnected() {
        register(new ChargePointRequest("CP_TEST_8", "ws://localhost:9999", null, null, null, null, List.of(1), false));

        // plug-in has to send a StatusNotification to the central system
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/api/charge-points/connectors/plug-in?cpId=CP_TEST_8", null, ProblemDetail.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getDetail());
        assertTrue(response.getBody().getDetail().contains("is not connected"));

        remove("CP_TEST_8");
    }

    @Test
    void removesAChargePoint() {
        register(new ChargePointRequest("CP_TEST_9", null, null, null, null, null, null, false));

        ResponseEntity<Void> delete = restTemplate.exchange(
                "/api/charge-points?cpId=CP_TEST_9", HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, delete.getStatusCode());

        ResponseEntity<ProblemDetail> detail = restTemplate.getForEntity(
                "/api/charge-points/detail?cpId=CP_TEST_9", ProblemDetail.class);
        assertEquals(HttpStatus.NOT_FOUND, detail.getStatusCode());
    }

    private ChargePointResponse register(ChargePointRequest request) {
        ResponseEntity<ChargePointResponse> response =
                restTemplate.postForEntity("/api/charge-points", request, ChargePointResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().connected(),
                "the test registers charge points without connecting them");
        return response.getBody();
    }

    private void remove(String chargePointId) {
        restTemplate.exchange("/api/charge-points?cpId=" + chargePointId, HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
    }
}
