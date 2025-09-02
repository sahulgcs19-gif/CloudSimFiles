/*
 * Title:        CloudSim Toolkit
 * Description:  Memory-Utilization–Aware Minimum Predicted Memory VM selection policy
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * This policy selects the VM with the MINIMUM predicted memory pressure:
 *   1) CPU utilization is predicted via a moving average over a dynamic window:
 *        dws = floor( (sum_{i=1..n} |VRU_i - VRU_{i-1}|) / n + gamma )
 *      where VRU_i are utilization history samples in [0,1].
 *   2) Memory fraction is inferred proportionally to CPU using a per-type ratio:
 *        memFrac = MEM_ALPHA[type] * cpuPred
 *   3) Scalar metric per VM:
 *        metric = memFrac * vm.getRam()     (MB)
 *      The VM with the smallest 'metric' is selected for migration.
 *
 * If a VM lacks usable history, the policy falls back to classic MMT behavior
 * for that VM (metric = vm.getRam()), ensuring robustness.
 */

package org.cloudbus.cloudsim.power;

import java.util.List;

import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.examples.power.Constants;
public class PowerVmSelectionPolicyMinimumPredictedMemory extends PowerVmSelectionPolicy {

    /** Memory-to-CPU utilization ratios by VM type (0..4), provided by you. */
    private static final double[] MEM_ALPHA = {
        1.00,  // type 0
        0.70,  // type 1
        2.00,  // type 2
        1.80,  // type 3
        0.80   // type 4
    };

    private static final double DEFAULT_ALPHA = 1.0;

    /** Additive term γ in the dynamic window equation. */
    private final double gamma;

    /** Create policy with default gamma = 1.0. */
    public PowerVmSelectionPolicyMinimumPredictedMemory() {
        this(1.0);
    }

    /** Create policy with custom gamma. */
    public PowerVmSelectionPolicyMinimumPredictedMemory(final double gamma) {
        this.gamma = gamma;
    }

    /*
     * (non-Javadoc)
     * @see org.cloudbus.cloudsim.power.PowerVmSelectionPolicy#getVmToMigrate(org.cloudbus.cloudsim.power.PowerHost)
     */
    @Override
    public Vm getVmToMigrate(final PowerHost host) {
        final List<PowerVm> migratableVms = getMigratableVms(host);
        if (migratableVms.isEmpty()) {
            return null;
        }

        Vm vmToMigrate = null;
        double minMetric = Double.MAX_VALUE;

        for (final PowerVm vm : migratableVms) {
            if (vm.isInMigration()) {
                continue;
            }

            // 1) Dynamic window size from utilization history per your equation
            final int dws = computeDynamicWindow(vm, gamma);

            // 2) Predict CPU fraction via moving average over last dws points
            final double cpuPred = predictCpuFrac(vm, dws);

            // 3) If no history, fall back to MMT metric (RAM only) for this VM
            final double metric;
            if (Double.isNaN(cpuPred)) {
                metric = vm.getRam();
            } else {
                // 4) Map CPU -> Memory via per-type ratio
                final int t = getVmType(vm);               // must return 0..4 in your setup
                final double alpha = alphaForType(t);
                final double memFrac = Math.max(0.0, alpha * cpuPred); // inferred memory fraction

                // 5) Scalar pressure metric (MB)
                metric = memFrac * vm.getRam();
            }

            if (metric < minMetric) {
                minMetric = metric;
                vmToMigrate = vm;
            }
        }

        return vmToMigrate;
    }

    /**
     * Dynamic window per:
     *   dws = floor( (sum_{i=1..n} |VRU_i - VRU_{i-1}|) / n + gamma )
     * where VRU_i are utilization history samples in [0,1].
     * For n < 2, returns max(1, floor(gamma)).
     * Window is clamped to [1, n] so the moving average is well-defined.
     */
    protected int computeDynamicWindow(final PowerVm vm, final double gamma) {
        final List<Double> h = vm.getUtilizationHistory();
        if (h == null || h.isEmpty()) {
            return Math.max(1, (int)Math.floor(gamma));
        }

        final int n = h.size();
        if (n < 2) {
            return Math.max(1, (int)Math.floor(gamma));
        }

        double sumAbsDiff = 0.0;
        for (int i = 1; i < n; i++) {
            sumAbsDiff += Math.abs(h.get(i) - h.get(i - 1));
        }

        int dws = (int)Math.floor((sumAbsDiff / n) + gamma);
        if (dws < 1) dws = 1;
        if (dws > n) dws = n;
        return dws;
    }

    /** Moving average over the last 'window' points of CPU utilization history ([0..1]). */
    protected double predictCpuFrac(final PowerVm vm, final int window) {
        final List<Double> h = vm.getUtilizationHistory();
        if (h == null || h.isEmpty() || window < 1) {
            return Double.NaN;
        }

        final int n = h.size();
        final int k = Math.min(window, n);
        double sum = 0.0;
        for (int i = n - k; i < n; i++) {
            sum += h.get(i);
        }

        double avg = sum / k;
        if (avg < 0.0) avg = 0.0;
        if (avg > 1.0) avg = 1.0;
        return avg;
    }

    /**
     * Map a VM to your logical type index [0..4] using Constants.VM_MIPS/VM_PES.
     * It uses a relative MIPS tolerance and provides a helpful error if no match is found.
     *
     * NOTE: Ensure your Constants arrays are correctly configured:
     *   Constants.VM_TYPES == MEM_ALPHA.length
     *   Constants.VM_MIPS.length == Constants.VM_TYPES
     *   Constants.VM_PES.length  == Constants.VM_TYPES
     */
    protected static int getVmType(final Vm vm) {
        final int n = Constants.VM_TYPES;
        if (n <= 0 || Constants.VM_MIPS.length != n || Constants.VM_PES.length != n) {
            throw new IllegalStateException("VM type constants out of sync: check VM_TYPES/VM_MIPS/VM_PES");
        }

        final double vmMips = vm.getMips();      // per-PE MIPS
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
          + "Nearest type=%d (mips=%.3f, pes=%d). Check Constants.*.",
            vm.getId(), vmMips, vmPes, nearestIdx,
            Constants.VM_MIPS[nearestIdx], Constants.VM_PES[nearestIdx]));
    }

    /** Resolve per-type memory-to-CPU ratio safely. */
    private static double alphaForType(final int type) {
        if (type >= 0 && type < MEM_ALPHA.length) {
            final double a = MEM_ALPHA[type];
            return (a > 0.0 && !Double.isNaN(a)) ? a : DEFAULT_ALPHA;
        }
        return DEFAULT_ALPHA;
    }
}
