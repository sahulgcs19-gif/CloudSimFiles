package org.cloudbus.cloudsim.examples.power;

import org.cloudbus.cloudsim.power.models.PowerModel;
import org.cloudbus.cloudsim.power.models.PowerModelSpecPowerPm3XeonP8175M;
import org.cloudbus.cloudsim.power.models.PowerModelSpecPowerPm2AwsGraviton2;
import org.cloudbus.cloudsim.power.models.PowerModelSpecPowerPm1XeonE52686v4;

/**
 * If you are using any algorithms, policies or workload included in the power package, please cite
 * the following paper:
 *
 * Anton Beloglazov, and Rajkumar Buyya, "Optimal Online Deterministic Algorithms and Adaptive
 * Heuristics for Energy and Performance Efficient Dynamic Consolidation of Virtual Machines in
 * Cloud Data Centers", Concurrency and Computation: Practice and Experience (CCPE), Volume 24,
 * Issue 13, Pages: 1397-1420, John Wiley & Sons, Ltd, New York, USA, 2012
 *
 * @author Anton Beloglazov
 * @since Jan 6, 2012
 */
public class Constants {

	public final static boolean ENABLE_OUTPUT = true;
	public final static boolean OUTPUT_CSV    = false;

	public final static double SCHEDULING_INTERVAL = 300;
	public final static double SIMULATION_LIMIT = 24 * 60 * 60;

	public final static int CLOUDLET_LENGTH	= 2500 * (int) SIMULATION_LIMIT;
	public final static int CLOUDLET_PES	= 1;

	/*
	 * VM instance types:
	 *   High-Memory Extra Large Instance: 3.25 EC2 Compute Units, 8.55 GB // too much MIPS
	 *   High-CPU Medium Instance: 2.5 EC2 Compute Units, 0.85 GB
	 *   Extra Large Instance: 2 EC2 Compute Units, 3.75 GB
	 *   Small Instance: 1 EC2 Compute Unit, 1.7 GB
	 *   Micro Instance: 0.5 EC2 Compute Unit, 0.633 GB
	 *   We decrease the memory size two times to enable oversubscription
	 *
	 */
	// ===== VM catalog (5 types) =====
	public final static int VM_TYPES = 5;
	public static final double[] VM_UTIL_MIN = { 0.20, 0.50, 0.10, 0.10, 0.60 };
	public static final double[] VM_UTIL_MAX = { 0.70, 0.90, 0.50, 0.50, 0.90 };

	// Per-vCPU MIPS (same across all types for now; tune as needed)
	public final static int VCPU_MIPS = 250;

	// vCPUs (PEs) per type: VM1=8, VM2=4, VM3=4, VM4=4, VM5=64
	public final static int[] VM_PES  = { 8, 4,4, 4, 64 };

	// Per-PE MIPS for each type (uniform here; can individualize later)
	public final static int[] VM_MIPS = {
	    2500, 2500, 2500, 2500, 2500
	};

	// RAM in MB: VM1=64GB, VM2=32GB, VM3=32GB, VM4=64GB, VM5=512GB
	public final static int[] VM_RAM = {
	    64 * 1024,   // VM1: 64GB
	    32 * 1024,   // VM2: 32GB
	    32 * 1024,   // VM3: 32GB
	    64 * 1024,   // VM4: 64GB
	    512 * 1024   // VM5: 512GB
	};
	public final static int VM_BW		= 100000; // 100 Mbit/s
	public final static int VM_SIZE		= 2500; // 2.5 GB

	/*
	 * Host types:
	 *   HP ProLiant ML110 G4 (1 x [Xeon 3040 1860 MHz, 2 cores], 4GB)
	 *   HP ProLiant ML110 G5 (1 x [Xeon 3075 2660 MHz, 2 cores], 4GB)
	 *   We increase the memory size to enable over-subscription (x4)
	 */
	// ===== Physical Machines (PMs) =====
	public final static int HOST_TYPES  = 3;

	// vCPUs per host type (PEs ≈ vCPUs in CloudSim)
	public final static int[] HOST_PES  = { 18, 64, 16 };

	// Per-PE MIPS for each host type
	// (choose values to reflect arch differences; tune as you like)
	public final static int[] HOST_MIPS = {
	    2500,  // PM1: x86-based
	    2500,  // PM2: ARM-based 
	    2500   // PM3: x86-based
	};

	// RAM in MB
	public final static int[] HOST_RAM = {
	    144 * 1024,  // PM1: 144 GB
	    512 * 1024,  // PM2: 512 GB
	    128 * 1024   // PM3: 128 GB
	};

	public final static int HOST_BW		 = 10000000; // 1 Gbit/s
	public final static int HOST_STORAGE = 10000000; // 1 GB

	public final static PowerModel[] HOST_POWER = {
			new PowerModelSpecPowerPm1XeonE52686v4(), // idx 0 -> PM1 (18-core x86)
	        new PowerModelSpecPowerPm2AwsGraviton2(), // idx 1 -> PM2 (64-core ARM)
	        new PowerModelSpecPowerPm3XeonP8175M()    // idx 2 -> PM3 (16-core x86)
	};

}
