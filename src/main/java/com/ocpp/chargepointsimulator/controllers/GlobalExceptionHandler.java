package com.ocpp.chargepointsimulator.controllers;

import com.ocpp.chargepointsimulator.exceptions.ChargePointAlreadyExistsException;
import com.ocpp.chargepointsimulator.exceptions.ChargePointNotConnectedException;
import com.ocpp.chargepointsimulator.exceptions.ChargePointNotFoundException;
import com.ocpp.chargepointsimulator.exceptions.ConnectorNotFoundException;
import com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException;
import com.ocpp.chargepointsimulator.exceptions.OcppRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Maps simulator failures onto HTTP status codes so a caller can tell what actually went wrong
 * instead of always receiving a 200 with an empty body.
 *
 * <ul>
 *   <li>400 - the charge point definition is invalid</li>
 *   <li>404 - unknown charge point or connector</li>
 *   <li>409 - the charge point already exists, or it is not connected to the central system</li>
 *   <li>502 - the central system could not be reached or did not answer</li>
 *   <li>500 - anything else</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidChargePointConfigException.class)
    public ProblemDetail handleInvalidConfiguration(InvalidChargePointConfigException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid charge point configuration", e);
    }

    @ExceptionHandler({ ChargePointNotFoundException.class, ConnectorNotFoundException.class })
    public ProblemDetail handleNotFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Charge point or connector not found", e);
    }

    @ExceptionHandler({ ChargePointAlreadyExistsException.class, ChargePointNotConnectedException.class })
    public ProblemDetail handleConflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Conflicting charge point state", e);
    }

    @ExceptionHandler(OcppRequestException.class)
    public ProblemDetail handleOcppFailure(OcppRequestException e) {
        return problem(HttpStatus.BAD_GATEWAY, "Central system communication failed", e);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error while serving a charge point request.", e);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error (" + e.getClass().getSimpleName() + "), check the simulator logs for details.");
        problemDetail.setTitle("Unexpected error");
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    private ProblemDetail problem(HttpStatus status, String title, Exception e) {
        if (status.is5xxServerError()) {
            log.error("{}: {}", title, e.getMessage(), e);
        } else {
            log.warn("{}: {}", title, e.getMessage());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problemDetail.setTitle(title);
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }
}
