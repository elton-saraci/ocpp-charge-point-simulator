package com.ocpp.chargepointsimulator.controllers;

import com.ocpp.chargepointsimulator.configurations.SimulatorDefaults;
import com.ocpp.chargepointsimulator.controllers.dto.AuthorizationResponse;
import com.ocpp.chargepointsimulator.controllers.dto.ChargePointRequest;
import com.ocpp.chargepointsimulator.controllers.dto.ChargePointResponse;
import com.ocpp.chargepointsimulator.controllers.dto.ConnectorResponse;
import com.ocpp.chargepointsimulator.domain.AuthorizationOutcome;
import com.ocpp.chargepointsimulator.services.ChargePointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API to create and drive the simulated charge points.
 *
 * <p>Charge points are addressed with the {@code cpId} request parameter and their connectors with the
 * optional {@code connectorId} parameter. When {@code connectorId} is omitted the charge point picks
 * an {@code Available} connector, which falls back to the first one.
 */
@RestController
@RequestMapping("/api/charge-points")
@Slf4j
@RequiredArgsConstructor
public class ChargePointController {

    private final ChargePointService chargePointService;
    private final SimulatorDefaults defaults;

    /** Registers a new charge point and connects it to the central system. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChargePointResponse register(@RequestBody ChargePointRequest request) {
        log.info("Incoming request to register charge point '{}'.", request.chargePointId());
        return ChargePointResponse.from(chargePointService.register(request.toConfig(defaults), request.shouldConnect()));
    }

    @GetMapping
    public List<ChargePointResponse> list() {
        return ChargePointResponse.from(chargePointService.list());
    }

    @GetMapping("/detail")
    public ChargePointResponse detail(@RequestParam String cpId) {
        return ChargePointResponse.from(chargePointService.get(cpId));
    }

    /** (Re)opens the WebSocket connection. The simulator does not reconnect on its own. */
    @PostMapping("/connect")
    public ChargePointResponse connect(@RequestParam String cpId) {
        log.info("Incoming request to connect charge point '{}'.", cpId);
        chargePointService.connect(cpId);
        return ChargePointResponse.from(chargePointService.get(cpId));
    }

    @PostMapping("/disconnect")
    public ChargePointResponse disconnect(@RequestParam String cpId) {
        log.info("Incoming request to disconnect charge point '{}'.", cpId);
        chargePointService.disconnect(cpId);
        return ChargePointResponse.from(chargePointService.get(cpId));
    }

    /** Disconnects the charge point and removes it from the registry. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestParam String cpId) {
        log.info("Incoming request to remove charge point '{}'.", cpId);
        chargePointService.remove(cpId);
    }

    /** Simulates plugging a cable into a connector: the connector becomes Preparing. */
    @PostMapping("/connectors/plug-in")
    public ConnectorResponse plugIn(@RequestParam String cpId,
                                    @RequestParam(required = false) Integer connectorId) {
        log.info("Incoming plug-in request for charge point '{}' connector '{}'.", cpId, connectorId);
        return ConnectorResponse.from(chargePointService.plugIn(cpId, connectorId));
    }

    /** Simulates unplugging a cable: an active transaction is stopped, the connector becomes Available. */
    @PostMapping("/connectors/plug-out")
    public ConnectorResponse plugOut(@RequestParam String cpId,
                                     @RequestParam(required = false) Integer connectorId) {
        log.info("Incoming plug-out request for charge point '{}' connector '{}'.", cpId, connectorId);
        return ConnectorResponse.from(chargePointService.plugOut(cpId, connectorId));
    }

    /** Simulates an RFID card tap: sends an Authorize request and starts a transaction when accepted. */
    @PostMapping("/connectors/rfid")
    public AuthorizationResponse rfid(@RequestParam String cpId,
                                      @RequestParam(required = false) Integer connectorId,
                                      @RequestParam String idTag) {
        log.info("Incoming RFID request for charge point '{}' connector '{}' with idTag '{}'.",
                cpId, connectorId, idTag);
        AuthorizationOutcome outcome = chargePointService.authorize(cpId, connectorId, idTag);
        return AuthorizationResponse.from(outcome, chargePointService.getConnector(cpId, outcome.connectorId()));
    }
}
