package com.ocpp.chargepointsimulator.utilities;

import java.util.concurrent.TimeUnit;

/**
 * Translates electrical power into the amount of energy a simulated meter registers over a certain
 * time span.
 *
 * <p>Physics: energy is power integrated over time, so for a constant power
 *
 * <pre>
 *   E[Wh] = P[W] * t[h] = P[W] * (t[s] / 3600)
 * </pre>
 *
 * With the defaults of 5000 W and a 60 s metering interval the register grows by 83 Wh per message.
 */
public final class EnergyMeterCalculator {

    private EnergyMeterCalculator() {
    }

    /**
     * @param chargingPowerW   constant charging power in Watt, must not be negative; {@code 0} is a
     *     connector that a charging profile suspended, and adds no energy
     * @param intervalSeconds  metering interval in seconds, must be positive
     * @return energy in Wh to add to the meter register for one metering interval, rounded to the
     *     nearest Wh
     */
    public static int energyStepWh(int chargingPowerW, int intervalSeconds) {
        if (chargingPowerW < 0) {
            throw new IllegalArgumentException("chargingPowerW must not be negative, but was " + chargingPowerW + ".");
        }
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("intervalSeconds must be greater than 0, but was " + intervalSeconds + ".");
        }
        double intervalHours = intervalSeconds / (double) TimeUnit.HOURS.toSeconds(1);
        return (int) Math.round(chargingPowerW * intervalHours);
    }
}
