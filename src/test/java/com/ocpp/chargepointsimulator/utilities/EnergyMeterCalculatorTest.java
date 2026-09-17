package com.ocpp.chargepointsimulator.utilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnergyMeterCalculatorTest {

    @Test
    void derivesEnergyFromPowerAndInterval() {
        // 5000 W for 60 s = 5 kW * (60 / 3600) h = 83.33 Wh
        assertEquals(83, EnergyMeterCalculator.energyStepWh(5000, 60));
        // 5000 W for 15 s = 20.83 Wh
        assertEquals(21, EnergyMeterCalculator.energyStepWh(5000, 15));
        // 7400 W for 30 s = 61.67 Wh
        assertEquals(62, EnergyMeterCalculator.energyStepWh(7400, 30));
        // 3600 W for one hour = 3600 Wh
        assertEquals(3600, EnergyMeterCalculator.energyStepWh(3600, 3600));
        // 23000 W for one hour = 23000 Wh
        assertEquals(23000, EnergyMeterCalculator.energyStepWh(23000, 3600));
    }

    @Test
    void roundsDownWhenTheIntervalCarriesLessThanHalfAWh() {
        // 5 W for 60 s = 0.083 Wh, the register must not be inflated artificially
        assertEquals(0, EnergyMeterCalculator.energyStepWh(5, 60));
    }

    @Test
    void rejectsNonPositiveInputs() {
        assertThrows(IllegalArgumentException.class, () -> EnergyMeterCalculator.energyStepWh(0, 60));
        assertThrows(IllegalArgumentException.class, () -> EnergyMeterCalculator.energyStepWh(-5000, 60));
        assertThrows(IllegalArgumentException.class, () -> EnergyMeterCalculator.energyStepWh(5000, 0));
        assertThrows(IllegalArgumentException.class, () -> EnergyMeterCalculator.energyStepWh(5000, -60));
    }
}
