/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Collectors;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.examples.power.Constants;
import org.cloudbus.cloudsim.HostDynamicWorkload;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.lists.PowerVmList;
import org.cloudbus.cloudsim.util.ExecutionTimeMeasurer;

/**
 * The class of an abstract power-aware VM allocation policy that dynamically optimizes the VM
 * allocation using migration.
 * 
 * If you are using any algorithms, policies or workload included in the power package, please cite
 * the following paper:
 * 
 * Anton Beloglazov, and Rajkumar Buyya, "Optimal Online Deterministic Algorithms and Adaptive
 * Heuristics for Energy and Performance Efficient Dynamic Consolidation of Virtual Machines in
 * Cloud Data Centers", Concurrency and Computation: Practice and Experience (CCPE), Volume 24,
 * Issue 13, Pages: 1397-1420, John Wiley & Sons, Ltd, New York, USA, 2012
 * 
 * @author Anton Beloglazov
 * @since CloudSim Toolkit 3.0
 */
public abstract class PowerVmAllocationPolicyMigrationAbstract extends PowerVmAllocationPolicyAbstract {

	/** The vm selection policy. */
	private PowerVmSelectionPolicy vmSelectionPolicy;

	/** The saved allocation. */
	private final List<Map<String, Object>> savedAllocation = new ArrayList<Map<String, Object>>();

	/** The utilization history. */
	private final Map<Integer, List<Double>> utilizationHistory = new HashMap<Integer, List<Double>>();

	/** The metric history. */
	private final Map<Integer, List<Double>> metricHistory = new HashMap<Integer, List<Double>>();

	/** The time history. */
	private final Map<Integer, List<Double>> timeHistory = new HashMap<Integer, List<Double>>();

	/** The execution time history vm selection. */
	private final List<Double> executionTimeHistoryVmSelection = new LinkedList<Double>();

	/** The execution time history host selection. */
	private final List<Double> executionTimeHistoryHostSelection = new LinkedList<Double>();

	/** The execution time history vm reallocation. */
	private final List<Double> executionTimeHistoryVmReallocation = new LinkedList<Double>();

	/** The execution time history total. */
	private final List<Double> executionTimeHistoryTotal = new LinkedList<Double>();
	private static final double B1 = 0.30; // β1 for ΔU
	private static final double B2 = 0.15; // β2 for C_cycle
	private static final double B3 = 0.20; // β3 for U_Per
	private static final double B4 = 0.20; // β4 for U_mem
	private static final double B5 = 0.15; // β5 for SLA_perf

	private static final double U_MIN = 0.80;                 // Territorial band lower
	private static final double U_MAX = 0.90;                 // Guided/Territorial CPU safety
	private static final double U_THRESHOLD_FOR_UPER = U_MAX; // threshold used in U_Per
	private static final double MEM_THRESHOLD_FRAC = 0.95;    // memory safety bound
	private static final double GAMMA_CYCLE = 1.0;

	private static final double[] MEM_ALPHA = { 1.00, 0.70, 2.00, 1.80, 0.80 };
	private static final double DEFAULT_ALPHA = 1.0;


	/**
	 * Instantiates a new power vm allocation policy migration abstract.
	 * 
	 * @param hostList the host list
	 * @param vmSelectionPolicy the vm selection policy
	 */
	public PowerVmAllocationPolicyMigrationAbstract(
			List<? extends Host> hostList,
			PowerVmSelectionPolicy vmSelectionPolicy) {
		super(hostList);
		vmSelectionPolicy = new MySelectionPolicy();
		setVmSelectionPolicy(vmSelectionPolicy);
	}

	/**
	 * Optimize allocation of the VMs according to current utilization.
	 * 
	 * @param vmList the vm list
	 * 
	 * @return the array list< hash map< string, object>>
	 */
	@Override
	public List<Map<String, Object>> optimizeAllocation(List<? extends Vm> vmList) {
		ExecutionTimeMeasurer.start("optimizeAllocationTotal");

		ExecutionTimeMeasurer.start("optimizeAllocationHostSelection");
		List<PowerHostUtilizationHistory> overUtilizedHosts = getOverUtilizedHosts();
		getExecutionTimeHistoryHostSelection().add(
				ExecutionTimeMeasurer.end("optimizeAllocationHostSelection"));

		printOverUtilizedHosts(overUtilizedHosts);

		saveAllocation();

		ExecutionTimeMeasurer.start("optimizeAllocationVmSelection");
		List<? extends Vm> vmsToMigrate = getVmsToMigrateFromHosts(overUtilizedHosts);
		getExecutionTimeHistoryVmSelection().add(ExecutionTimeMeasurer.end("optimizeAllocationVmSelection"));

		Log.printLine("Reallocation of VMs from the over-utilized hosts:");
		ExecutionTimeMeasurer.start("optimizeAllocationVmReallocation");
		List<Map<String, Object>> migrationMap = getNewVmPlacement(vmsToMigrate, new HashSet<Host>(
				overUtilizedHosts));
		getExecutionTimeHistoryVmReallocation().add(
				ExecutionTimeMeasurer.end("optimizeAllocationVmReallocation"));
		Log.printLine();

		migrationMap.addAll(getMigrationMapFromUnderUtilizedHosts(overUtilizedHosts));

		restoreAllocation();

		getExecutionTimeHistoryTotal().add(ExecutionTimeMeasurer.end("optimizeAllocationTotal"));

		return migrationMap;
	}

	/**
	 * Gets the migration map from under utilized hosts.
	 * 
	 * @param overUtilizedHosts the over utilized hosts
	 * @return the migration map from under utilized hosts
	 */
	protected List<Map<String, Object>> getMigrationMapFromUnderUtilizedHosts(
			List<PowerHostUtilizationHistory> overUtilizedHosts) {
		List<Map<String, Object>> migrationMap = new LinkedList<Map<String, Object>>();
		List<PowerHost> switchedOffHosts = getSwitchedOffHosts();

		// over-utilized hosts + hosts that are selected to migrate VMs to from over-utilized hosts
		Set<PowerHost> excludedHostsForFindingUnderUtilizedHost = new HashSet<PowerHost>();
		excludedHostsForFindingUnderUtilizedHost.addAll(overUtilizedHosts);
		excludedHostsForFindingUnderUtilizedHost.addAll(switchedOffHosts);
		excludedHostsForFindingUnderUtilizedHost.addAll(extractHostListFromMigrationMap(migrationMap));

		// over-utilized + under-utilized hosts
		Set<PowerHost> excludedHostsForFindingNewVmPlacement = new HashSet<PowerHost>();
		excludedHostsForFindingNewVmPlacement.addAll(overUtilizedHosts);
		excludedHostsForFindingNewVmPlacement.addAll(switchedOffHosts);

		int numberOfHosts = getHostList().size();

		while (true) {
			if (numberOfHosts == excludedHostsForFindingUnderUtilizedHost.size()) {
				break;
			}

			PowerHost underUtilizedHost = getUnderUtilizedHost(excludedHostsForFindingUnderUtilizedHost);
			if (underUtilizedHost == null) {
				break;
			}

			Log.printLine("Under-utilized host: host #" + underUtilizedHost.getId() + "\n");

			excludedHostsForFindingUnderUtilizedHost.add(underUtilizedHost);
			excludedHostsForFindingNewVmPlacement.add(underUtilizedHost);

			List<? extends Vm> vmsToMigrateFromUnderUtilizedHost = getVmsToMigrateFromUnderUtilizedHost(underUtilizedHost);
			if (vmsToMigrateFromUnderUtilizedHost.isEmpty()) {
				continue;
			}

			Log.print("Reallocation of VMs from the under-utilized host: ");
			if (!Log.isDisabled()) {
				for (Vm vm : vmsToMigrateFromUnderUtilizedHost) {
					Log.print(vm.getId() + " ");
				}
			}
			Log.printLine();

			List<Map<String, Object>> newVmPlacement = getNewVmPlacementFromUnderUtilizedHost(
					vmsToMigrateFromUnderUtilizedHost,
					excludedHostsForFindingNewVmPlacement);

			excludedHostsForFindingUnderUtilizedHost.addAll(extractHostListFromMigrationMap(newVmPlacement));

			migrationMap.addAll(newVmPlacement);
			Log.printLine();
		}

		return migrationMap;
	}

	/**
	 * Prints the over utilized hosts.
	 * 
	 * @param overUtilizedHosts the over utilized hosts
	 */
	protected void printOverUtilizedHosts(List<PowerHostUtilizationHistory> overUtilizedHosts) {
		if (!Log.isDisabled()) {
			Log.printLine("Over-utilized hosts:");
			for (PowerHostUtilizationHistory host : overUtilizedHosts) {
				Log.printLine("Host #" + host.getId());
			}
			Log.printLine();
		}
	}

	/**
	 * Find host for vm.
	 * 
	 * @param vm the vm
	 * @param excludedHosts the excluded hosts
	 * @return the power host
	 */


	public PowerHost findHostForVm(Vm vm, Set<? extends Host> excludedHosts) {
	    final List<PowerHost> candidates = new ArrayList<>();
	    for (PowerHost h : this.<PowerHost>getHostList()) {
	        if (!excludedHosts.contains(h) && h.isSuitableForVm(vm) && getPowerAfterAllocation(h, vm) >= 0) {
	            candidates.add(h);
	        }
	    }
	    if (candidates.isEmpty()) return null;
	    if (candidates.size() == 1) return candidates.get(0);

	    // Compute F_fitness(eq.19) and predictions used by Guided/Territorial phases
	    final Map<PowerHost, Double> ffit = new HashMap<>();
	    final Map<PowerHost, Double> uHat = new HashMap<>();   // predicted host CPU fraction after allocation
	    final Map<PowerHost, Double> mHat = new HashMap<>();   // predicted host MEM fraction after allocation

	    double fMin = Double.POSITIVE_INFINITY, fMax = 0.0;
	    for (PowerHost h : candidates) {
	        final double f = evaluateFitness(h, vm);     // (Algorithm step: “Compute F_fitness for all PMs”)
	        ffit.put(h, f);
	        if (f < fMin) fMin = f;
	        if (f > fMax) fMax = f;

	        uHat.put(h, predictHostCpuFracAfter(h, vm)); // used in Guided/Territorial constraints
	        mHat.put(h, predictHostMemFracAfter(h, vm));
	    }

	    // ======================== Guided Foraging (eq. 21) ========================
	    // “Select PM with best resource availability via eq (21)”
	    // Here: choose MIN fitness among PMs passing CPU & Memory safety checks.
	    PowerHost guidedBest = null;
	    double guidedBestVal = Double.POSITIVE_INFINITY;
	    for (PowerHost h : candidates) {
	        if (uHat.get(h) <= U_MAX && mHat.get(h) <= MEM_THRESHOLD_FRAC) {
	            final double f = ffit.get(h);
	            if (f < guidedBestVal) { guidedBestVal = f; guidedBest = h; }
	        }
	    }
	    if (guidedBest != null) return guidedBest;


	    // “Explore PM neighbors” → restrict to cluster C = {PM | U∈[U_MIN,U_MAX]} and pick max/ min as per policy.
	    // We keep it consistent with the earlier MIN-fitness selection inside the local band.
	    PowerHost territorialBest = null;
	    double territorialBestVal = Double.POSITIVE_INFINITY;
	    for (PowerHost h : candidates) {
	        final double u = uHat.get(h);
	        if (u >= U_MIN && u <= U_MAX) {
	            final double f = ffit.get(h);
	            if (f < territorialBestVal) { territorialBestVal = f; territorialBest = h; }
	        }
	    }
	    if (territorialBest != null) return territorialBest;


	    // “Replace worst PM using eq (23)” → global exploration by random target in [F_min, F_max]
	    final double r = fMin + ThreadLocalRandom.current().nextDouble() * (fMax - fMin);
	    PowerHost closestAbove = null;
	    double closestAboveVal = Double.POSITIVE_INFINITY;

	    PowerHost globalBest = null;
	    double globalBestVal = Double.POSITIVE_INFINITY;

	    for (PowerHost h : candidates) {
	        final double f = ffit.get(h);
	        if (f >= r && f < closestAboveVal) { closestAboveVal = f; closestAbove = h; }
	        if (f < globalBestVal) { globalBestVal = f; globalBest = h; }
	    }
	    return (closestAbove != null) ? closestAbove : globalBest;
	}

	private double evaluateFitness(final PowerHost host, final Vm vm) {
	    if (getPowerAfterAllocation(host, vm) < 0) return Double.POSITIVE_INFINITY;

	    // ΔU (eq. 20): average absolute successive change of host utilization history
	    final double deltaU = computeDeltaU(host);                            // promotes stable PMs

	    // C_cycle: dynamic window size for the migrating VM: dws = floor(Σ|ΔVRU|/n + γ)
	    final double cCycle = (vm instanceof PowerVm)
	            ? computeDynamicWindow((PowerVm) vm, GAMMA_CYCLE) : GAMMA_CYCLE;

	    // U_Per (eq. 24 notion): prospective perf. violation if CPU after allocation exceeds threshold
	    final double uAfter = predictHostCpuFracAfter(host, vm);
	    final double uPer = (uAfter > U_THRESHOLD_FOR_UPER) ? 1.0 : 0.0;

	    // U_mem: predicted memory fraction after allocation (must be in proportion to CPU)
	    final double uMem = predictHostMemFracAfter(host, vm);

	    // SLA_perf: 1 if host would be overutilized after placing vm (degradation risk), else 0
	    final double slaPerf = isHostOverUtilizedAfterAllocation(host, vm) ? 1.0 : 0.0;

	    return B1*deltaU + B2*cCycle + B3*uPer + B4*uMem + B5*slaPerf;
	}


	private double predictHostCpuFracAfter(final PowerHost host, final Vm vmToPlace) {
	    double predictedMips = 0.0;

	    for (Vm v : host.getVmList()) {
	        if (!(v instanceof PowerVm)) continue;
	        final PowerVm pvm = (PowerVm) v;
	        final int w = computeDynamicWindow(pvm, GAMMA_CYCLE);
	        final double cpuFrac = wma(pvm.getUtilizationHistory(), w);
	        final double vmMax = pvm.getMips() * pvm.getNumberOfPes();
	        predictedMips += (Double.isNaN(cpuFrac) ? 0.0 : cpuFrac) * vmMax;
	    }

	    if (vmToPlace instanceof PowerVm) {
	        final PowerVm mv = (PowerVm) vmToPlace;
	        final int w = computeDynamicWindow(mv, GAMMA_CYCLE);
	        double cpuFrac = wma(mv.getUtilizationHistory(), w);
	        if (Double.isNaN(cpuFrac)) cpuFrac = currentRequestFrac(mv);
	        predictedMips += cpuFrac * (mv.getMips() * mv.getNumberOfPes());
	    } else {
	        predictedMips += vmToPlace.getCurrentRequestedTotalMips();
	    }

	    return host.getTotalMips() > 0 ? predictedMips / host.getTotalMips() : 0.0;
	}

	private double predictHostMemFracAfter(final PowerHost host, final Vm vmToPlace) {
	    double memMb = 0.0;

	    for (Vm v : host.getVmList()) {
	        if (!(v instanceof PowerVm)) continue;
	        final PowerVm pvm = (PowerVm) v;
	        final int w = computeDynamicWindow(pvm, GAMMA_CYCLE);
	        final double cpuFrac = wma(pvm.getUtilizationHistory(), w);
	        final double memFrac = inferMemFracFromCpu(pvm, Double.isNaN(cpuFrac) ? 0.0 : cpuFrac);
	        memMb += memFrac * pvm.getRam();
	    }

	    if (vmToPlace instanceof PowerVm) {
	        final PowerVm mv = (PowerVm) vmToPlace;
	        final int w = computeDynamicWindow(mv, GAMMA_CYCLE);
	        double cpuFrac = wma(mv.getUtilizationHistory(), w);
	        if (Double.isNaN(cpuFrac)) cpuFrac = currentRequestFrac(mv);
	        final double memFrac = inferMemFracFromCpu(mv, cpuFrac);
	        memMb += memFrac * mv.getRam();
	    } else {
	        final double cpuFrac = Math.min(1.0,
	                vmToPlace.getCurrentRequestedTotalMips() /
	               (Math.max(1.0, vmToPlace.getMips() * vmToPlace.getNumberOfPes())));
	        memMb += DEFAULT_ALPHA * cpuFrac * vmToPlace.getRam();
	    }

	    return host.getRam() > 0 ? memMb / host.getRam() : 0.0;
	}

	/* dws = floor( (Σ|VRU_i - VRU_{i-1}|)/n + γ ), clamped to [1, n] */
	private int computeDynamicWindow(final PowerVm vm, final double gamma) {
	    final List<Double> h = vm.getUtilizationHistory();
	    if (h == null || h.isEmpty()) return Math.max(1, (int)Math.floor(gamma));
	    final int n = h.size();
	    if (n < 2) return Math.max(1, (int)Math.floor(gamma));
	    double sumAbs = 0.0;
	    for (int i = 1; i < n; i++) sumAbs += Math.abs(h.get(i) - h.get(i-1));
	    int dws = (int)Math.floor(sumAbs / n + gamma);
	    if (dws < 1) dws = 1;
	    if (dws > n) dws = n;
	    return dws;
	}

	/* Weighted moving average over last 'window' samples (weights 1..window; heavier recent). */
	private double wma(final List<Double> hist, final int window) {
	    if (hist == null || hist.isEmpty() || window < 1) return Double.NaN;
	    final int n = hist.size();
	    final int k = Math.min(window, n);
	    double num = 0.0, den = 0.0;
	    for (int i = 0; i < k; i++) {
	        final int idx = n - 1 - i;
	        final double w = (i + 1);
	        num += w * hist.get(idx);
	        den += w;
	    }
	    double v = (den > 0 ? num / den : Double.NaN);
	    if (Double.isNaN(v)) return v;
	    if (v < 0) v = 0;
	    if (v > 1) v = 1;
	    return v;
	}

	/* ΔU (eq. 20): average absolute successive change in host utilization history */
	private double computeDeltaU(final PowerHost host) {
	    if (!(host instanceof PowerHostUtilizationHistory)) return 0.0;
	    final double[] h = ((PowerHostUtilizationHistory)host).getUtilizationHistory();
	    if (h == null || h.length < 2) return 0.0;
	    double sum = 0.0;
	    for (int i = 1; i < h.length; i++) sum += Math.abs(h[i] - h[i-1]);
	    return sum / (h.length - 1);
	}

	/* Map CPU→MEM (same as earlier): memFrac = MEM_ALPHA[type] * cpuFrac (not capped). */
	private double inferMemFracFromCpu(final Vm vm, final double cpuFrac) {
	    final int t = getVmType(vm);       // 0..4
	    final double alpha = alphaForType(t);
	    final double x = alpha * Math.max(0.0, cpuFrac);
	    return (x < 0.0) ? 0.0 : x;
	}

	private double currentRequestFrac(final PowerVm vm) {
	    final double req = vm.getCurrentRequestedTotalMips();
	    final double cap = vm.getMips() * vm.getNumberOfPes();
	    if (cap <= 0) return 0.0;
	    double f = req / cap;
	    if (f < 0) f = 0;
	    if (f > 1) f = 1;
	    return f;
	}

	/* Resolve VM type by (MIPS, PEs) like your earlier helper; throws if no exact match. */
	private int getVmType(final Vm vm) {
	    final int n = Constants.VM_TYPES;
	    if (n <= 0 || Constants.VM_MIPS.length != n || Constants.VM_PES.length != n) {
	        throw new IllegalStateException("VM type constants out of sync.");
	    }
	    final double vmMips = vm.getMips();
	    final int vmPes = vm.getNumberOfPes();
	    final double MIPS_EPS_REL = 1e-6;

	    int nearestIdx = -1;
	    double bestCost = Double.POSITIVE_INFINITY;

	    for (int t = 0; t < n; t++) {
	        final double baseMips = Constants.VM_MIPS[t];
	        final int basePes = Constants.VM_PES[t];
	        final double delta = Math.abs(vmMips - baseMips);
	        final double tol = Math.max(Math.abs(baseMips), 1.0) * MIPS_EPS_REL;
	        final boolean mipsMatch = delta <= tol;
	        final boolean pesMatch = vmPes == basePes;
	        if (mipsMatch && pesMatch) return t;

	        final double norm = delta / Math.max(baseMips, 1.0);
	        final double cost = norm*norm + 0.25 * (vmPes - basePes) * (vmPes - basePes);
	        if (cost < bestCost) { bestCost = cost; nearestIdx = t; }
	    }
	    throw new IllegalArgumentException(String.format(
	        "Cannot infer VM type id=%d (mips=%.2f pes=%d), nearest=%d",
	        vm.getId(), vmMips, vmPes, nearestIdx));
	}

	private double alphaForType(final int type) {
	    if (type >= 0 && type < MEM_ALPHA.length) {
	        final double a = MEM_ALPHA[type];
	        return (a > 0.0 && !Double.isNaN(a)) ? a : DEFAULT_ALPHA;
	    }
	    return DEFAULT_ALPHA;
	}




	/**
	 * Checks if is host over utilized after allocation.
	 * 
	 * @param host the host
	 * @param vm the vm
	 * @return true, if is host over utilized after allocation
	 */
	protected boolean isHostOverUtilizedAfterAllocation(PowerHost host, Vm vm) {
		boolean isHostOverUtilizedAfterAllocation = true;
		if (host.vmCreate(vm)) {
			isHostOverUtilizedAfterAllocation = isHostOverUtilized(host);
			host.vmDestroy(vm);
		}
		return isHostOverUtilizedAfterAllocation;
	}

	/**
	 * Find host for vm.
	 * 
	 * @param vm the vm
	 * @return the power host
	 */
	@Override
	public PowerHost findHostForVm(Vm vm) {
		Set<Host> excludedHosts = new HashSet<Host>();
		if (vm.getHost() != null) {
			excludedHosts.add(vm.getHost());
		}
		return findHostForVm(vm, excludedHosts);
	}

	/**
	 * Extract host list from migration map.
	 * 
	 * @param migrationMap the migration map
	 * @return the list
	 */
	protected List<PowerHost> extractHostListFromMigrationMap(List<Map<String, Object>> migrationMap) {
		List<PowerHost> hosts = new LinkedList<PowerHost>();
		for (Map<String, Object> map : migrationMap) {
			hosts.add((PowerHost) map.get("host"));
		}
		return hosts;
	}

	/**
	 * Gets the new vm placement.
	 * 
	 * @param vmsToMigrate the vms to migrate
	 * @param excludedHosts the excluded hosts
	 * @return the new vm placement
	 */
	protected List<Map<String, Object>> getNewVmPlacement(
			List<? extends Vm> vmsToMigrate,
			Set<? extends Host> excludedHosts) {
		List<Map<String, Object>> migrationMap = new LinkedList<Map<String, Object>>();
		PowerVmList.sortByCpuUtilization(vmsToMigrate);
		for (Vm vm : vmsToMigrate) {
			PowerHost allocatedHost = findHostForVm(vm, excludedHosts);
			if (allocatedHost != null) {
				allocatedHost.vmCreate(vm);
				Log.printLine("VM #" + vm.getId() + " allocated to host #" + allocatedHost.getId());

				Map<String, Object> migrate = new HashMap<String, Object>();
				migrate.put("vm", vm);
				migrate.put("host", allocatedHost);
				migrationMap.add(migrate);
			}
		}
		return migrationMap;
	}

	/**
	 * Gets the new vm placement from under utilized host.
	 * 
	 * @param vmsToMigrate the vms to migrate
	 * @param excludedHosts the excluded hosts
	 * @return the new vm placement from under utilized host
	 */
	protected List<Map<String, Object>> getNewVmPlacementFromUnderUtilizedHost(
			List<? extends Vm> vmsToMigrate,
			Set<? extends Host> excludedHosts) {
		List<Map<String, Object>> migrationMap = new LinkedList<Map<String, Object>>();
		PowerVmList.sortByCpuUtilization(vmsToMigrate);
		for (Vm vm : vmsToMigrate) {
			PowerHost allocatedHost = findHostForVm(vm, excludedHosts);
			if (allocatedHost != null) {
				allocatedHost.vmCreate(vm);
				Log.printLine("VM #" + vm.getId() + " allocated to host #" + allocatedHost.getId());

				Map<String, Object> migrate = new HashMap<String, Object>();
				migrate.put("vm", vm);
				migrate.put("host", allocatedHost);
				migrationMap.add(migrate);
			} else {
				Log.printLine("Not all VMs can be reallocated from the host, reallocation cancelled");
				for (Map<String, Object> map : migrationMap) {
					((Host) map.get("host")).vmDestroy((Vm) map.get("vm"));
				}
				migrationMap.clear();
				break;
			}
		}
		return migrationMap;
	}

	/**
	 * Gets the vms to migrate from hosts.
	 * 
	 * @param overUtilizedHosts the over utilized hosts
	 * @return the vms to migrate from hosts
	 */
	protected
			List<? extends Vm>
			getVmsToMigrateFromHosts(List<PowerHostUtilizationHistory> overUtilizedHosts) {
		List<Vm> vmsToMigrate = new LinkedList<Vm>();
		for (PowerHostUtilizationHistory host : overUtilizedHosts) {
			while (true) {
				Vm vm = getVmSelectionPolicy().getVmToMigrate(host);
				if (vm == null) {
					break;
				}
				vmsToMigrate.add(vm);
				host.vmDestroy(vm);
				if (!isHostOverUtilized(host)) {
					break;
				}
			}
		}
		return vmsToMigrate;
	}

	/**
	 * Gets the vms to migrate from under utilized host.
	 * 
	 * @param host the host
	 * @return the vms to migrate from under utilized host
	 */
	protected List<? extends Vm> getVmsToMigrateFromUnderUtilizedHost(PowerHost host) {
		List<Vm> vmsToMigrate = new LinkedList<Vm>();
		for (Vm vm : host.getVmList()) {
			if (!vm.isInMigration()) {
				vmsToMigrate.add(vm);
			}
		}
		return vmsToMigrate;
	}

	/**
	 * Gets the over utilized hosts.
	 * 
	 * @return the over utilized hosts
	 */
	protected List<PowerHostUtilizationHistory> getOverUtilizedHosts() {
		List<PowerHostUtilizationHistory> overUtilizedHosts = new LinkedList<PowerHostUtilizationHistory>();
		for (PowerHostUtilizationHistory host : this.<PowerHostUtilizationHistory> getHostList()) {
			if (isHostOverUtilized(host)) {
				overUtilizedHosts.add(host);
			}
		}
		return overUtilizedHosts;
	}

	/**
	 * Gets the switched off host.
	 * 
	 * @return the switched off host
	 */
	protected List<PowerHost> getSwitchedOffHosts() {
		List<PowerHost> switchedOffHosts = new LinkedList<PowerHost>();
		for (PowerHost host : this.<PowerHost> getHostList()) {
			if (host.getUtilizationOfCpu() == 0) {
				switchedOffHosts.add(host);
			}
		}
		return switchedOffHosts;
	}

	/**
	 * Gets the under utilized host.
	 * 
	 * @param excludedHosts the excluded hosts
	 * @return the under utilized host
	 */
	protected PowerHost getUnderUtilizedHost(Set<? extends Host> excludedHosts) {
		double minUtilization = 0.30;	
		PowerHost underUtilizedHost = null;
		for (PowerHost host : this.<PowerHost> getHostList()) {
			if (excludedHosts.contains(host)) {
				continue;
			}
			double utilization = host.getUtilizationOfCpu();
			if (utilization > 0 && utilization < minUtilization
					&& !areAllVmsMigratingOutOrAnyVmMigratingIn(host)) {
				minUtilization = utilization;
				underUtilizedHost = host;
			}
		}
		return underUtilizedHost;
	}

	/**
	 * Checks whether all vms are in migration.
	 * 
	 * @param host the host
	 * @return true, if successful
	 */
	protected boolean areAllVmsMigratingOutOrAnyVmMigratingIn(PowerHost host) {
		for (PowerVm vm : host.<PowerVm> getVmList()) {
			if (!vm.isInMigration()) {
				return false;
			}
			if (host.getVmsMigratingIn().contains(vm)) {
				return true;
			}
		}
		return true;
	}

	/**
	 * Checks if is host over utilized.
	 * 
	 * @param host the host
	 * @return true, if is host over utilized
	 */
	protected abstract boolean isHostOverUtilized(PowerHost host);

	/**
	 * Adds the history value.
	 * 
	 * @param host the host
	 * @param metric the metric
	 */
	protected void addHistoryEntry(HostDynamicWorkload host, double metric) {
		int hostId = host.getId();
		if (!getTimeHistory().containsKey(hostId)) {
			getTimeHistory().put(hostId, new LinkedList<Double>());
		}
		if (!getUtilizationHistory().containsKey(hostId)) {
			getUtilizationHistory().put(hostId, new LinkedList<Double>());
		}
		if (!getMetricHistory().containsKey(hostId)) {
			getMetricHistory().put(hostId, new LinkedList<Double>());
		}
		if (!getTimeHistory().get(hostId).contains(CloudSim.clock())) {
			getTimeHistory().get(hostId).add(CloudSim.clock());
			getUtilizationHistory().get(hostId).add(host.getUtilizationOfCpu());
			getMetricHistory().get(hostId).add(metric);
		}
	}

	/**
	 * Save allocation.
	 */
	protected void saveAllocation() {
		getSavedAllocation().clear();
		for (Host host : getHostList()) {
			for (Vm vm : host.getVmList()) {
				if (host.getVmsMigratingIn().contains(vm)) {
					continue;
				}
				Map<String, Object> map = new HashMap<String, Object>();
				map.put("host", host);
				map.put("vm", vm);
				getSavedAllocation().add(map);
			}
		}
	}

	/**
	 * Restore allocation.
	 */
	protected void restoreAllocation() {
		for (Host host : getHostList()) {
			host.vmDestroyAll();
			host.reallocateMigratingInVms();
		}
		for (Map<String, Object> map : getSavedAllocation()) {
			Vm vm = (Vm) map.get("vm");
			PowerHost host = (PowerHost) map.get("host");
			if (!host.vmCreate(vm)) {
				Log.printLine("Couldn't restore VM #" + vm.getId() + " on host #" + host.getId());
				System.exit(0);
			}
			getVmTable().put(vm.getUid(), host);
		}
	}

	/**
	 * Gets the power after allocation.
	 * 
	 * @param host the host
	 * @param vm the vm
	 * 
	 * @return the power after allocation
	 */
	protected double getPowerAfterAllocation(PowerHost host, Vm vm) {
	    double power = 0;
	    try {
	        double utilization = getMaxUtilizationAfterAllocation(host, vm);

	        // Clamp utilization between 0 and 1
	        if (utilization > 1) {
	            utilization = 1.0;
	        } else if (utilization < 0) {
	            utilization = 0.0;
	        }

	        power = host.getPowerModel().getPower(utilization);
	    } catch (Exception e) {
	        e.printStackTrace();
	        System.exit(0);
	    }
	    return power;
	}


	/**
	 * Gets the power after allocation. We assume that load is balanced between PEs. The only
	 * restriction is: VM's max MIPS < PE's MIPS
	 * 
	 * @param host the host
	 * @param vm the vm
	 * 
	 * @return the power after allocation
	 */
	protected double getMaxUtilizationAfterAllocation(PowerHost host, Vm vm) {
		double requestedTotalMips = vm.getCurrentRequestedTotalMips();
		double hostUtilizationMips = getUtilizationOfCpuMips(host);
		double hostPotentialUtilizationMips = hostUtilizationMips + requestedTotalMips;
		double pePotentialUtilization = hostPotentialUtilizationMips / host.getTotalMips();
		return pePotentialUtilization;
	}
	
	/**
	 * Gets the utilization of the CPU in MIPS for the current potentially allocated VMs.
	 *
	 * @param host the host
	 *
	 * @return the utilization of the CPU in MIPS
	 */
	protected double getUtilizationOfCpuMips(PowerHost host) {
		double hostUtilizationMips = 0;
		for (Vm vm2 : host.getVmList()) {
			if (host.getVmsMigratingIn().contains(vm2)) {
				// calculate additional potential CPU usage of a migrating in VM
				hostUtilizationMips += host.getTotalAllocatedMipsForVm(vm2) * 0.9 / 0.1;
			}
			hostUtilizationMips += host.getTotalAllocatedMipsForVm(vm2);
		}
		return hostUtilizationMips;
	}

	/**
	 * Gets the saved allocation.
	 * 
	 * @return the saved allocation
	 */
	protected List<Map<String, Object>> getSavedAllocation() {
		return savedAllocation;
	}

	/**
	 * Sets the vm selection policy.
	 * 
	 * @param vmSelectionPolicy the new vm selection policy
	 */
	protected void setVmSelectionPolicy(PowerVmSelectionPolicy vmSelectionPolicy) {
		this.vmSelectionPolicy = vmSelectionPolicy;
	}

	/**
	 * Gets the vm selection policy.
	 * 
	 * @return the vm selection policy
	 */
	protected PowerVmSelectionPolicy getVmSelectionPolicy() {
		return vmSelectionPolicy;
	}

	/**
	 * Gets the utilization history.
	 * 
	 * @return the utilization history
	 */
	public Map<Integer, List<Double>> getUtilizationHistory() {
		return utilizationHistory;
	}

	/**
	 * Gets the metric history.
	 * 
	 * @return the metric history
	 */
	public Map<Integer, List<Double>> getMetricHistory() {
		return metricHistory;
	}

	/**
	 * Gets the time history.
	 * 
	 * @return the time history
	 */
	public Map<Integer, List<Double>> getTimeHistory() {
		return timeHistory;
	}

	/**
	 * Gets the execution time history vm selection.
	 * 
	 * @return the execution time history vm selection
	 */
	public List<Double> getExecutionTimeHistoryVmSelection() {
		return executionTimeHistoryVmSelection;
	}

	/**
	 * Gets the execution time history host selection.
	 * 
	 * @return the execution time history host selection
	 */
	public List<Double> getExecutionTimeHistoryHostSelection() {
		return executionTimeHistoryHostSelection;
	}

	/**
	 * Gets the execution time history vm reallocation.
	 * 
	 * @return the execution time history vm reallocation
	 */
	public List<Double> getExecutionTimeHistoryVmReallocation() {
		return executionTimeHistoryVmReallocation;
	}

	/**
	 * Gets the execution time history total.
	 * 
	 * @return the execution time history total
	 */
	public List<Double> getExecutionTimeHistoryTotal() {
		return executionTimeHistoryTotal;
	}

}
