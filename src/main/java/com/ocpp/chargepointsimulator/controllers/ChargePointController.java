package com.ocpp.chargepointsimulator.controllers;

import com.ocpp.chargepointsimulator.services.ChargePointService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/charge-point")
@AllArgsConstructor
@Slf4j
public class ChargePointController {

    private final ChargePointService chargePointService;

    @PostMapping("/plug-in")
    public void plugInTheCharger() {
        log.info("Incoming charger plug-in request.");
        chargePointService.plugTheChargerIn();
    }

    @PostMapping("/rfid")
    public void triggerRfidRequest(@RequestParam String idTag) {
        log.info("Rfid request with idTag -> {}", idTag);
        chargePointService.triggerAuthorizationRequest(idTag);
    }

    @PostMapping("/plug-out")
    public void plugOutTheCar() {
        log.info("Incoming charger plug-out request.");
        chargePointService.plugOutTheCharger();
    }

}
