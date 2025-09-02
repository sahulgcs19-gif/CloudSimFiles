package org.cloudbus.cloudsim.power.models;

/** PM2: AWS Graviton2 (your data; 0..100% in 10% steps) */
public class PowerModelSpecPowerPm2AwsGraviton2 extends PowerModelSpecPower {

    // Utilization steps: 0,10,20,30,40,50,60,70,80,90,100 %
    private final double[] power = {
        70.8, 102.8, 137.2, 169.2, 201.2, 236.6, 268.6, 300.6, 336.0, 403.4, 451.4
    };

    @Override
    protected double getPowerData(int index) {
        return power[index];
    }
}
