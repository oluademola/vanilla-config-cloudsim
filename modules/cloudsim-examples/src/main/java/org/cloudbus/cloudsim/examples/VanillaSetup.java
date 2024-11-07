/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation
 *               of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.examples;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.opencsv.CSVReader;
import java.io.FileReader;
import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.io.FileInputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

/** Lab 05 solution. */
public class VanillaSetup {

	private static List<Cloudlet> cloudletList;
	private static List<Vm> vmlist;

	public static void main(String[] args) {

		try {
			// Initialize CloudSim
			int num_user = 1;   // number of cloud users
			Calendar calendar = Calendar.getInstance();
			boolean trace_flag = false;

			CloudSim.init(num_user, calendar, trace_flag);

			// Create Datacenter
			Datacenter datacenter = createDatacenter("Datacenter");

			// Create Broker
			DatacenterBroker broker = createBroker();
			int brokerId = broker.getId();

			// Load Azure serverless functions dataset (adjust the file path as needed)
			String invocationsFile = "dataset/processed_invocations_per_function.csv";
			String durationsFile = "dataset/processed_function_durations.csv";
			String memoryFile = "dataset/processed_app_memory.csv";
			List<FunctionData> functionsData = loadProcessedData(invocationsFile, durationsFile, memoryFile);

			// VM creation and submission
			vmlist = createVM(brokerId, 20, 2000, 4096, 1000, 20000);  // Customize VM count and specs as needed
			broker.submitVmList(vmlist);

			// Set up batching parameters
			int batchSize = 100;  // Adjust based on your system's capacity and dataset size
			double currentTime = 0;
			double idleThreshold = 300;  // Idle threshold in seconds (e.g., 5 minutes)

			// Batch process cloudlets
			for (int i = 0; i < functionsData.size(); i += batchSize) {
				// Create a batch of cloudlets
				List<FunctionData> batch = functionsData.subList(i, Math.min(i + batchSize, functionsData.size()));
				List<Cloudlet> cloudletBatch = createCloudlet(brokerId, batch, currentTime, idleThreshold);

				// Submit the batch to the broker
				broker.submitCloudletList(cloudletBatch);

				// Advance the simulation time for the next batch (e.g., 1 hour)
				currentTime += 3600;  // Increase time as per your simulation's time unit
			}

			// Start simulation
			Log.printLine("\nStarting Simulation...");
			CloudSim.startSimulation();

			// Retrieve results when simulation is over
			List<Cloudlet> newList = broker.getCloudletReceivedList();
			CloudSim.stopSimulation();

			// Display Results
			calculateResults(newList);
			Log.printLine("\nSimulation finished!");
		} catch (Exception e) {
			e.printStackTrace();
			Log.printLine("Simulation terminated due to unexpected error");
		}
	}

	// Load processed CSV data (skeleton function, replace with your own implementation)
	public static List<FunctionData> loadProcessedData(String invocationsFile, String durationsFile, String memoryFile) {
		Map<String, Integer> memoryData = loadMemoryData(memoryFile);
		Map<String, double[]> durationData = loadDurationData(durationsFile);  // Adjusted to store both avg and max execution times
		return loadInvocationData(invocationsFile, memoryData, durationData);
	}

	// Implement CSV parsing here to populate functionsData with invocation, duration, and memory data
	private static Map<String, Integer> loadMemoryData(String memoryFile) {
		Map<String, Integer> memoryMap = new HashMap<>();
		try (CSVReader reader = new CSVReader(new FileReader(memoryFile))) {
			String[] line;
			reader.readNext(); // Skip header
			while ((line = reader.readNext()) != null) {
				// Combine HashOwner and HashApp as a unique identifier
				String key = line[0] + line[1];
				// Parse Average_Memory_Usage_MB from column index 2
				int averageMemory = Integer.parseInt(line[2]);
				memoryMap.put(key, averageMemory);

				// Debug: Print each memory entry
				System.out.println("Memory Entry - Key: " + key + ", Memory: " + averageMemory + " MB");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("Total memory entries loaded: " + memoryMap.size());
		return memoryMap;
	}


	private static Map<String, double[]> loadDurationData(String durationFile) {
		Map<String, double[]> durationMap = new HashMap<>();
		try (CSVReader reader = new CSVReader(new FileReader(durationFile))) {
			String[] line;
			reader.readNext(); // Skip header
			while ((line = reader.readNext()) != null) {
				// Combine HashOwner, HashApp, and HashFunction as a unique identifier
				String key = line[0] + line[1] + line[2];
				// Parse Average_Execution_Time from column index 3 and Max_Execution_Time from column index 5
				double avgExecutionTime = Double.parseDouble(line[3]);
				double maxExecutionTime = Double.parseDouble(line[5]);
				durationMap.put(key, new double[]{avgExecutionTime, maxExecutionTime});

				// Debug: Print each duration entry
				System.out.println("Duration Entry - Key: " + key + ", Avg Execution Time: " + avgExecutionTime + "s, Max Execution Time: " + maxExecutionTime + "s");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("Total duration entries loaded: " + durationMap.size());
		return durationMap;
	}



	private static List<FunctionData> loadInvocationData(String invocationFile, Map<String, Integer> memoryData, Map<String, double[]> durationData) {
		List<FunctionData> functionsData = new ArrayList<>();
		try (CSVReader reader = new CSVReader(new FileReader(invocationFile))) {
			String[] line;
			reader.readNext(); // Skip header
			while ((line = reader.readNext()) != null) {
				// Create unique identifiers for the function and app keys
				String functionKey = line[0] + line[1] + line[2]; // Consistent with duration data key
				String appKey = line[0] + line[1];                // Consistent with memory data key

				// Debug: Print the keys being checked for matching
				System.out.println("Checking functionKey: " + functionKey);
				System.out.println("Checking appKey: " + appKey);

				// Retrieve average and maximum execution times with fallback defaults
				double avgExecutionTime = durationData.containsKey(functionKey) ? durationData.get(functionKey)[0] : 0.0;
				double maxExecutionTime = durationData.containsKey(functionKey) ? durationData.get(functionKey)[1] : avgExecutionTime * 1.5;
				int memoryUsage = memoryData.getOrDefault(appKey, 128);

				// Process peak hours (last column, index 28)
				String peakHoursStr = line[28].replace("[", "").replace("]", "").replace("'", "");
				List<String> peakHours = Arrays.asList(peakHoursStr.split(", "));

				// Debug: Log if avgExecutionTime, maxExecutionTime, and memoryUsage were retrieved from maps
				if (durationData.containsKey(functionKey)) {
					System.out.println("Found avgExecutionTime and maxExecutionTime for " + functionKey +
							": " + avgExecutionTime + "s, " + maxExecutionTime + "s");
				} else {
					System.out.println("avgExecutionTime and maxExecutionTime for " + functionKey + " not found, defaults used.");
				}

				if (memoryData.containsKey(appKey)) {
					System.out.println("Found memoryUsage for " + appKey + ": " + memoryUsage);
				} else {
					System.out.println("memoryUsage for " + appKey + " not found, default used.");
				}

				// Create FunctionData object
				FunctionData functionData = new FunctionData(functionKey, memoryUsage, avgExecutionTime, maxExecutionTime, peakHours);
				functionsData.add(functionData);

				// Debug: Print each loaded FunctionData
				System.out.println("Loaded FunctionData: " + functionData.getFunctionId() +
						", Memory: " + functionData.getMemoryUsage() +
						"MB, AvgExecTime: " + functionData.getAverageExecutionTime() +
						"s, MaxExecTime: " + functionData.getMaxExecutionTime() +
						"s, Peak Hours: " + functionData.getPeakHours());
			}

			// Print summary of total functions loaded
			System.out.println("Total functions loaded: " + functionsData.size());

		} catch (Exception e) {
			e.printStackTrace();
		}
		return functionsData;
	}





	// Create Cloudlets with cold start delay
	private static List<Cloudlet> createCloudlet(int userId, List<FunctionData> functionsData, double currentTime, double idleThreshold) {
		LinkedList<Cloudlet> list = new LinkedList<>();
		int pesNumber = 1;
		UtilizationModel utilizationModel = new UtilizationModelFull();

		for (FunctionData function : functionsData) {
			long length = (long) (function.getAverageExecutionTime() * 1000);  // Convert to appropriate units
			long fileSize = 300;  // Adjust as needed
			long outputSize = 300;  // Adjust as needed

			// Determine if the function should be "cold"
			if (function.getLastInvocationTime() < 0 || (currentTime - function.getLastInvocationTime() > idleThreshold)) {
				function.setCold(true);
			} else {
				function.setCold(false);
			}

			Cloudlet cloudlet = new Cloudlet(function.getFunctionId().hashCode(), length, pesNumber, fileSize, outputSize, utilizationModel, utilizationModel, utilizationModel);
			cloudlet.setUserId(userId);

			// Calculate dynamic cold start delay for cold functions only
			if (function.isCold()) {
				double coldStartDelay = 0.2;  // Base delay for cold starts

				// Increase delay based on memory usage
				if (function.getMemoryUsage() > 200) {  // Example threshold for high-memory functions
					coldStartDelay += 0.3;
				}

				// Increase delay based on execution time thresholds
				if (function.getAverageExecutionTime() > 1000 || function.getMaxExecutionTime() > 2000) {  // Example threshold
					coldStartDelay += 0.5;
				}

				// Increase delay if invocation occurs outside peak hours
				String currentHour = getCurrentHourAsString(currentTime);
				boolean isPeakHour = function.isDuringPeakHour(currentHour);
				if (!isPeakHour) {
					coldStartDelay += 0.3;  // Additional delay for off-peak hours
				}

				// Apply the calculated cold start delay to the execution start time
				cloudlet.setExecStartTime(cloudlet.getExecStartTime() + coldStartDelay);
			}

			// Update the function's last invocation time to the current simulation time
			function.setLastInvocationTime(currentTime);

			list.add(cloudlet);
		}

		Log.printLine("\nCreated Cloudlets with Dynamic Cold Start Handling.");
		return list;
	}

	// Helper function to convert currentTime to the hour of the day in "Hour_X" format
	private static String getCurrentHourAsString(double currentTime) {
		int hour = (int) ((currentTime / 3600) % 24);  // Convert seconds to hours in 24-hour format
		return "Hour_" + hour;
	}



	// Create VMs
	private static List<Vm> createVM(int userId, int vmCount, int mips, int ram, long bw, long size) {
		LinkedList<Vm> vmList = new LinkedList<>();
		String vmm = "Xen";
		int pesNumber = 1;

		for (int i = 0; i < vmCount; i++) {
			Vm vm = new Vm(i, userId, mips, pesNumber, ram, bw, size, vmm, new CloudletSchedulerSpaceShared());
			vmList.add(vm);
		}

		Log.printLine("Created " + vmCount + " VMs with MIPS: " + mips + ", RAM: " + ram + "MB, Bandwidth: " + bw + "bps, Storage: " + size + "MB.");
		return vmList;
	}

	// Create Datacenter
	private static Datacenter createDatacenter(String name) {
		List<Host> hostList = new ArrayList<>();
		int ram = 16384;
		long storage = 1000000;
		int bw = 10000;
		int mips = 10000;

		for (int hostId = 0; hostId < 8; hostId++) {
			List<Pe> peList = new ArrayList<>();
			peList.add(new Pe(0, new PeProvisionerSimple(mips)));
			hostList.add(new Host(hostId, new RamProvisionerSimple(ram), new BwProvisionerSimple(bw), storage, peList, new VmSchedulerTimeShared(peList)));
		}

		String arch = "x86";
		String os = "Linux";
		String vmm = "Xen";
		double timeZone = 10.0;
		double cost = 3.0;
		double costPerMem = 0.05;
		double costPerStorage = 0.1;
		double costPerBw = 0.1;
		LinkedList<Storage> storageList = new LinkedList<>();

		DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
				arch, os, vmm, hostList, timeZone, cost, costPerMem, costPerStorage, costPerBw);

		Datacenter datacenter = null;
		try {
			datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return datacenter;
	}

	// Create Broker
	private static DatacenterBroker createBroker() {
		DatacenterBroker broker = null;
		try {
			broker = new DatacenterBroker("Broker");
		} catch (Exception e) {
			e.printStackTrace();
		}
		return broker;
	}

	// Calculate and display results
	private static void calculateResults(List<Cloudlet> list) {
		DecimalFormat dft = new DecimalFormat("###.##");
		double totalColdStartLatency = 0;
		double totalExecutionTime = 0;
		double totalActualExecutionTime = 0;
		double staticColdStartDelay = 0.2;  // Assuming static delay in seconds (200 ms)

		Log.printLine("========== RESULTS ==========");

		for (Cloudlet cloudlet : list) {
			double execTime = cloudlet.getActualCPUTime();
			double startDelay = staticColdStartDelay;
			double totalExecTime = execTime + startDelay;  // Total including cold start delay

			totalExecutionTime += totalExecTime;
			totalColdStartLatency += startDelay;
			totalActualExecutionTime += execTime;

			Log.printLine("Cloudlet " + cloudlet.getCloudletId() +
					" -> Execution Time: " + dft.format(execTime) + "s, " +
					"Cold Start Latency: " + dft.format(startDelay) + "s, " +
					"Total Time: " + dft.format(totalExecTime) + "s");
		}

		// Print averages
		Log.printLine("\nAverage Cold Start Latency: " + dft.format(totalColdStartLatency / list.size()) + "s");
		Log.printLine("Average Actual Execution Time: " + dft.format(totalActualExecutionTime / list.size()) + "s");
		Log.printLine("Average Total Time (Execution + Cold Start): " + dft.format(totalExecutionTime / list.size()) + "s");
	}
}

// Helper class to store function data
class FunctionData {
	private String functionId;
	private double lastInvocationTime = -1;  // Initialize to -1 to indicate no prior invocations
	private boolean isCold = true;
	private int memoryUsage;
	private double averageExecutionTime;
	private double maxExecutionTime;
	private List<String> peakHours;

	// Constructor to initialize all fields
	public FunctionData(String functionId, int memoryUsage, double averageExecutionTime,
						double maxExecutionTime, List<String> peakHours) {
		this.functionId = functionId;
		this.memoryUsage = memoryUsage;
		this.averageExecutionTime = averageExecutionTime;
		this.maxExecutionTime = maxExecutionTime;
		this.peakHours = peakHours;
	}

	// Getters and setters for each field
	public String getFunctionId() { return functionId; }

	public double getLastInvocationTime() { return lastInvocationTime; }
	public void setLastInvocationTime(double lastInvocationTime) { this.lastInvocationTime = lastInvocationTime; }

	public boolean isCold() { return isCold; }
	public void setCold(boolean isCold) { this.isCold = isCold; }

	public int getMemoryUsage() { return memoryUsage; }

	public double getAverageExecutionTime() { return averageExecutionTime; }

	public double getMaxExecutionTime() { return maxExecutionTime; }
	public void setMaxExecutionTime(double maxExecutionTime) { this.maxExecutionTime = maxExecutionTime; }

	public List<String> getPeakHours() { return peakHours; }
	public void setPeakHours(List<String> peakHours) { this.peakHours = peakHours; }

	// Helper method to determine if current invocation is during peak hours
	public boolean isDuringPeakHour(String currentHour) {
		return peakHours.contains(currentHour);
	}
}



