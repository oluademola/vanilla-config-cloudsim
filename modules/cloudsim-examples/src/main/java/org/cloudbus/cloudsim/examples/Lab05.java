/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation
 *               of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.examples;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerSpaceShared;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.lists.VmList;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

/** Lab 05 solution. */
public class Lab05 {

	/** Find the cloudlet with max memory requirement to dimension VMs.*/
	private static int cloudlet_max_mem = 0;

	/** The cloudlet list. */
	private static List<Cloudlet> cloudletList;

	/** The vmlist. */
	private static List<Vm> vmlist;

	/** Creates main() to run this example */
	public static void main(String[] args) {

		try {
			// Lab Parameters:
			String datapath = "dataset/task280.xlsx";
			int dc_servers = 4;

			// First step: Initialize the CloudSim package. It should be called before creating any entities.
			int num_user = 1;   // number of grid users
			Calendar calendar = Calendar.getInstance();
			boolean trace_flag = false;  // mean trace events

			// Initialize the CloudSim library
			CloudSim.init(num_user, calendar, trace_flag);

			// Second step: Create a Datacenter
			@SuppressWarnings("unused")
			Datacenter datacenter = createDatacenter("Datacenter", dc_servers);

			//Third step: Create a Broker
			DatacenterBroker broker = createBroker();
			int brokerId = broker.getId();
			
			//Fourth step: Create VMs and Cloudlets from the data file and send them to broker
			List<Map<Integer, List<String>>> workbook_data = read_excel_workbook(datapath);
			Map<Integer, List<String>> cloudlet_data = workbook_data.get(0);
			Map<Integer, List<String>> node_data = workbook_data.get(1);
			
			cloudletList = createCloudlet(brokerId,cloudlet_data); 
			vmlist = createVM(brokerId,node_data); 

			broker.submitVmList(vmlist);
			broker.submitCloudletList(cloudletList);
			
			// Fifth step: Start the simulation
			Log.printLine("\nStarting Simulation ...");
			CloudSim.startSimulation();

			// Final step: Print results when simulation is over
			List<Cloudlet> newList = broker.getCloudletReceivedList();

			CloudSim.stopSimulation();
			calculateResults(newList);
			
			Log.printLine("\nSimulation finished!");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			Log.printLine("The simulation has been terminated due to an unexpected error");
		}
	}

// --------------------- CODE SUBMISSION (from here) --------------------- //
	/** Read Excel data from 'Cloud-Fog Computing' Dataset:
	 *  https://www.kaggle.com/datasets/sachin26240/vehicularfogcomputing
	 * */

	public static List<Map<Integer, List<String>>> read_excel_workbook(String filePath) {
		List<Map<Integer, List<String>>> workbookData = new ArrayList<>();

		try (FileInputStream fileInputStream = new FileInputStream(filePath);
			 XSSFWorkbook workbook = new XSSFWorkbook(fileInputStream)) {

			for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
				XSSFSheet sheet = workbook.getSheetAt(i);
				Map<Integer, List<String>> sheetData = new HashMap<>();
				sheet.forEach(row -> sheetData.put(row.getRowNum(), extractRowData(row)));
				workbookData.add(sheetData);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		return workbookData;
	}

	private static List<String> extractRowData(Row row) {
		List<String> rowData = new ArrayList<>();
		row.forEach(cell -> rowData.add(getCellValueAsString(cell)));
		return rowData;
	}

	private static String getCellValueAsString(Cell cell) {
		switch (cell.getCellType()) {
			case STRING:
				return cell.getStringCellValue();
			case NUMERIC:
				return DateUtil.isCellDateFormatted(cell) ? cell.getDateCellValue().toString() : String.valueOf(cell.getNumericCellValue());
			case BOOLEAN:
				return String.valueOf(cell.getBooleanCellValue());
			case FORMULA:
				return cell.getCellFormula();
			case BLANK:
				return "";
			default:
				return "";
		}
	}

	/** Create Cloudlets from dataset (sheet 0 'TaskDetails')*/
	private static List<Cloudlet> createCloudlet(int userId, Map<Integer, List<String>> data){

		// Creates a container to store Cloudlets
		LinkedList<Cloudlet> list = new LinkedList<Cloudlet>();
		
		//Cloudlet parameters
		int pesNumber = 1;
		UtilizationModel utilizationModel = new UtilizationModelFull();
		int index = 0;
		int cloudlet_max_idx = 0;
		for (Integer row: data.keySet()) {
			if (row == 0) continue;	// Ignore first row with titles
			
			long length =  (long) (Double.valueOf(data.get(row).get(1)) * 1000); 		// Million instructions, i.e., convert 10^9 to 10^6
			long fileSize = (long) (Double.valueOf(data.get(row).get(3)) * 1000000);	// Convert MB to Byte as required by Cloudlet constructor
			long outputSize = (long) (Double.valueOf(data.get(row).get(4)) * 1000000);	// Convert MB to Byte as required by Cloudlet constructor
			int mem_required =  (int) (Double.parseDouble(data.get(row).get(2)));	// Cloudlet memory requirement in MB
			if (mem_required > cloudlet_max_mem) {
				cloudlet_max_mem = mem_required;
				cloudlet_max_idx = row;
			}
			
			//Log.printLine("Input row " + row + " Cloudlet: " + data.get(row).get(0) + " length: " + (length));
			Cloudlet[] cloudlet = new Cloudlet[data.size()];
			cloudlet[index] = new Cloudlet(row, length, pesNumber, fileSize, outputSize, utilizationModel, utilizationModel, utilizationModel);
			cloudlet[index].setUserId(userId);
			list.add(cloudlet[index]);
			index++;
		}
		
		Log.printLine("\nCreated " + index + " Cloudlets from data.");
		Log.printLine("Cloudlet " + (cloudlet_max_idx) + " has largest memory requirement [MB]: " + (cloudlet_max_mem));
		
		return list;
	}

	/** Create VMs from dataset (sheet 1 'NodeDetails')*/
	private static List<Vm> createVM(int userId, Map<Integer, List<String>> data) {
		
		//Creates a container to store VMs. This list is passed to the broker later
		LinkedList<Vm> list = new LinkedList<Vm>();
	
		//VM Parameters
		long size = 3000; //image size (MB)
		    	
		int ram = cloudlet_max_mem == 1 ? 1 : Integer.highestOneBit(cloudlet_max_mem - 1) * 2; 	
		Log.printLine("\nCreating VMs with " + (ram) + "MB of Memory");
		
		long bw = 1000;
		int pesNumber = 1; //number of cpus
		String vmm = "Xen"; //VMM name
		
		//create VMs
		Vm[] vm = new Vm[data.size()];
		int index = 0;
		for (Integer row: data.keySet()) {
			if (row == 0) continue;	// Ignore first line with column titles
			int mips = (int) (Double.parseDouble(data.get(row).get(1)));
	    	Log.printLine("Creating VM" + row + " ("+ data.get(row).get(0) + ") " + "with CPU rate " + (mips) + " MIPS");
			vm[index] = new Vm(
					row, 
					userId, 
					mips, 
					pesNumber, 
					ram, 
					bw, 
					size, 
					vmm, 
					new CloudletSchedulerTimeShared()
					//new CloudletSchedulerSpaceShared()
					); 
			list.add(vm[index]);
			index++;
		}
//		Log.printLine("Created " + index + " VMs from data.");	
		return list;
	}
		
	private static Datacenter createDatacenter(String name, int servers){
		
		Log.printLine("\nCreating " + name + " with " + servers + " servers");	
		//    We need to create a list to store one or more Machines
		List<Host> hostList = new ArrayList<Host>();

		// Create Hosts with its id and list of PEs and add them to the list of machines
		//    A Machine contains one or more PEs or CPUs/Cores. Therefore, should
		//    create a list to store these PEs before creating
		//    a Machine.
		int ram = 4096; //host memory (MB)
		long storage = 1000000; //host storage
		int bw = 10000;
		int mips = 5000;  // Core speed
		int cores = 2;	  // Cores per server
		
		for (int hostId=0; hostId < servers; hostId++) {
			List<Pe> peList = new ArrayList<Pe>();
			for (int core=0; core < cores; core++){
				peList.add(new Pe(core, new PeProvisionerSimple(mips)));
			}			
			hostList.add(
	    			new Host(
	    				hostId,
	    				new RamProvisionerSimple(ram),
	    				new BwProvisionerSimple(bw),
	    				storage,
	    				peList,
	    				new VmSchedulerTimeShared(peList)
	    				//new VmSchedulerSpaceShared(peList)
	    			)
	    		); 
		}

		//    Create a DatacenterCharacteristics object that stores the
		//    properties of a data center: architecture, OS, list of
		//    Machines, allocation policy: time- or space-shared, time zone
		//    and its price (G$/Pe time unit).
		String arch = "x86";      // system architecture
		String os = "Linux";          // operating system
		String vmm = "Xen";
		double time_zone = 10.0;         // time zone this resource located
		double cost = 3.0;              // the cost of using processing in this resource
		double costPerMem = 0.05;		// the cost of using memory in this resource
		double costPerStorage = 0.1;	// the cost of using storage in this resource
		double costPerBw = 0.1;			// the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<Storage>();	//we are not adding SAN devices by now

		DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);


		// Finally, we need to create a PowerDatacenter object.
		Datacenter datacenter = null;
		try {
			datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return datacenter;
	}

	private static DatacenterBroker createBroker(){

		DatacenterBroker broker = null;
		try {
			broker = new DatacenterBroker("Broker");
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		return broker;
	}

/** Create Summary of Execution */
private static void calculateResults(List<Cloudlet> list) {
	
	int size = list.size();
	Cloudlet cloudlet;
	DecimalFormat dft = new DecimalFormat("###.##");
	
	String indent = "\t";
	Log.printLine();
	Log.printLine("========== RESULTS ==========");
	cloudlet = list.get(0);
	Log.print("\nCloudlet finished first: " + cloudlet.getCloudletId()+ indent + "time: " + dft.format(cloudlet.getActualCPUTime()));
	cloudlet = 	list.get(size-1);
	Log.print("\nCloudlet finished last: " + cloudlet.getCloudletId() + indent + "time: " + dft.format(cloudlet.getActualCPUTime()));
	Log.print("\nTotal execution time: " + dft.format(cloudlet.getFinishTime()));
	
	// Create lists to summarize results
	List<Double> CPUTimes = new ArrayList<Double>();
	List<Integer> VMusage = new ArrayList<Integer>();
	List<Double> lengths = new ArrayList<Double>();			
	List<Double> CPURate = new ArrayList<Double>();
	for (int i = 0; i < size; i++) {
		cloudlet = list.get(i);
		CPUTimes.add(cloudlet.getActualCPUTime());
		VMusage.add(cloudlet.getVmId());
		lengths.add((double) cloudlet.getCloudletLength());
		//Log.print("\nCloudlet " + cloudlet.getCloudletId() + " start time: " + dft.format(cloudlet.getExecStartTime())+ " finish time: " + dft.format(cloudlet.getFinishTime()));
	}		
	
	// Cloudlet average length
	OptionalDouble length_average = lengths
            .stream()
            .mapToDouble(a -> a)
            .average();
	Log.printLine("\nCloudlet average length: " + (length_average.isPresent() ? dft.format(length_average.getAsDouble()) : 0));
	
	// Calculate the Cloudlet average execution time
	OptionalDouble CPU_average = CPUTimes
            .stream()
            .mapToDouble(a -> a)
            .average();
	Log.printLine("Cloudlet average execution time: " + (CPU_average.isPresent() ? dft.format(CPU_average.getAsDouble()) : 0));
	
	// Count the number of Cloudlets run by each VM
	Map<Object, Long> counts = VMusage.stream().collect(Collectors.groupingBy(e -> e, Collectors.counting()));
	for (Object count: counts.keySet()){	
		// get the VM object with the 'count' id and query its MIPS.
		Vm vm_obj = vmlist.stream().filter(eachvm -> count.equals(eachvm.getId())).findAny().orElse(null); 
		Double vm_mips = vm_obj.getMips();		
		Log.printLine("VM " + vm_obj.getId() + " (" + vm_mips.intValue() + " MIPS)" +" ran " + counts.get(count) + " Cloudlets");		 		
		CPURate.add(vm_mips);
	}

	// Calculate the Average VM CPUrate
//	OptionalDouble average_rate = CPURate
//            .stream()
//            .mapToDouble(a -> a)
//            .average();
//	Log.printLine("Average VM CPU rate: " + (average_rate.isPresent() ? dft.format(average_rate.getAsDouble()) : 0));
	
	Double total_rate = CPURate
            .stream()
            .mapToDouble(a -> a)
            .sum();
	Log.printLine("Total VM CPU rate: " + total_rate.intValue());

}

}

