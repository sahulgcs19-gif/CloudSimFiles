package org.cloudbus.cloudsim.power.models;

/** PM1: Intel Xeon E5-2686 v4 (estimated curve; 0..100% in 10% steps) */
public class PowerModelSpecPowerPm1XeonE52686v4 extends PowerModelSpecPower {

    // Utilization steps: 0,10,20,30,40,50,60,70,80,90,100 %
    private final double[] power = {
        35.0, 52.0, 70.0, 78.5, 87.0, 96.5, 104.0, 108.0, 112.0, 129.0, 147.0
    };

    @Override
    protected double getPowerData(int index) {
        return power[index];
    }
}
