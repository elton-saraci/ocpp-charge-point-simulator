package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.ChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfileKindType;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ChargingSchedule;
import eu.chargetime.ocpp.model.core.ChargingSchedulePeriod;
import eu.chargetime.ocpp.model.smartcharging.ChargingProfileStatus;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileRequest;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileStatus;
import eu.chargetime.ocpp.model.smartcharging.GetCompositeScheduleConfirmation;
import eu.chargetime.ocpp.model.smartcharging.GetCompositeScheduleRequest;
import eu.chargetime.ocpp.model.smartcharging.GetCompositeScheduleStatus;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileConfirmation;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Drives the three smart charging messages the way a central system does, and checks what the charge
 * point answers and remembers.
 */
class ChargePointSmartChargingHandlerTest {

    private static final int TRANSACTION_ID = 4711;
    private static final ChargePointConfig CONFIG = new ChargePointConfig(
            "CP_1", "ws://localhost:8080", null, null, 11_040, 230, 3, 60, List.of(1, 2));

    private ChargePointSession session;
    private ChargePointSmartChargingHandler handler;

    @BeforeEach
    void setUp() {
        session = new ChargePointSession(CONFIG);
        handler = new ChargePointSmartChargingHandler(session);
    }

    @Test
    void acceptsAValidProfileAndStoresIt() {
        SetChargingProfileConfirmation confirmation =
                handler.handleSetChargingProfileRequest(defaultProfileRequest(1, 1, 6));

        assertEquals(ChargingProfileStatus.Accepted, confirmation.getStatus());
        assertEquals(1, session.getChargingProfiles().size());
    }

    @Test
    void rejectsAProfileForAnUnknownConnector() {
        SetChargingProfileConfirmation confirmation =
                handler.handleSetChargingProfileRequest(defaultProfileRequest(1, 9, 6));

        assertEquals(ChargingProfileStatus.Rejected, confirmation.getStatus());
        assertEquals(0, session.getChargingProfiles().size());
    }

    @Test
    void rejectsATxProfileWithoutARunningTransaction() {
        SetChargingProfileConfirmation confirmation = handler.handleSetChargingProfileRequest(
                txProfileRequest());

        assertEquals(ChargingProfileStatus.Rejected, confirmation.getStatus());
    }

    @Test
    void acceptsATxProfileForTheRunningTransaction() {
        ConnectorState connector = session.getConnector(1);
        connector.startTransaction(TRANSACTION_ID, "tag1", ChargePointStatus.Charging);

        SetChargingProfileConfirmation confirmation = handler.handleSetChargingProfileRequest(
                txProfileRequest());

        assertEquals(ChargingProfileStatus.Accepted, confirmation.getStatus());
    }

    @Test
    void aNewProfileReplacesTheSlotItBelongsTo() {
        handler.handleSetChargingProfileRequest(defaultProfileRequest(3, 1, 16));
        handler.handleSetChargingProfileRequest(defaultProfileRequest(4, 1, 6));

        // same connector, purpose and stack level: only the newest profile stays
        assertEquals(1, session.getChargingProfiles().size());
        assertEquals(4, session.getChargingProfiles().all().getFirst().chargingProfileId());
    }

    @Test
    void clearsTheMatchingProfile() {
        handler.handleSetChargingProfileRequest(defaultProfileRequest(5, 1, 6));

        ClearChargingProfileRequest clear = new ClearChargingProfileRequest();
        clear.setId(5);

        assertEquals(ClearChargingProfileStatus.Accepted,
                handler.handleClearChargingProfileRequest(clear).getStatus());
        assertEquals(0, session.getChargingProfiles().size());
    }

    @Test
    void answersUnknownWhenNothingMatchesAClearRequest() {
        ClearChargingProfileRequest clear = new ClearChargingProfileRequest();
        clear.setId(999);

        assertEquals(ClearChargingProfileStatus.Unknown,
                handler.handleClearChargingProfileRequest(clear).getStatus());
    }

    @Test
    void answersGetCompositeScheduleWithTheEffectiveLimit() {
        handler.handleSetChargingProfileRequest(defaultProfileRequest(6, 1, 6));

        GetCompositeScheduleRequest request = new GetCompositeScheduleRequest(1, 600);
        GetCompositeScheduleConfirmation confirmation = handler.handleGetCompositeScheduleRequest(request);

        assertEquals(GetCompositeScheduleStatus.Accepted, confirmation.getStatus());
        assertEquals(1, confirmation.getConnectorId());
        // 6 A over three phases at 230 V
        assertEquals(4_140d, confirmation.getChargingSchedule().getChargingSchedulePeriod()[0].getLimit(), 0.5);
    }

    @Test
    void answersGetCompositeScheduleWithTheNominalPowerWhenNoProfileExists() {
        GetCompositeScheduleConfirmation confirmation =
                handler.handleGetCompositeScheduleRequest(new GetCompositeScheduleRequest(1, 600));

        assertEquals(GetCompositeScheduleStatus.Accepted, confirmation.getStatus());
        assertEquals(11_040d, confirmation.getChargingSchedule().getChargingSchedulePeriod()[0].getLimit(), 0.5);
    }

    @Test
    void rejectsGetCompositeScheduleForAnUnknownConnector() {
        assertEquals(GetCompositeScheduleStatus.Rejected,
                handler.handleGetCompositeScheduleRequest(new GetCompositeScheduleRequest(7, 600)).getStatus());
    }

    @Test
    void rejectsGetCompositeScheduleWithoutADuration() {
        GetCompositeScheduleRequest request = new GetCompositeScheduleRequest(1, 600);
        request.setDuration(0);

        assertEquals(GetCompositeScheduleStatus.Rejected,
                handler.handleGetCompositeScheduleRequest(request).getStatus());
        assertNull(handler.handleGetCompositeScheduleRequest(request).getChargingSchedule());
    }

    private static SetChargingProfileRequest defaultProfileRequest(int profileId,
                                                                  int connectorId,
                                                                  double limit) {
        ChargingSchedule schedule = new ChargingSchedule(ChargingRateUnitType.A, new ChargingSchedulePeriod[] {
                new ChargingSchedulePeriod(0, limit) });
        ChargingProfile profile = new ChargingProfile();
        profile.setChargingProfileId(profileId);
        profile.setChargingProfilePurpose(ChargingProfilePurposeType.TxDefaultProfile);
        profile.setChargingProfileKind(ChargingProfileKindType.Absolute);
        profile.setChargingSchedule(schedule);
        return new SetChargingProfileRequest(connectorId, profile);
    }

    private static SetChargingProfileRequest txProfileRequest() {
        ChargingSchedule schedule = new ChargingSchedule(ChargingRateUnitType.A, new ChargingSchedulePeriod[] {
                new ChargingSchedulePeriod(0, (double) 6) });
        ChargingProfile profile = new ChargingProfile();
        profile.setChargingProfileId(2);
        profile.setTransactionId(TRANSACTION_ID);
        profile.setChargingProfilePurpose(ChargingProfilePurposeType.TxProfile);
        profile.setChargingProfileKind(ChargingProfileKindType.Relative);
        profile.setChargingSchedule(schedule);
        return new SetChargingProfileRequest(1, profile);
    }
}
