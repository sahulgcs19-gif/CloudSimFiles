/*
 * Title:        CloudSim Toolkit
 * Description:  Multi-objective Overutilized Host Detection (MOHD) VM allocation policy
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * This policy marks a host as overutilized if:
 *   (A) Predicted CPU utilization of the PM exceeds the CPU threshold (gammaCpu), or
 *   (B) CPU is OK but predicted memory usage of the PM exceeds Mem_th (fraction of host RAM).
 *
 * CPU prediction uses a Weighted Moving Average (WMA) over an adaptive window CWin(ε, w, ·):
 *   - If recent deviation |VRU - WMA| > θ, expand the window (more smoothing).
 *   - Else, gently shrink the window (more responsiveness).
 *
 * Memory prediction reuses the SAME function used in your previous methods:
 *   memFrac = MEM_ALPHA[type] * cpuPred
 * And aggregates absolute memory (MB) across VMs:
 *   predictedHostMemMB = Σ (memFrac_j * VMj.RAM_MB)
 *
 * NOTES:
 *  - Ensure Constants.VM_TYPES / VM_MIPS[] / VM_PES[] are configured for your 5 VM types.
 *  - If your Constants class is in a different package, add/adjust the import accordingly.
 */

package org.cloudbus.cloudsim.power;
import org.cloudbus.cloudsim.examples.power.Constants;
import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;

public class PowerVmAllocationPolicyMigrationMOHD extends PowerVmAllocationPolicyMigrationAbstract {

    /* ===================== Tunable Parameters (pseudocode symbols) ===================== */

    /** ε: window adaptation sensitivity (0.0 .. 1.0). */
    private final double epsilon;

    /** Initial window size w (>=1). */
    private final int baseWindow;

    /** θ: deviation threshold between recent VRU and WMA to trigger window growth. */
    private final double theta;

    /** γ: CPU utilization threshold (fraction, e.g., 0.9 for 90%). */
    private final double gammaCpu;

    /** Mem_th: memory threshold as a FRACTION of host RAM (e.g., 0.9). */
    private final double memThresholdFrac;

    /** Optional fallback policy (not required by MOHD but available if you want chaining). */
    private final PowerVmAllocationPolicyMigrationAbstract fallbackVmAllocationPolicy;

    /* ===================== Memory-to-CPU ratio (same as previous methods) ===================== */

    /** Memory-to-CPU utilization ratios by VM type (0..4). */
    private static final double[] MEM_ALPHA = {
        1.00,  // type 0
        0.70,  // type 1
        2.00,  // type 2
        1.80,  // type 3
        0.80   // type 4
    };
    private static final double DEFAULT_ALPHA = 1.0;

    public PowerVmAllocationPolicyMigrationMOHD(
            final List<? extends Host> hostList,
            final PowerVmSelectionPolicy vmSelectionPolicy,
            final double epsilon,
            final int baseWindow,
            final double theta,
            final double gammaCpu,
            final double memThresholdFrac,
            final PowerVmAllocationPolicyMigrationAbstract fallbackVmAllocationPolicy) {

        super(hostList, vmSelectionPolicy);
        if (epsilon < 0) throw new IllegalArgumentException("epsilon must be >= 0");
        if (baseWindow < 1) throw new IllegalArgumentException("baseWindow must be >= 1");
        if (theta < 0) throw new IllegalArgumentException("theta must be >= 0");
        if (gammaCpu <= 0 || gammaCpu > 1.0) throw new IllegalArgumentException("gammaCpu must be in (0,1]");
        if (memThresholdFrac <= 0 || memThresholdFrac > 1.0) throw new IllegalArgumentException("memThresholdFrac must be in (0,1]");

        this.epsilon = epsilon;
        this.baseWindow = baseWindow;
        this.theta = theta;
        this.gammaCpu = gammaCpu;
        this.memThresholdFrac = memThresholdFrac;
        this.fallbackVmAllocationPolicy = fallbackVmAllocationPolicy;
    }

    public PowerVmAllocationPolicyMigrationMOHD(
            final List<? extends Host> hostList,
            final PowerVmSelectionPolicy vmSelectionPolicy,
            final double epsilon,
            final int baseWindow,
            final double theta,
            final double gammaCpu,
            final double memThresholdFrac) {
        this(hostList, vmSelectionPolicy, epsilon, baseWindow, theta, gammaCpu, memThresholdFrac, null);
    }

   
    @Override
    public boolean isHostOverUtilized(final PowerHost host) {
        final List<PowerVm> vms = getVmListSafe(host);
        if (vms.isEmpty()) {
            addHistoryEntry(host, gammaCpu); // record threshold used (for plotting/history)
            return false;
        }

        /* ---------- 1) CPU objective: predict PM CPU and compare to gammaCpu ---------- */
        double predictedHostMips = 0.0;
        final double[] cpuPred = new double[vms.size()];

        for (int j = 0; j < vms.size(); j++) {
            final PowerVm vm = vms.get(j);

            // Adaptive window per VM: expand if deviation > theta, else gently shrink
            final int w = adaptiveWindow(vm.getUtilizationHistory(), baseWindow, epsilon, theta);

            // CPU prediction via Weighted Moving Average over 'w' samples (values in [0,1])
            cpuPred[j] = wma(vm.getUtilizationHistory(), w);

            // Aggregate predicted MIPS to the host
            if (!Double.isNaN(cpuPred[j])) {
                final double vmMaxMips = vm.getMips() * vm.getNumberOfPes();
                predictedHostMips += cpuPred[j] * vmMaxMips;
            }
        }

        final double hostTotalMips = host.getTotalMips();
        final double u_th = hostTotalMips > 0 ? (predictedHostMips / hostTotalMips) : 0.0;

        // Record the CPU threshold used (mirroring other CloudSim policies)
        addHistoryEntry(host, gammaCpu);

        if (u_th > gammaCpu) {
            // CPU overutilized (γ ← 1 in pseudocode)
            return true;
        }

        /* ---------- 2) Memory objective: only if CPU is OK ---------- */
        double predictedHostMemMB = 0.0;

        for (int j = 0; j < vms.size(); j++) {
            final PowerVm vm = vms.get(j);

            // Reuse CPU prediction; if missing, attempt a tiny fallback window
            double cpu = cpuPred[j];
            if (Double.isNaN(cpu)) {
                cpu = wma(vm.getUtilizationHistory(), 1);
                if (Double.isNaN(cpu)) cpu = 0.0;
            }

            // SAME memory mapping as your previous methods: memFrac = MEM_ALPHA[type] * cpuPred
            final double memFrac = inferMemFracFromCpu(vm, cpu);

            // Absolute predicted memory for this VM (MB)
            final double vmMemMB = vm.getRam();
            predictedHostMemMB += memFrac * vmMemMB;
        }

        // Compare against Mem_th (fraction of host RAM)
        final double memThresholdMB = memThresholdFrac * host.getRam();
        if (predictedHostMemMB >= memThresholdMB) {
            // Memory overutilized (γ ← 2 in pseudocode)
            return true;
        }

        // Neither CPU nor memory exceeded thresholds
        return false;
    }

       private static List<PowerVm> getVmListSafe(final PowerHost host) {
        final List<? extends Vm> raw = host.getVmList();
        final List<PowerVm> out = new ArrayList<>(raw.size());
        for (Vm v : raw) {
            if (v instanceof PowerVm) out.add((PowerVm) v);
        }
        return out;
    }
    protected static int adaptiveWindow(final List<Double> hist, final int w0,
                                        final double epsilon, final double theta) {
        if (hist == null || hist.isEmpty()) return Math.max(1, w0);
        int w = Math.max(1, Math.min(w0, hist.size()));

        final double dev = recentDeviation(hist, w, /*lookBack*/4);
        if (dev > theta) {
            // increase for smoothing under volatility
            w = (int)Math.ceil(w * (1.0 + Math.max(0.0, epsilon)));
        } else {
            // decrease for responsiveness under stability
            w = (int)Math.floor(w * (1.0 - Math.max(0.0, epsilon) * 0.5));
        }
        if (w < 1) w = 1;
        if (w > hist.size()) w = hist.size();
        return w;
    }

    /** Max absolute deviation between VRU and its WMA(w) over the last 'lookBack' samples. */
    protected static double recentDeviation(final List<Double> hist, final int w, final int lookBack) {
        if (hist == null || hist.isEmpty()) return 0.0;
        final int n = hist.size();
        final int lb = Math.max(1, Math.min(lookBack, n));
        double maxDev = 0.0;
        for (int i = n - lb; i < n; i++) {
            final double vru = hist.get(i);
            final double wmaVal = wmaUpTo(hist, w, i + 1); // WMA using values up to index i
            if (!Double.isNaN(wmaVal)) {
                maxDev = Math.max(maxDev, Math.abs(vru - wmaVal));
            }
        }
        return maxDev;
    }

    /** Weighted moving average of the last 'window' points (weights 1..window, heavier recent). */
    protected static double wma(final List<Double> hist, final int window) {
        if (hist == null || hist.isEmpty() || window < 1) return Double.NaN;
        final int n = hist.size();
        final int k = Math.min(window, n);
        double num = 0.0, den = 0.0;
        for (int i = 0; i < k; i++) {
            final int idx = n - 1 - i;
            final double w = (i + 1); // 1..k
            num += w * hist.get(idx);
            den += w;
        }
        return den > 0 ? clamp01(num / den) : Double.NaN;
    }

    /** WMA computed using samples hist[0..endExclusive-1] with the given window. */
    protected static double wmaUpTo(final List<Double> hist, final int window, final int endExclusive) {
        if (hist == null || hist.isEmpty() || window < 1) return Double.NaN;
        final int n = Math.min(endExclusive, hist.size());
        final int k = Math.min(window, n);
        double num = 0.0, den = 0.0;
        for (int i = 0; i < k; i++) {
            final int idx = n - 1 - i;
            final double w = (i + 1);
            num += w * hist.get(idx);
            den += w;
        }
        return den > 0 ? clamp01(num / den) : Double.NaN;
    }

    private static double clamp01(final double x) {
        if (Double.isNaN(x)) return x;
        if (x < 0) return 0.0;
        if (x > 1) return 1.0;
        return x;
    }

    /** EXACT same mapping used earlier: memFrac = MEM_ALPHA[type] * cpuFrac (not capped). */
    protected final double inferMemFracFromCpu(final Vm vm, final double cpuFrac) {
        final int t = getVmType(vm);       // must return 0..4
        final double alpha = alphaForType(t);
        double memFrac = alpha * cpuFrac;  // may exceed 1.0, signalling pressure
        if (memFrac < 0.0) memFrac = 0.0;
        return memFrac;
    }

    /** Map VM -> logical type [0..4] using Constants.VM_MIPS/VM_PES (same as your previous helper). */
    protected static int getVmType(final Vm vm) {
        final int n = Constants.VM_TYPES;
        if (n <= 0 || Constants.VM_MIPS.length != n || Constants.VM_PES.length != n) {
            throw new IllegalStateException("VM type constants out of sync: check VM_TYPES/VM_MIPS/VM_PES");
        }

        final double vmMips = vm.getMips();      // per-PE MIPS in CloudSim
        final int vmPes = vm.getNumberOfPes();

        // Relative tolerance for MIPS comparison
        final double MIPS_EPS_REL = 1e-6;

        int nearestIdx = -1;
        double nearestCost = Double.POSITIVE_INFINITY;

        for (int t = 0; t < n; t++) {
            final double baseMips = Constants.VM_MIPS[t];
            final int basePes = Constants.VM_PES[t];

            final double mipsDelta = Math.abs(vmMips - baseMips);
            final double mipsTol = Math.max(Math.abs(baseMips), 1.0) * MIPS_EPS_REL;
            final boolean mipsMatch = mipsDelta <= mipsTol;
            final boolean pesMatch  = vmPes == basePes;

            if (mipsMatch && pesMatch) {
                return t; // exact match (within tolerance)
            }

            // Track nearest for better diagnostics
            final double normDelta = mipsDelta / Math.max(baseMips, 1.0);
            final double cost = normDelta * normDelta + 0.25 * (vmPes - basePes) * (vmPes - basePes);
            if (cost < nearestCost) {
                nearestCost = cost;
                nearestIdx = t;
            }
        }

        throw new IllegalArgumentException(String.format(
            "Unable to determine VM type for VM ID %d (mips=%.3f, pes=%d). "
          + "Nearest type=%d (mips=%.3f, pes=%d).",
            vm.getId(), vmMips, vmPes, nearestIdx,
            Constants.VM_MIPS[nearestIdx], Constants.VM_PES[nearestIdx]));
    }

    private static double alphaForType(final int type) {
        if (type >= 0 && type < MEM_ALPHA.length) {
            final double a = MEM_ALPHA[type];
            return (a > 0.0 && !Double.isNaN(a)) ? a : DEFAULT_ALPHA;
        }
        return DEFAULT_ALPHA;
    }



    protected double getGammaCpu()        { return gammaCpu; }
    protected double getMemThresholdFrac(){ return memThresholdFrac; }
    protected double getTheta()           { return theta; }
    protected double getEpsilon()         { return epsilon; }
    protected int getBaseWindow()         { return baseWindow; }

    public PowerVmAllocationPolicyMigrationAbstract getFallbackVmAllocationPolicy() {
        return fallbackVmAllocationPolicy;
    }
}


