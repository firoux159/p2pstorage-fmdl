package org.gbtc.storage.client;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.gbtc.storage.P2PKeyHashGenerator;
import org.gbtc.storage.P2PNodeServer;
import org.gbtc.storage.P2PNodeServerBuilder;
import org.gbtc.storage.P2PNodeSettings;
import org.gbtc.storage.UniformRandomGenerator;
import org.gbtc.storage.repository.FileDTO;
import org.gbtc.storage.repository.FileRepository;
import org.gbtc.storage.serialization.P2PGsonFactory;
import org.gbtc.storage.services.ClientRetrieveService;
import org.gbtc.storage.services.ClientStoreService;

import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.serialization.gson.GsonFactory;
import io.ep2p.kademlia.serialization.gson.GsonMessageSerializer;
import io.ep2p.kademlia.services.DHTLookupServiceAPI;
import io.ep2p.kademlia.services.DHTLookupServiceFactory;
import io.ep2p.kademlia.services.DHTStoreServiceAPI;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PClientManager {
	
	private long seed;
	
	private Scanner menuScanner;
	
	private Random random;
	
    private int clientNumThread;
	
	private static int storeDelay;
	private static int lookupDelay;
	
	private int statusPeriod;
	private int warmupPeriod; // in minutes
	
	private int clientStoreDelay = 0;
	private int clientLookupDelay = 0;
	
	private int clientConnectTimeout;
	private int clientReadTimeout;
	private int clientWriteTimeout;
	
	private int type;
	
	private String host;
	private int port;
	private int clientCount;
	private int dataCount;
	private int dataSize;
	
	private int iteration = 0;
	private int sampleCount = 0;
	
	private ArrayList<NodeServer> servers = new ArrayList<NodeServer>();
	
	private P2PNodeSettings nodeSettings;
	private P2PNodeServer nodeServer;
	private static ExecutorService executorService;
	
	private static P2PNodeStatusMonitor monitor;
	
	private static ArrayList<Future<Map<String, BigInteger>>> futureList = new ArrayList<Future<Map<String, BigInteger>>>();
	
	
	public P2PClientManager(int type, String host, int port, int clientCount, int dataCount, int dataSize, int iteration, int sampleCount) {
		
		this.type = type;
		this.host = host;
		this.port = port;
		this.clientCount = clientCount;
		this.dataCount = dataCount;
		this.dataSize = dataSize;
		this.iteration = iteration;
		this.sampleCount = sampleCount;
		
		readConfiguration();
		loadNodes();
	}
	
	public void control() {
		
		char response = 'X';
		do {
			try {
				response = Character.toUpperCase(displayMenu());

				switch (response) {
					case '1':
						handleWarmup();
						handleLoad();
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
	
	public char displayMenu() throws Exception {
		
		System.out.println("\n** P2P Storage Client **");
		
		System.out.printf("%4s\n", "1. Warm Up & Load Data");
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
	
	public void handleWarmup() {
		
		log.info(String.format("Start warmup for %s seconds ...", warmupPeriod));
		
		// start a client node
		String name = host+"_WarmupClient";

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
        
        boolean success = nodeServer.startClientNode();
        
		// perform warmup by doing random lookup
		UniformRandomGenerator uniformRandomGenerator = new UniformRandomGenerator(nodeSettings.getIdentifierSize(), seed);
		Random random = new Random(seed);
		
		long start = System.currentTimeMillis();
		long lapsed = 0;
		
		while (lapsed < warmupPeriod) {
			// generate random key to lookup
			String randomKey = uniformRandomGenerator.generate().toString();

			// randomly selects a server
			// Note: No need to include virtual nodes because this is just the starting server, request will eventually reached the virtual node
			// if the key is closest to it
			NodeServer server = servers.get(random.nextInt(servers.size()));
			
			log.debug(String.format("Lookup Key %s to Node %s", randomKey, server.getId()));
			
			FileDTO value = nodeServer.clientLookup(server, randomKey, clientReadTimeout);
			
			lapsed = (System.currentTimeMillis() - start)/1000;
		}

		nodeServer.stopClientNode();
		
		log.info(String.format("Warmup of %s seconds completed", warmupPeriod));
	}
	
	@SneakyThrows
	public void handleLoad() {

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
		
		// periodically check for data loading completion
		try {
			Thread.sleep(60000);
			
			while (!isLoadDone()) {
				Thread.sleep(60000);
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
		
		return;
	}
	
	private boolean isLoadDone() {
		boolean isDone = true;
		
		for (Future<Map<String, BigInteger>> future: futureList) {
			if (!future.isDone())
				isDone = false;
		}
		
		return isDone;
	}
	
	private String getTypeDescription(int type) {
		String typeDescription = "Unknown";
		
		switch (type) {
			case P2PNodeServer.TYPE_DEFAULT:
				typeDescription = "Random";
				break;
			case P2PNodeServer.TYPE_VN:
				typeDescription = "Virtual Node";
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
	
	public void handleRetrieveMultiIteration() {
		
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
	
	public void handleRetrieveInner(int iteration) {
		
		// start a client node
		String name = host+"_RetrieveClient";

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
        
        boolean success = nodeServer.startClientNode();
        
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
			if (type == P2PNodeServer.TYPE_VN) {
				fileName = "logs/vn_response_time.csv";
				header = String.format("Iteration, VNNode ID, Key, Request Timestamp, Response Timestamp, Duration (milliseconds), Internal Duration, Result");
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
				
				if (type == P2PNodeServer.TYPE_DEFAULT || type == P2PNodeServer.TYPE_VN)
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
	
	public void handleWorkloadStatus() {
		monitor = new P2PNodeStatusMonitor(type, nodeSettings, servers, host, clientNumThread, clientConnectTimeout, clientReadTimeout, clientWriteTimeout, statusPeriod);
		monitor.start();
	}
	

	@SneakyThrows
	public void handleStop() {
		if (executorService != null)
			executorService.shutdownNow();
		
		if (monitor != null)
			monitor.stop();
	}
	
	
	public ArrayList<File> splitBySize(File file, int maxSize) {
		ArrayList<File> list = new ArrayList<>();
	    
//	    try {
//	    	InputStream in = Files.newInputStream(file.toPath());
//	    	final byte[] buffer = new byte[maxSize];
//	        int dataRead = in.read(buffer);
//	        
//	        int sequence = 1;
//	        
//	        while (dataRead > -1) {
//	            File fileChunk = stageFile(file.getName(), sequence, buffer, dataRead);
//	            list.add(fileChunk);
//	            dataRead = in.read(buffer);
//	            sequence++;
//	        }
//	    }
//	    catch (Exception e) {
//	    	e.printStackTrace();
//	    	log.error(e.getMessage());
//	    }
	    
	    return list;
	}
	
	private File stageFile(String prefix, int index, byte[] buffer, int length) {
	    
		File outputFile = new File(nodeSettings.getDataTempPath(), prefix+"-split"+index);
		
		try (FileOutputStream fos = new FileOutputStream(outputFile)) {
			fos.write(buffer, 0, length);
		}
	    catch (Exception e) {
	    	log.error(e.getMessage());
	    }

		return outputFile;
	}
	
	public void readConfiguration() {
    	// read configuration from file
    	File configFile = new File("config.properties");
    	 
    	try {
    	    FileReader reader = new FileReader(configFile);
    	    Properties props = new Properties();
    	    props.load(reader);
    	    
    	    seed = Long.parseLong(props.getProperty("SEED"));
    	    
    	    lookupDelay = Integer.parseInt(props.getProperty("CLIENT_LOOKUP_DELAY"));
    	    storeDelay = Integer.parseInt(props.getProperty("CLIENT_STORE_DELAY"));
    	    
    	    clientNumThread = Integer.parseInt(props.getProperty("CLIENT_NUM_THREAD"));
    	    clientConnectTimeout = Integer.parseInt(props.getProperty("CLIENT_CONNECT_TIMEOUT"));
    	    clientReadTimeout = Integer.parseInt(props.getProperty("CLIENT_READ_TIMEOUT"));
    	    clientWriteTimeout = Integer.parseInt(props.getProperty("CLIENT_WRITE_TIMEOUT"));
    	    
    	    warmupPeriod = Integer.parseInt(props.getProperty("WARMUP_PERIOD"));
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
	
	public void loadNodes() {
    	// read configuration from file
    	File nodesFile = new File("nodes.txt");
    	
    	P2PKeyHashGenerator keyHashGenerator = new P2PKeyHashGenerator(nodeSettings.identifierSize);
    	 
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
	
    public static HashMap<NodeServer, Double> sortByValue(HashMap<NodeServer, Double> hashMap, int mode) {
    	HashMap<NodeServer, Double> sorted = null;
    	
    	if (mode == 1) {
    		// ascending
        	sorted = hashMap.entrySet()
            		.stream()
            		.sorted(Map.Entry.comparingByValue())
            		.collect(Collectors.toMap(
            				Map.Entry::getKey, 
            				Map.Entry::getValue, 
            				(e1, e2) -> e1, LinkedHashMap::new));
    	}
    	else if (mode == 2) {
    		// descending
        	sorted = hashMap.entrySet()
            		.stream()
            		.sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
            		.collect(Collectors.toMap(
            				Map.Entry::getKey, 
            				Map.Entry::getValue, 
            				(e1, e2) -> e1, LinkedHashMap::new));
    	}
    	else {
    		log.error("Invalid sorting mode!");
    	}
    	
    	return sorted;
    }

	
}
