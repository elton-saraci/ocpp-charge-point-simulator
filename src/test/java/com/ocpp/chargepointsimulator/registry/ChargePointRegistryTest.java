package com.ocpp.chargepointsimulator.registry;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.exceptions.ChargePointAlreadyExistsException;
import com.ocpp.chargepointsimulator.exceptions.ChargePointNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChargePointRegistryTest {

    private final ChargePointRegistry registry = new ChargePointRegistry();

    @Test
    void keepsChargePointsApartByTheirId() {
        ChargePointSession first = session("CP_1");
        ChargePointSession second = session("CP_2");

        registry.add(first);
        registry.add(second);

        assertEquals(2, registry.size());
        assertSame(first, registry.get("CP_1"));
        assertSame(second, registry.get("CP_2"));
        assertEquals(List.of("CP_1", "CP_2"), registry.registeredIds().stream().sorted().toList());
    }

    @Test
    void rejectsADuplicateId() {
        registry.add(session("CP_1"));

        assertThrows(ChargePointAlreadyExistsException.class, () -> registry.add(session("CP_1")));
    }

    @Test
    void reportsUnknownIds() {
        assertThrows(ChargePointNotFoundException.class, () -> registry.get("CP_MISSING"));
        assertThrows(ChargePointNotFoundException.class, () -> registry.remove("CP_MISSING"));
    }

    @Test
    void removesChargePoints() {
        registry.add(session("CP_1"));

        assertSame("CP_1", registry.remove("CP_1").getChargePointId());
        assertEquals(0, registry.size());
    }

    private ChargePointSession session(String chargePointId) {
        return new ChargePointSession(new ChargePointConfig(
                chargePointId, "ws://localhost:8080", null, null, 5000, 60, List.of(1, 2)));
    }
}
