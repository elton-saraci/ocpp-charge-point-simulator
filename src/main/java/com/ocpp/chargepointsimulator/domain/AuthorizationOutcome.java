package com.ocpp.chargepointsimulator.domain;

import eu.chargetime.ocpp.model.core.AuthorizationStatus;

/**
 * Result of an Authorize request, both the verdict of the central system and the connector the
 * verdict applies to.
 *
 * @param connectorId          connector the id tag was authorized for
 * @param accepted             {@code true} when the central system accepted the id tag
 * @param centralSystemStatus  status reported by the central system, {@code null} when it did not
 *                             send an id tag info block at all
 */
public record AuthorizationOutcome(int connectorId, boolean accepted, AuthorizationStatus centralSystemStatus) {
}
