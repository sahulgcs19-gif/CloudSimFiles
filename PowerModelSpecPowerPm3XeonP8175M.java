package org.cloudbus.cloudsim.power.models;

/** PM3: Intel Xeon P-8175M (your data; 0..100% in 10% steps) */
public class PowerModelSpecPowerPm3XeonP8175M extends PowerModelSpecPower {

    // Utilization steps: 0,10,20,30,40,50,60,70,80,90,100 %
    private final double[] power = {
        50.0, 75.0, 100.0, 112.0, 125.0, 137.0, 150.0, 155.0, 160.0, 135.0, 210.0
    };

    @Override
    protected double getPowerData(int index) {
        return power[index];
    }
}
