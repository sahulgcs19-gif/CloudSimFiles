CloudSimFiles
My Files needs to execute the program i proposed in my article
AAHA / MOHD — CloudSim 3.0.3 
This package adds:
MOHD  : Multi objective Overutilized Host Detection
AAHA based VM Placement 
Memory aware VM Selection
PM Power Models  : PM1/PM2/PM3 examples


Prerequisites
OS: Windows / macOS / Linux
JDK: Java 8 (recommended). Set JAVA_HOME and ensure java -version works.
Build (choose one):
IDE: IntelliJ IDEA or Eclipse.


Download CloudSim 3.0.3
Download the release zip: cloudsim-3.0.3.zip from
https://github.com/Cloudslab/cloudsim/releases/tag/cloudsim-3.0.3
Please Check the video to follow the steps to add cloudsim-3.0.3 in eclipse - https://www.youtube.com/watch?v=OZRbkkEuQMI


Replace/add the following files
Step 1: Power models (PM1–PM3) (Add These Files)a
Package: org.cloudbus.cloudsim.power.models
Path: cloudsim-3.0.3/src/org/cloudbus/cloudsim/power/models/
PowerModelSpecPowerPm1XeonE52686v4.java — PM1
PowerModelSpecPowerPm2AwsGraviton2.java — PM2
PowerModelSpecPowerPm3XeonP8175M.java — PM3

Step 2: Workload sizes (randomized experiments - Replace these files)
Package: org.cloudbus.cloudsim.examples.power.random
Path: cloudsim-3.0.3/examples/org/cloudbus/cloudsim/examples/power/random/
RandomConstants.java — number of VMs/PMs
Mohd.java - To execute the main file

Step 3: Helpers & constants (Please Replace these Files)
Package: org.cloudbus.cloudsim.examples.power
Path: cloudsim-3.0.3/examples/org/cloudbus/cloudsim/examples/power/
Helper.java — SLA violation utilities
Constants.java — VM & PM properties (arrays for MIPS, PEs, RAM, STORAGE, etc.), VM_TYPES = 5, and your MEM_Utilization

Step 4: VM selection & Host detection policies (Pease add these Files)
Package: org.cloudbus.cloudsim.power
Path: cloudsim-3.0.3/src/org/cloudbus/cloudsim/power/
PowerVmSelectionPolicyMinimumPredictedMemory.java — memory-aware VM selection
PowerVmAllocationPolicyMigrationMOHD.java — MOHD host over-utilization policy 

Step 5: Runner (Replace this File)
Package: org.cloudbus.cloudsim.examples.power
Path: cloudsim-3.0.3/examples/org/cloudbus/cloudsim/examples/power/
RunnerAbstract.java — already exists in CloudSim Mohd runner will use it.

Step 6: VM Placement Technique (Replace this file)
Package: org.cloudbus.cloudsim.power; 
Path: cloudsim-3.0.3/src/org/cloudbus/cloudsim/power/
PowerVmAllocationPolicyMigrationAbstract

Now You can build and Run the Project.
