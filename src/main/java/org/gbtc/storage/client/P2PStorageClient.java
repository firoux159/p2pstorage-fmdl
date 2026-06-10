package org.gbtc.storage.client;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Scanner;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import java.util.*;

import org.gbtc.storage.P2PKeyHashGenerator;
import org.gbtc.storage.P2PNodeServer;
import org.gbtc.storage.P2PNodeServerBuilder;
import org.gbtc.storage.P2PNodeSettings;
import org.gbtc.storage.node.P2PNode;
import org.gbtc.storage.protocol.message.NodeStatusMessage;
import org.gbtc.storage.protocol.message.NodeStatusResultMessage;
import org.gbtc.storage.repository.FileDTO;
import org.gbtc.storage.repository.FileRepository;
import org.gbtc.storage.serialization.P2PGsonFactory;
import org.gbtc.storage.services.ClientRetrieveService;
import org.gbtc.storage.services.ClientStoreService;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.serialization.gson.GsonFactory;
import io.ep2p.kademlia.serialization.gson.GsonMessageSerializer;
import io.ep2p.kademlia.services.DHTLookupServiceAPI;
import io.ep2p.kademlia.services.DHTLookupServiceFactory;
import io.ep2p.kademlia.services.DHTStoreServiceAPI;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class P2PStorageClient {
	
	private static P2PNodeSettings nodeSettings;
	
    private static int clientNumThread;
    private static int clientConnectTimeout;
    private static int clientReadTimeout;
	private static int clientWriteTimeout;
	
	private static int storeDelay;
	private static int lookupDelay;
	
	private static int statusPeriod;
	
	private static Scanner menuScanner;
	
	private static int type;
	
	private static String host;
	private static int port;
	private static int clientCount;
	private static int dataCount;
	private static int dataSize;
	
	private static int iteration = 0;
	private static int sampleCount = 0;
	
	private static ArrayList<NodeServer> servers = new ArrayList<NodeServer>();
	private static ArrayList<P2PNodeServer> clients = new ArrayList<P2PNodeServer>();
	//private static P2PNodeServer node;
	
	private static ExecutorService executorService;
	
	private static P2PNodeStatusMonitor monitor;
//	private static BufferedWriter statusWriter;
//	private static Timer statusTimer;
	
	private static ArrayList<Future<Map<String, BigInteger>>> futureList = new ArrayList<Future<Map<String, BigInteger>>>();
	
	public static void main(String args[]) {
		if (args.length < 8) {
			System.err.println("Missing required arguments <type> <host> <port> <number of client> <number of data> <data size> <iteration> <number of samples> - System Aborted.");
		}
		else {
			
			type = Integer.parseInt(args[0].trim());
			host = args[1].trim();
			port = Integer.parseInt(args[2].trim());
			
			clientCount = Integer.parseInt(args[3]);
			dataCount = Integer.parseInt(args[4]);
			dataSize = Integer.parseInt(args[5]);
			
			iteration = Integer.parseInt(args[6]);
			sampleCount = Integer.parseInt(args[7]);
			
			try {
				readConfiguration();
				loadNodes();
				control();
			}
			catch (Exception e) {
				System.err.println("FATAL ERROR - System Aborted. [Reason: "+e.getMessage()+"]");
			}
		}
	}

	
	public static void control() {
		
		char response = 'X';
		do {
			try {
				response = Character.toUpperCase(displayMenu());

				switch (response) {
					case '1':
						handleLoad();
						handleRetrieveMultiIteration();
				   		break;
					case '2':
						handleRetrieveMultiIteration();
				   		break;
					case '3':
						handleWorkloadStatus();
				   		break;
					case '9':
						handleStop();
				   		System.out.println("\nSystem exited!");
				   		menuScanner.close();
				   		System.exit(0);
				   		//break;
				   	default:
				   		System.out.println("Invalid selection, please choose from available options");
				}				
			}
			catch (Exception e)
			{
				e.printStackTrace();
				//System.out.println("Invalid selection, please choose from available options");
			}
		} 
		while (response != '9');
	}
	
	public static char displayMenu() throws Exception {
		System.out.println("\n** P2P Storage **");
		
		System.out.printf("%4s\n", "1. Load Data");
		System.out.printf("%4s\n", "2. Retrieve Data ("+ getTypeDescription(type) +")");
		System.out.printf("%4s\n", "3. Gather Workload Status");
		System.out.printf("%4s\n", "9. Exit");
		System.out.print("Enter your choice: ");
		
		menuScanner = new Scanner(System.in);
		
		// validate that input should only be 1
		// option validation will be done in separate method
		String input = menuScanner.nextLine();
		
		if (input.length() > 1)
			throw new Exception("Invalid selection, please choose from available options");
		
		return input.charAt(0);
	}
	
	public static void handleLoad() {
		
		if (clientCount > 0)
			executorService = Executors.newFixedThreadPool(clientCount);

		for (int i=1; i<=clientCount; i++) {

			try {
				String name = host+"_LoadClient"+i;
				
				P2PStorageClientCallable client = new P2PStorageClientCallable(
						nodeSettings, servers, name, host, port++, 
						lookupDelay, storeDelay, clientNumThread, clientConnectTimeout, clientReadTimeout, clientWriteTimeout,
						dataCount, dataSize);
				
				Future<Map<String, BigInteger>> future = executorService.submit(client);
				futureList.add(future);
			}
			catch (Exception e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		
		return;
	}
	
	public static void handleRetrieveMultiIteration() {
		// need to wait data loading to complete
		try {
			while (!isLoadDone()) {
				Thread.sleep(60000);
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
		
		for (int i=1; i<=iteration; i++) {
			try {
				handleRetrieveInner(i);
				Thread.sleep(30000);
			}
			catch (Exception e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}
	
	public static void handleRetrieveInner(int iteration) {
		
		// start a client node
		String name = "RetrieveClient";

		// sampling data using random
		Random random = new Random(name.hashCode() & 0x7fffffff & iteration);
		
        P2PKeyHashGenerator keyHashGenerator = new P2PKeyHashGenerator(nodeSettings.getIdentifierSize()); 
        FileRepository fileRepository = new FileRepository(nodeSettings.getDataPath(), 0, 0);
		
        BigInteger nodeId = keyHashGenerator.generateHash(name.hashCode() & 0x7fffffff);
        
        GsonFactory gsonFactory = new P2PGsonFactory.DefaultGsonFactory<BigInteger, NettyConnectionInfo, String, FileDTO>(
        		BigInteger.class, NettyConnectionInfo.class, String.class, FileDTO.class);

        P2PMessageSender messageSender = new P2PMessageSender(
				new GsonMessageSerializer<BigInteger, NettyConnectionInfo, String, FileDTO> (gsonFactory.gsonBuilder()),
				clientNumThread, clientConnectTimeout, clientReadTimeout, clientWriteTimeout);

        P2PNodeServer nodeServer = new P2PNodeServerBuilder(
                nodeId,
                new NettyConnectionInfo(host, port),
                fileRepository,
                keyHashGenerator,
                String.class, FileDTO.class, nodeSettings)
        		.messageSender(messageSender)
				.dhtStoreServiceFactory(new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new ClientStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				})
				.dhtLookupServiceFactory(new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new ClientRetrieveService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				})
				.build();
        
        nodeServer.setType(P2PNodeServer.TYPE_CLIENT);
        nodeServer.setName(name);
        nodeServer.setLookupDelay(lookupDelay);
		nodeServer.setStoreDelay(storeDelay);
        
//        if (mode == 1)
//    		log.info(String.format("Starting Client Node (Random) [Name=%s, ID=%s]...", name, node.getId()));
//    	else if (mode == 2)
//    		log.info(String.format("Starting Client Node (PT) [Name=%s, ID=%s]...", name, node.getId()));
        
        boolean success = nodeServer.startClientNode();
        
//		if (success) {
//			if (mode == 1)
//				log.info(String.format("Client Node (Random) [Name=%s, ID=%s] is running at %s:%s", name,
//						node.getId(), node.getConnectionInfo().getHost(), node.getConnectionInfo().getPort()));
//			else if (mode == 2)
//				log.info(String.format("Client Node (PT) [Name=%s, ID=%s] is running at %s:%s", name, node.getId(),
//						node.getConnectionInfo().getHost(), node.getConnectionInfo().getPort()));
//		}
		
		try {
			
			// collate data from each client threads
			HashMap<String, BigInteger> storedDataMap = new HashMap<>();
			
			for (Future<Map<String, BigInteger>> future: futureList) {
				Map<String, BigInteger> map = future.get();
				storedDataMap.putAll(map);
			}
			//System.out.println("Consolidated map: "+storedDataMap);
			
			// take sample of data to retrieve
			HashMap<String, BigInteger> sampleDataMap = new HashMap<>();

			while (sampleDataMap.size() < sampleCount) {
				Set<String> keySet = storedDataMap.keySet();
		        List<String> keyList = new ArrayList<>(keySet);

		        int size = keyList.size();
		        int randIdx = random.nextInt(size);
		        String randomKey = keyList.get(randIdx);
		        sampleDataMap.putIfAbsent(randomKey, storedDataMap.get(randomKey));
			}

			// prepare the statistic file
			String fileName = "";
			String header = "";
			
			if (type == P2PNodeServer.TYPE_DEFAULT) {
				fileName = "logs/r_response_time.csv";
				header = String.format("Iteration, RNode ID, Key, Request Timestamp, Response Timestamp, Duration (milliseconds), Internal Duration, Result");
			}
			else if (type == P2PNodeServer.TYPE_REPUTATION) {
				fileName = "logs/rp_response_time.csv";
				header = String.format("Iteration, RPNode ID, Key, Request Timestamp, Response Timestamp, Duration (milliseconds), Internal Duration, Result");
			}
			else if (type == P2PNodeServer.TYPE_PT) {
				fileName = "logs/pt_response_time.csv";
				header = String.format("Iteration, PTNode ID, Key, Request Timestamp, Response Timestamp, Duration (milliseconds), Internal Duration, Result");
			}
			
			BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true));
			writer.write(header);
			writer.newLine();
			writer.flush();

			// process the sample data
			for (Map.Entry<String, BigInteger> entry : sampleDataMap.entrySet()) {
				
		        String key = entry.getKey();
		        BigInteger storerNodeId = entry.getValue();
		        
		        // randomly selects a server
				NodeServer server = servers.get(random.nextInt(servers.size()));
				
				log.debug("Selected server: "+server.getId()+", "+server.getConnectionInfo().getHost()+", "+server.getConnectionInfo().getPort());
				
				//BigInteger storerNodeId = dataMap.get(key);
				
				if (storerNodeId == null)
					continue;
				
				log.debug(String.format("Looking up data %s to node %s, storer=%s",key, server.getId(), storerNodeId)); 
				
				long start = System.currentTimeMillis();
				
				FileDTO value = null;
				
				if (type == P2PNodeServer.TYPE_DEFAULT)
					value = nodeServer.clientLookup(server, key, clientReadTimeout);
				else if (type == P2PNodeServer.TYPE_REPUTATION || type == P2PNodeServer.TYPE_PT)
					value = nodeServer.clientLookup(server, storerNodeId+"@"+key, clientReadTimeout);
				
				long end = System.currentTimeMillis();
				
				if (value != null) {
					log.info(String.format("Retrieve object: %s, node: %s, result: %s", key, server.getId(), "FOUND"));
					String line = String.format("%s, %s, %s, %s, %s, %s, %s, %s", iteration, server.getId(), key, start, end, (end-start), value.getReadLatency(), "FOUND");
					writer.write(line);
					writer.newLine();
				}
				else {
					// failed
					log.info(String.format("Retrieve object: (%s), node: %s, result: %s", key, server.getId(), "FAILED"));
					String line = String.format("%s, %s, %s, %s, %s, %s, %s, %s", iteration, server.getId(), key, start, end, (end-start), -1, "FAILED");
					writer.write(line);
					writer.newLine();
				}
				
				writer.flush();

				if (lookupDelay > 0)
					Thread.sleep(lookupDelay);
			}
			
			writer.close();
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
		
		nodeServer.stopClientNode();
	}
	
	public static void handleWorkloadStatus() {
		monitor = new P2PNodeStatusMonitor(type, nodeSettings, servers, host, clientNumThread, clientConnectTimeout, clientReadTimeout, clientWriteTimeout, statusPeriod);
		monitor.start();
	}
	
	public static void handleStop() {
		if (executorService != null)
			executorService.shutdownNow();
		
		if (monitor != null)
			monitor.stop();
	}
	
	
	public static void readConfiguration() {
    	// read configuration from file
    	File configFile = new File("config.properties");
    	 
    	try {
    	    FileReader reader = new FileReader(configFile);
    	    Properties props = new Properties();
    	    props.load(reader);
    	 
    	    storeDelay = Integer.parseInt(props.getProperty("CLIENT_STORE_DELAY"));
    	    lookupDelay = Integer.parseInt(props.getProperty("CLIENT_LOOKUP_DELAY"));
    	    
    	    clientNumThread = Integer.parseInt(props.getProperty("CLIENT_NUM_THREAD"));
    	    clientConnectTimeout = Integer.parseInt(props.getProperty("CLIENT_CONNECT_TIMEOUT"));
    	    clientReadTimeout = Integer.parseInt(props.getProperty("CLIENT_READ_TIMEOUT"));
    	    clientWriteTimeout = Integer.parseInt(props.getProperty("CLIENT_WRITE_TIMEOUT"));
    	    
    	    statusPeriod = Integer.parseInt(props.getProperty("STATUS_PERIOD"));

    	    reader.close();
    	    
			// read and load specific node parameters
    	    nodeSettings = new P2PNodeSettings();
    	} 
    	catch (Exception e) {
    	    // TO-DO
    		e.printStackTrace();
    	}
	}
	
	public static void loadNodes() {
    	// read configuration from file
    	File nodesFile = new File("nodes.txt");
    	
    	P2PKeyHashGenerator keyHashGenerator = new P2PKeyHashGenerator(128);
    	 
    	try {
    		try (Scanner scanner = new Scanner(nodesFile)) {
    		    while (scanner.hasNextLine()) {
    		    	
    		    	StringTokenizer tokenizer = new StringTokenizer(scanner.nextLine(), ",");
    		    	NodeServer node = new NodeServer();
    		    	
    		        while (tokenizer.hasMoreElements()) {
    		        	// format: node_id,node_host,node_port
    		        	String nodeName = tokenizer.nextToken().trim();
    		        	
    		        	// note: the hashcode AND-ed with 0x7fffffff to turn-off the negative sign
    		        	// so that the resulting hashcode is positive
    		        	BigInteger nodeId = keyHashGenerator.generateHash(nodeName.hashCode() & 0x7fffffff);
    		        	
    		        	//System.out.println("Name="+nodeName+", Before Id="+nodeName.hashCode()+", After Id="+nodeId);
    		        	
    		        	node.setId(nodeId);
    		        	node.setConnectionInfo(
    		        			new NettyConnectionInfo(tokenizer.nextToken().trim(), Integer.parseInt(tokenizer.nextToken().trim()))
    		        	);
    		        	node.setName(nodeName);
    		        	
    		        	servers.add(node);
    		        }
    		    }
    		}
    	} 
    	catch (Exception e) {
    	    // TO-DO
    		e.printStackTrace();
    	}
	}
	
	private static String getTypeDescription(int type) {
		String typeDescription = "Unknown";
		
		switch (type) {
			case P2PNodeServer.TYPE_DEFAULT:
				typeDescription = "Random";
				break;
			case P2PNodeServer.TYPE_REPUTATION:
				typeDescription = "Reputation";
				break;
			case P2PNodeServer.TYPE_PT:
				typeDescription = "PT";
				break;
			default:
		   		log.error("Invalid Type");
		}
		
		return typeDescription;
	}
	
	private static boolean isLoadDone() {
		boolean isDone = true;
		
		for (Future<Map<String, BigInteger>> future: futureList) {
			if (!future.isDone())
				isDone = false;
		}
		
		return isDone;
	}

}
