package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.StoredChargingProfile;
import com.ocpp.chargepointsimulator.exceptions.InvalidChargingProfileException;
import com.ocpp.chargepointsimulator.utilities.CompositeScheduleBuilder;
import com.ocpp.chargepointsimulator.utilities.SmartChargingCalculator;
import eu.chargetime.ocpp.feature.profile.ClientSmartChargingEventHandler;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ChargingSchedule;
import eu.chargetime.ocpp.model.smartcharging.ChargingProfileStatus;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileConfirmation;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileRequest;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileStatus;
import eu.chargetime.ocpp.model.smartcharging.GetCompositeScheduleConfirmation;
import eu.chargetime.ocpp.model.smartcharging.GetCompositeScheduleRequest;
import eu.chargetime.ocpp.model.smartcharging.GetCompositeScheduleStatus;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileConfirmation;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Handles the smart charging messages a central system sends to this charge point.
 *
 * <ul>
 *   <li>{@code SetChargingProfile} — the profile is validated against OCPP 1.6 and stored, an invalid
 *       profile is answered with {@code Rejected} instead of being silently dropped.
 *   <li>{@code ClearChargingProfile} — removes what matches, {@code Unknown} when nothing did.
 *   <li>{@code GetCompositeSchedule} — answered with the schedule that results from every profile
 *       that applies to the requested connector.
 * </ul>
 *
 * <p>Applying the schedule to the connectors is not done here: the metering loop re-evaluates the
 * stored profiles on every tick, so a profile that changes at a period boundary takes effect without
 * any further message from the central system.
 */
@Slf4j
@RequiredArgsConstructor
public class ChargePointSmartChargingHandler implements ClientSmartChargingEventHandler {

    private final ChargePointSession session;

    @Override
    public SetChargingProfileConfirmation handleSetChargingProfileRequest(SetChargingProfileRequest request) {
        try {
            StoredChargingProfile profile = StoredChargingProfile.from(
                    request, connectorIds(), session.runningTransactions(), Instant.now());
            return accept(profile);
        } catch (InvalidChargingProfileException e) {
            log.warn("[{}] Rejected charging profile: {}", session.getChargePointId(), e.getMessage());
            return new SetChargingProfileConfirmation(ChargingProfileStatus.Rejected);
        }
    }

    @Override
    public ClearChargingProfileConfirmation handleClearChargingProfileRequest(ClearChargingProfileRequest request) {
        int removed = session.getChargingProfiles().clear(request.getId(), request.getConnectorId(),
                request.getChargingProfilePurpose(), request.getStackLevel());
        if (removed == 0) {
            log.info("[{}] No charging profile matched the clear request (id {}, connector {}, purpose {}, stack {}).",
                    session.getChargePointId(), request.getId(), request.getConnectorId(),
                    request.getChargingProfilePurpose(), request.getStackLevel());
            return new ClearChargingProfileConfirmation(ClearChargingProfileStatus.Unknown);
        }
        log.info("[{}] Cleared {} charging profile(s), {} left.", session.getChargePointId(),
                removed, session.getChargingProfiles().size());
        return new ClearChargingProfileConfirmation(ClearChargingProfileStatus.Accepted);
    }

    @Override
    public GetCompositeScheduleConfirmation handleGetCompositeScheduleRequest(GetCompositeScheduleRequest request) {
        Optional<String> refusal = refusalReason(request);
        if (refusal.isPresent()) {
            log.warn("[{}] Rejected GetCompositeSchedule: {}", session.getChargePointId(), refusal.get());
            return new GetCompositeScheduleConfirmation(GetCompositeScheduleStatus.Rejected);
        }
        return acceptedCompositeSchedule(request);
    }

    private SetChargingProfileConfirmation accept(StoredChargingProfile profile) {
        session.getChargingProfiles().put(profile);
        log.info("[{}] Accepted charging {}, {} profile(s) stored.",
                session.getChargePointId(), profile.describe(), session.getChargingProfiles().size());
        return new SetChargingProfileConfirmation(ChargingProfileStatus.Accepted);
    }

    /** @return why the request cannot be answered, empty when it can be. */
    private Optional<String> refusalReason(GetCompositeScheduleRequest request) {
        if (request.getConnectorId() == null) {
            return Optional.of("a connectorId is required");
        }
        if (request.getDuration() == null || request.getDuration() <= 0) {
            return Optional.of("a positive duration is required, but was " + request.getDuration());
        }
        if (request.getConnectorId() != 0 && !connectorIds().contains(request.getConnectorId())) {
            return Optional.of("connector " + request.getConnectorId() + " does not exist");
        }
        return Optional.empty();
    }

    /** The composite of every profile that applies to the requested connector, in the requested unit. */
    private GetCompositeScheduleConfirmation acceptedCompositeSchedule(GetCompositeScheduleRequest request) {
        Instant now = Instant.now();
        ChargingSchedule schedule = CompositeScheduleBuilder.build(
                session.getChargingProfiles().forConnector(request.getConnectorId()),
                compositeRequest(request, now));

        GetCompositeScheduleConfirmation confirmation =
                new GetCompositeScheduleConfirmation(GetCompositeScheduleStatus.Accepted);
        confirmation.setConnectorId(request.getConnectorId());
        confirmation.setScheduleStart(ZonedDateTime.ofInstant(now, ZoneOffset.UTC));
        confirmation.setChargingSchedule(schedule);
        log.info("[{}] Answered GetCompositeSchedule for connector {} ({} s, {}) with {} period(s).",
                session.getChargePointId(), request.getConnectorId(), request.getDuration(),
                schedule.getChargingRateUnit(), schedule.getChargingSchedulePeriod().length);
        return confirmation;
    }

    /** What the builder needs to know about the window, the unit and the electrical setup. */
    private CompositeScheduleBuilder.Request compositeRequest(GetCompositeScheduleRequest request, Instant now) {
        ChargePointConfig config = session.getConfig();
        return new CompositeScheduleBuilder.Request(
                request.getConnectorId(),
                now,
                session.transactionStart(),
                request.getDuration(),
                unit(request.getChargingRateUnit()),
                new SmartChargingCalculator.Electrical(config.phaseVoltage(), config.numberPhases()),
                config.chargingPower());
    }

    private Set<Integer> connectorIds() {
        return Set.copyOf(session.getConfig().connectorIds());
    }

    /**
     * The library has one {@code ChargingRateUnitType} per package, this maps the one of the request
     * onto the one used inside a {@code ChargingSchedule}.
     */
    private static ChargingRateUnitType unit(
            eu.chargetime.ocpp.model.smartcharging.ChargingRateUnitType requested) {
        return requested != null && "A".equals(requested.name()) ? ChargingRateUnitType.A : ChargingRateUnitType.W;
    }
}
