package com.ocpp.chargepointsimulator.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console is a single page, so the control room of a charge point needs a route of its own: a
 * reload or a bookmark must reach the console instead of a 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleRouteControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void servesTheConsoleForTheControlRoomOfAChargePoint() {
        ResponseEntity<String> response = restTemplate.getForEntity("/station/CP_SIM_001", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(console(response), "the console itself is expected, not the API");
    }

    @Test
    void servesTheConsoleWithoutAChargePointInTheRoute() {
        ResponseEntity<String> response = restTemplate.getForEntity("/station", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(console(response), "the console itself is expected, not the API");
    }

    @Test
    void doesNotShadowTheConsoleItsAssetsOrTheApi() {
        assertEquals(HttpStatus.OK, restTemplate.getForEntity("/", String.class).getStatusCode());
        assertEquals(HttpStatus.OK, restTemplate.getForEntity("/index.html", String.class).getStatusCode());
        assertEquals(HttpStatus.OK, restTemplate.getForEntity("/js/app.js", String.class).getStatusCode());
        assertEquals(HttpStatus.OK, restTemplate.getForEntity("/api/charge-points", String.class).getStatusCode());
    }

    private static boolean console(ResponseEntity<String> response) {
        return response.getBody() != null && response.getBody().contains("Chargomate · Fleet Console");
    }
}
