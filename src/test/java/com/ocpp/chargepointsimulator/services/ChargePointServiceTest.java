package com.ocpp.chargepointsimulator.services;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.exceptions.ChargePointNotFoundException;
import com.ocpp.chargepointsimulator.exceptions.InvalidChargePointConfigException;
import com.ocpp.chargepointsimulator.registry.ChargePointRegistry;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ChargePointServiceTest {

    @Autowired
    private ChargePointService chargePointService;

    @Autowired
    private ChargePointRegistry registry;

    @AfterEach
    void removeRegisteredChargePoints() {
        registry.registeredIds().forEach(chargePointService::remove);
    }

    @Test
    void updateRebuildsTheChargePointWithTheNewDefinition() {
        chargePointService.register(config("CP_UPD_1", 5000, 60, List.of(1, 2)), false);

        ChargePointSession updated = chargePointService.update(
                "CP_UPD_1", config("CP_UPD_1", 22000, 15, List.of(1, 2, 3)), false);

        assertEquals(22000, updated.getConfig().chargingPower());
        assertEquals(15, updated.getConfig().meterValuesFrequency());
        assertEquals(List.of(1, 2, 3), updated.getConfig().connectorIds());
        assertEquals(3, updated.getConnectors().size());
        assertEquals(List.of("CP_UPD_1"), registry.registeredIds().stream().toList());
        // the station was not connected before the update, so it stays disconnected
        assertFalse(updated.isConnected());
    }

    @Test
    void updateKeepsTheEnergyRegisterOfConnectorsThatStillExist() {
        ChargePointSession original = chargePointService.register(config("CP_UPD_2", 5000, 60, List.of(1, 2)), false);
        original.getConnector(1).advanceMeterValueWh(1500);
        original.getConnector(2).advanceMeterValueWh(700);

        ChargePointSession updated = chargePointService.update(
                "CP_UPD_2", config("CP_UPD_2", 5000, 60, List.of(1, 3)), false);

        assertEquals(1500, updated.getConnector(1).getCurrentMeterValueWh(), "existing connector keeps its register");
        assertEquals(0, updated.getConnector(3).getCurrentMeterValueWh(), "a new connector starts at zero");
        assertTrue(updated.findConnector(2).isEmpty(), "connectors that are gone are no longer exposed");
    }

    @Test
    void updateStillSucceedsWhenARunningTransactionCannotBeClosed() {
        // no central system is reachable, so closing the transaction fails: the redefinition must still go through
        ChargePointSession original = chargePointService.register(config("CP_UPD_4", 5000, 60, List.of(1)), false);
        original.getConnector(1).advanceMeterValueWh(300);
        original.getConnector(1).startTransaction(4711, "tag1", ChargePointStatus.Charging);

        ChargePointSession updated = chargePointService.update(
                "CP_UPD_4", config("CP_UPD_4", 7000, 20, List.of(1)), false);

        assertEquals(7000, updated.getConfig().chargingPower());
        assertEquals(ChargePointStatus.Available, updated.getConnector(1).getStatus());
        assertFalse(updated.getConnector(1).hasTransaction());
        assertEquals(300, updated.getConnector(1).getCurrentMeterValueWh());
    }

    @Test
    void updateRefusesToRenameAChargePoint() {
        chargePointService.register(config("CP_UPD_3", 5000, 60, List.of(1)), false);

        assertThrows(InvalidChargePointConfigException.class, () -> chargePointService.update(
                "CP_UPD_3", config("CP_OTHER", 5000, 60, List.of(1)), false));
        assertEquals(List.of("CP_UPD_3"), registry.registeredIds().stream().toList());
    }

    @Test
    void updateRequiresARegisteredChargePoint() {
        assertThrows(ChargePointNotFoundException.class, () -> chargePointService.update(
                "CP_MISSING", config("CP_MISSING", 5000, 60, List.of(1)), false));
    }

    private ChargePointConfig config(String chargePointId, int chargingPower, int meterValuesFrequency,
                                     List<Integer> connectorIds) {
        return new ChargePointConfig(chargePointId, "ws://localhost:9999", null, null,
                chargingPower, meterValuesFrequency, connectorIds);
    }
}
