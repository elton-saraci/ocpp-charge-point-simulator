package com.ocpp.chargepointsimulator.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Deep links into the web console.
 *
 * <p>The console is a single page served from {@code /}, so without a route of its own the control
 * room of a charge point could not be bookmarked, shared or reloaded: every address would land on the
 * fleet. {@code /station/{chargePointId}} forwards the request to the console, which reads the charge
 * point out of the address bar, while the browser keeps the address it asked for.
 *
 * <p>The route is a path of its own and does not shadow the console, its assets or the REST API.
 */
@Controller
public class ConsoleRouteController {

    /**
     * @return a server side forward to the console; a redirect would drop the deep link
     */
    @GetMapping({"/station", "/station/{chargePointId}"})
    public String controlRoom() {
        return "forward:/index.html";
    }
}
