package org.gbtc.storage.client;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gbtc.storage.P2PKeyHashGenerator;
import org.gbtc.storage.P2PNodeServer;
import org.gbtc.storage.P2PNodeServerBuilder;
import org.gbtc.storage.P2PNodeSettings;
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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PStorageClientRunnable implements Runnable {
	
	private P2PNodeSettings nodeSettings;
	private ArrayList<NodeServer> servers = new ArrayList<NodeServer>();
	private P2PNodeServer nodeServer;
	private String name;
	private String host;
	private int port;
	
	private int lookupDelay = 0;
	private int storeDelay = 0;

	private int numThread;
	private int connectTimeout;
	private int readTimeout;
	private int writeTimeout;

//	private int lookupTimeout;
//	private int storeTimeout;
	
	private int dataCount;
	private int dataSize;
	
	private int type;
	private int mode;
	
	private Random random;
	
	@Getter
	private Map<String, BigInteger> dataMap = new HashMap<>();
	
	public P2PStorageClientRunnable(int type, P2PNodeSettings nodeSettings, ArrayList<NodeServer> servers, String name, String host, int port, 
			int lookupDelay, int storeDelay, int numThread, int connectTimeout, int readTimeout, int writeTimeout, int mode, int dataCount, int dataSize) {

		this.type = type;
		
		this.nodeSettings = nodeSettings;
		this.servers = servers;
		this.name = name;
		this.host = host;
		this.port = port;
		
		this.lookupDelay = lookupDelay;
		this.storeDelay = storeDelay;
//		this.lookupTimeout = lookupTimeout;
//		this.storeTimeout = storeTimeout;
		
		this.numThread = numThread;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.writeTimeout = writeTimeout;

		this.mode = mode;
		this.dataCount = dataCount;
		this.dataSize = dataSize;
	}
	
//	public P2PStorageClientRunnable(P2PNodeSettings nodeSettings, ArrayList<NodeServer> servers, P2PNodeServer clientNode, String name, 
//			int lookupDelay, int storeDelay, int lookupTimeout, int storeTimeout, int mode, int dataCount, int dataSize) {
//
//		this.nodeSettings = nodeSettings;
//		this.servers = servers;
//		this.node = clientNode;
//		this.name = name;
//		
//		this.lookupDelay = lookupDelay;
//		this.storeDelay = storeDelay;
//		this.lookupTimeout = lookupTimeout;
//		this.storeTimeout = storeTimeout;
//		this.mode = mode;
//		
//		this.dataCount = dataCount;
//		this.dataSize = dataSize;
//	}

//	public void run() {
//		
//		try {
//			random = new Random(name.hashCode() & 0x7fffffff);
//			
//			generateData(dataCount, dataSize);
//			
//			startNode();
//			Thread.sleep(1000);
//			
//			if (type == 1) {
//				// load
//				runLoad();
//			}
//			else {
//				runClients();
//			}
//			Thread.sleep(5000);
//			
//			stopNode();
//			Thread.sleep(1000);
//		}
//		catch (Exception e) {
//			log.error(e.getMessage());
//		}
//	}
	
	public void run() {
		
		try {
			random = new Random(name.hashCode() & 0x7fffffff);
			
			generateData(dataCount, dataSize);
			
			startNode();
			Thread.sleep(1000);
			
			if (mode == 1)
				// random
				storeRetrieveData();
			else if (mode == 2)
				// PT
				storeRetrieveDataPT();
			Thread.sleep(5000);
			
			stopNode();
			Thread.sleep(1000);
			
			log.info("<<<<< Client " + this.name+ " (Load) -- Completed >>>>>");
		}
		catch (Exception e) {
			log.error(e.getMessage());
		}
	}

	
//	public void runLoad() {
//		try {
//			if (mode == 1)
//				// random
//				storeRetrieveData();
//			else if (mode == 2)
//				// PT
//				storeRetrieveDataPT();
//
//			log.info("<<<<< Client " + this.name+ " (Load) -- Completed >>>>>");
//		}
//		catch (Exception e) {
//			log.error(e.getMessage());
//		}		
//	}
//	
//	
//	public void runClients() {
//		try {
//			storeData();
//			Thread.sleep(60000);
//			
//			if (mode == 1) {
//				// random
//				retrieveData();
//				log.info("<<<<< Client " + this.name+ " (Random) -- Completed >>>>>");
//			}
//			else if (mode == 2) {
//				// PT
//				retrieveDataPT();
//				log.info("<<<<< Client " + this.name+ " (PT) -- Completed >>>>>");
//			}
//		}
//		catch (Exception e) {
//			log.error(e.getMessage());
//		}	
//	}
	
//	private void startNode() {
//        
//    	if (mode == 1)
//    		log.info(String.format("Starting Client Node (Random) [Name=%s, ID=%s]...", this.name, node.getId()));
//    	else if (mode == 2)
//    		log.info(String.format("Starting Client Node (PT) [Name=%s, ID=%s]...", this.name, node.getId()));
//        
//        boolean success = node.startClientNode();
//        
//        if (success) {
//        	if (mode == 1)
//        		log.info(String.format("Client Node (Random) [Name=%s, ID=%s] is running at %s:%s", this.name, node.getId(), node.getConnectionInfo().getHost(), node.getConnectionInfo().getPort()));
//        	else if (mode == 2)
//        		log.info(String.format("Client Node (PT) [Name=%s, ID=%s] is running at %s:%s", this.name, node.getId(), node.getConnectionInfo().getHost(), node.getConnectionInfo().getPort()));
//        }
//	}
	
	private void startNode() {
        P2PKeyHashGenerator keyHashGenerator = new P2PKeyHashGenerator(nodeSettings.getIdentifierSize()); 
        FileRepository fileRepository = new FileRepository(nodeSettings.getDataPath(), 0, 0);
		
        BigInteger nodeId = keyHashGenerator.generateHash(this.name.hashCode() & 0x7fffffff);
        
        GsonFactory gsonFactory = new P2PGsonFactory.DefaultGsonFactory<BigInteger, NettyConnectionInfo, String, FileDTO>(
        		BigInteger.class, NettyConnectionInfo.class, String.class, FileDTO.class);

        P2PMessageSender messageSender = new P2PMessageSender(
				new GsonMessageSerializer<BigInteger, NettyConnectionInfo, String, FileDTO> (gsonFactory.gsonBuilder()),
				numThread, connectTimeout, readTimeout, writeTimeout);

        
        nodeServer = new P2PNodeServerBuilder(
                nodeId,
                new NettyConnectionInfo(this.host, this.port),
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
        
    	//log.info(String.format("Starting Client Node [Name=%s, ID=%s]...", this.name, node.getId()));
//    	if (mode == 1)
//    		log.info(String.format("Starting Client Node (Random) [Name=%s, ID=%s]...", this.name, node.getId()));
//    	else if (mode == 2)
//    		log.info(String.format("Starting Client Node (PT) [Name=%s, ID=%s]...", this.name, node.getId()));
        
        boolean success = nodeServer.startClientNode();
        
//		if (success) {
//			if (mode == 1)
//				log.info(String.format("Client Node (Random) [Name=%s, ID=%s] is running at %s:%s", this.name,
//						nodeServer.getId(), nodeServer.getConnectionInfo().getHost(), nodeServer.getConnectionInfo().getPort()));
//			else if (mode == 2)
//				log.info(String.format("Client Node (PT) [Name=%s, ID=%s] is running at %s:%s", this.name, nodeServer.getId(),
//						nodeServer.getConnectionInfo().getHost(), nodeServer.getConnectionInfo().getPort()));
//		}
        
//        if (success)
//        	log.info(String.format("Client Node [Name=%s, ID=%s] is running at %s:%s", this.name, node.getId(), node.getConnectionInfo().getHost(), node.getConnectionInfo().getPort()));

	}
	
	private void generateData(int count, int size) {
		try {
			Path path = Paths.get("data",this.name);
			Files.createDirectories(path);
			  
			for (int i=1; i<=count; i++) {
				
			    Path dataPath = Paths.get(path.toString(), this.name + "_" + i + ".dat");
			    if (dataPath.toFile().exists())
			    	continue;
			    
			    Files.createFile(dataPath);
			    
			    byte[] bytes = new byte[size * 1024];
			    BufferedOutputStream bos = null;
			    FileOutputStream fos = null;
			    
			    fos = new FileOutputStream(dataPath.toFile(), false);
			    bos = new BufferedOutputStream(fos);
			    bos.write(bytes);
			    
//			    RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "rw");
//			    raf.setLength(size * 1024);
//			    raf.close();
			    
                bos.flush();
                bos.close();
                fos.flush();
                fos.close();
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
	}
	
	private void storeData() {
		// TO-DO: Use workload generator with exponential distribution
		try {
			List<File> files = new ArrayList<File>();
			Stream<Path> stream = Files.list(Paths.get("data",this.name));
			
	        files = stream
	  	          .filter(file -> !Files.isDirectory(file))
	  	          .map(Path::toFile)
	  	          .collect(Collectors.toList());
			
			for (File file : files) {
				// if size greater than threshold, split the file into multiple chunks
				//Path path = Paths.get(file.getPath());
				double size = (double) file.length() / 1024; // size in Kilobytes
				
				log.debug("File (name, size) : "+file.getName()+", "+size);
				
				ArrayList<File> chunks = new ArrayList<File>();
				
				if (size > nodeSettings.getDataSize()) {
					chunks = splitBySize(file, nodeSettings.getDataSize() * 1024);
				}
				else {
					chunks.add(file);
				}
				
				for (File chunk : chunks) {
					
					// Note: during store, for the purpose of lookup the closest nodes, 
					// the StoreService will generate hash of the key using KeyHashGenerator
					// that bounds the key to the network size

					// transform the chunks into byte array
		        	byte[] content = new byte[(int) chunk.length()];
		        	FileInputStream inputStream = new FileInputStream(chunk);
		        	inputStream.read(content);
					
					FileDTO value = new FileDTO();
					value.setName(chunk.getName());
					value.setContent(content);
					inputStream.close();
					
					String key = ""+ (value.getName().hashCode() & 0x7fffffff);
					
					// randomly selects a server
					NodeServer server = servers.get(random.nextInt(servers.size()));
					
					log.debug("Selected server: "+server.getId()+", "+server.getConnectionInfo().getHost()+", "+server.getConnectionInfo().getPort());
					
					BigInteger storerNodeId = nodeServer.clientStore(server, key, value, writeTimeout);
					
					if (storerNodeId != null) {
						// put into data map because it will be used during lookup
						dataMap.put(key, storerNodeId);
						log.info(String.format("Store object: %s(%s), node: %s, storer: %s, result: STORED", value.getName(), key, server.getId(), storerNodeId));
					}
					else
						log.info(String.format("Store object: %s(%s), node: %s, result: FAILED", value.getName(), key, server.getId()));
					
					if (storeDelay > 0)
						Thread.sleep(storeDelay);
					
				}
			}
			
			stream.close();
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
	}
	
	
	private void retrieveData() {
		// TO-DO: Use workload generator with exponential distribution
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter("logs/"+this.name + "_response_time.csv", true));
			String header = String.format("RandomNode ID, Object, Key, Request Timestamp, Response Timestamp, Duration (milliseconds), Internal Duration, Result");
			
			writer.write(header);
			writer.newLine();
			writer.flush();
			
			List<File> files = new ArrayList<File>();
			Stream<Path> stream = Files.list(Paths.get("data",this.name));
			
			files = stream
		  	          .filter(file -> !Files.isDirectory(file))
		  	          .map(Path::toFile)
		  	          .collect(Collectors.toList());
			
  			for (File file : files) {
  				// if size greater than threshold, split the file into multiple chunks
				// Path path = Paths.get(file.getPath());
				double size = (double) file.length() / 1024; // size in Kilobytes

				log.debug("File (name, size) : " + file.getName() + ", " + size);

				ArrayList<File> chunks = new ArrayList<File>();

				if (size > nodeSettings.getDataSize()) {
					chunks = splitBySize(file, nodeSettings.getDataSize() * 1024);
				} else {
					chunks.add(file);
				}

				for (File chunk : chunks) {

					String key = "" + (chunk.getName().hashCode() & 0x7fffffff);

					// randomly selects a server
					NodeServer server = servers.get(random.nextInt(servers.size()));

					log.debug("Selected server: " + server.getId() + ", " + server.getConnectionInfo().getHost() + ", "
							+ server.getConnectionInfo().getPort());

					log.debug(String.format("Looking up data %s(%s) to node %s", chunk.getName(), key, server.getId()));

					long start = System.currentTimeMillis();

					FileDTO value = nodeServer.clientLookup(server, key, readTimeout);

					long end = System.currentTimeMillis();

					if (value != null) {
						log.info(String.format("Retrieve object: %s(%s), node: %s, result: %s", value.getName(), key,
								server.getId(), "FOUND"));
						
						String line = String.format("%s, %s, %s, %s, %s, %s, %s, %s", server.getId(), chunk.getName(), key, start, end, (end - start), value.getReadLatency(), "FOUND");
						writer.write(line);
						writer.newLine();
						
					} else {
						// failed
						log.info(String.format("Retrieve object: (%s), node: %s, result: %s", key, server.getId(),
								"FAILED"));

						String line = String.format("%s, %s, %s, %s, %s, %s, %s, %s", server.getId(), chunk.getName(), key, start, end, (end - start), -1, "FAILED");
						writer.write(line);
						writer.newLine();
					}
					
					writer.flush();

					if (lookupDelay > 0)
						Thread.sleep(lookupDelay);
				}
  			}
  			
  			writer.close();
  			stream.close();
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
	}
	
	private void retrieveDataPT() {
		// TO-DO: Use workload generator with exponential distribution
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter("logs/"+this.name + "_response_time.csv", true));
			String header = String.format("PTNode ID, Object, Key, Request Timestamp, Response Timestamp, Duration (milliseconds), Internal Duration, Result");
			
			writer.write(header);
			writer.newLine();
			writer.flush();
			
			List<File> files = new ArrayList<File>();
			Stream<Path> stream = Files.list(Paths.get("data",this.name));
			
			files = stream
		  	          .filter(file -> !Files.isDirectory(file))
		  	          .map(Path::toFile)
		  	          .collect(Collectors.toList());
			
			for (File file : files) {
				// if size greater than threshold, split the file into multiple chunks
				//Path path = Paths.get(file.getPath());
				double size = (double) file.length() / 1024; // size in Kilobytes
				
				log.debug("File (name, size) : "+file.getName()+", "+size);
				
				ArrayList<File> chunks = new ArrayList<File>();
				
				if (size > nodeSettings.getDataSize()) {
					chunks = splitBySize(file, nodeSettings.getDataSize() * 1024);
				}
				else {
					chunks.add(file);
				}
				
				for (File chunk : chunks) {
					String key = ""+ (chunk.getName().hashCode() & 0x7fffffff);
					// randomly selects a server
					NodeServer server = servers.get(random.nextInt(servers.size()));
					
					log.debug("Selected server: "+server.getId()+", "+server.getConnectionInfo().getHost()+", "+server.getConnectionInfo().getPort());
					
					BigInteger storerNodeId = dataMap.get(key);
					
					if (storerNodeId == null)
						continue;
					
					log.debug(String.format("Looking up data %s(%s) to node %s, storer=%s", chunk.getName(), key, server.getId(), storerNodeId)); 
					
					long start = System.currentTimeMillis();
					
					FileDTO value = nodeServer.clientLookup(server, storerNodeId+"@"+key, readTimeout);
					
					long end = System.currentTimeMillis();
					
					if (value != null) {
						log.info(String.format("Retrieve object: %s(%s), node: %s, result: %s", value.getName(), key, server.getId(), "FOUND"));
						String line = String.format("%s, %s, %s, %s, %s, %s, %s, %s", server.getId(), chunk.getName(), key, start, end, (end-start), value.getReadLatency(), "FOUND");
						writer.write(line);
						writer.newLine();
					}
					else {
						// failed
						log.info(String.format("Retrieve object: (%s), node: %s, result: %s", key, server.getId(), "FAILED"));
						String line = String.format("%s, %s, %s, %s, %s, %s, %s, %s", server.getId(), chunk.getName(), key, start, end, (end-start), -1, "FAILED");
						writer.write(line);
						writer.newLine();
					}
					
					writer.flush();

					if (lookupDelay > 0)
						Thread.sleep(lookupDelay);
				}
			}
  			writer.close();
  			stream.close();
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
	}
	
	private void storeRetrieveData() {
		// TO-DO: Use workload generator with exponential distribution
		try {
			List<File> files = new ArrayList<File>();
			Stream<Path> stream = Files.list(Paths.get("data",this.name));
			
	        files = stream
	  	          .filter(file -> !Files.isDirectory(file))
	  	          .map(Path::toFile)
	  	          .collect(Collectors.toList());
			
			for (File file : files) {
				// if size greater than threshold, split the file into multiple chunks
				//Path path = Paths.get(file.getPath());
				double size = (double) file.length() / 1024; // size in Kilobytes
				
				log.debug("File (name, size) : "+file.getName()+", "+size);
				
				ArrayList<File> chunks = new ArrayList<File>();
				
				if (size > nodeSettings.getDataSize()) {
					chunks = splitBySize(file, nodeSettings.getDataSize() * 1024);
				}
				else {
					chunks.add(file);
				}
				
				for (File chunk : chunks) {
					
					// Note: during store, for the purpose of lookup the closest nodes, 
					// the StoreService will generate hash of the key using KeyHashGenerator
					// that bounds the key to the network size

					// transform the chunks into byte array
		        	byte[] content = new byte[(int) chunk.length()];
		        	FileInputStream inputStream = new FileInputStream(chunk);
		        	inputStream.read(content);
					
					FileDTO value = new FileDTO();
					value.setName(chunk.getName());
					value.setContent(content);
					inputStream.close();
					
					String key = ""+ (value.getName().hashCode() & 0x7fffffff);
					
					// randomly selects a server
					NodeServer server = servers.get(random.nextInt(servers.size()));
					
					log.debug("Selected server: "+server.getId()+", "+server.getConnectionInfo().getHost()+", "+server.getConnectionInfo().getPort());
					
					BigInteger storerNodeId = nodeServer.clientStore(server, key, value, writeTimeout);
					
					if (storerNodeId != null) {
						// put into data map because it will be used during lookup
						dataMap.put(key, storerNodeId);
						log.info(String.format("Store object: %s(%s), node: %s, storer: %s, result: STORED", value.getName(), key, server.getId(), storerNodeId));
					}
					else
						log.info(String.format("Store object: %s(%s), node: %s, result: FAILED", value.getName(), key, server.getId()));
					
					if (storeDelay > 0)
						Thread.sleep(storeDelay);
					
					// proceed to lookup
					FileDTO lookupValue = nodeServer.clientLookup(server, key, readTimeout);
					
					if (lookupValue != null)
						log.info(String.format("Retrieve object: %s(%s), node: %s, result: %s", lookupValue.getName(), key, nodeServer.getId(), "FOUND"));
					else
						// failed
						log.info(String.format("Retrieve object: (%s), node: %s, result: %s", key, nodeServer.getId(), "FAILED"));

					if (lookupDelay > 0)
						Thread.sleep(lookupDelay);
				}
			}
			
			stream.close();
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
	}
	
	
	private void storeRetrieveDataPT() {
		// TO-DO: Use workload generator with exponential distribution
		try {
			List<File> files = new ArrayList<File>();
			Stream<Path> stream = Files.list(Paths.get("data",this.name));
			
	        files = stream
	  	          .filter(file -> !Files.isDirectory(file))
	  	          .map(Path::toFile)
	  	          .collect(Collectors.toList());
			
			for (File file : files) {
				// if size greater than threshold, split the file into multiple chunks
				//Path path = Paths.get(file.getPath());
				double size = (double) file.length() / 1024; // size in Kilobytes
				
				log.debug("File (name, size) : "+file.getName()+", "+size);
				
				ArrayList<File> chunks = new ArrayList<File>();
				
				if (size > nodeSettings.getDataSize()) {
					chunks = splitBySize(file, nodeSettings.getDataSize() * 1024);
				}
				else {
					chunks.add(file);
				}
				
				for (File chunk : chunks) {
					
					// Note: during store, for the purpose of lookup the closest nodes, 
					// the StoreService will generate hash of the key using KeyHashGenerator
					// that bounds the key to the network size

					// transform the chunks into byte array
		        	byte[] content = new byte[(int) chunk.length()];
		        	FileInputStream inputStream = new FileInputStream(chunk);
		        	inputStream.read(content);
					
					FileDTO value = new FileDTO();
					value.setName(chunk.getName());
					value.setContent(content);
					inputStream.close();
					
					String key = ""+ (value.getName().hashCode() & 0x7fffffff);
					
					// randomly selects a server
					NodeServer server = servers.get(random.nextInt(servers.size()));
					
					log.debug("Selected server: "+server.getId()+", "+server.getConnectionInfo().getHost()+", "+server.getConnectionInfo().getPort());
					
					BigInteger storerNodeId = nodeServer.clientStore(server, key, value, writeTimeout);
					
					if (storerNodeId != null) {
						// put into data map because it will be used during lookup
						dataMap.put(key, storerNodeId);
						log.info(String.format("Store object: %s(%s), node: %s, storer: %s, result: STORED", value.getName(), key, server.getId(), storerNodeId));
					}
					else
						log.info(String.format("Store object: %s(%s), node: %s, result: FAILED", value.getName(), key, server.getId()));
					
					if (storeDelay > 0)
						Thread.sleep(storeDelay);
					
					FileDTO lookupValue = nodeServer.clientLookup(server, storerNodeId+"@"+key, readTimeout);
					
					if (lookupValue != null)
						log.info(String.format("Retrieve object: %s(%s), node: %s, result: %s", lookupValue.getName(), key, nodeServer.getId(), "FOUND"));
					else
						// failed
						log.info(String.format("Retrieve object: (%s), node: %s, result: %s", key, nodeServer.getId(), "FAILED"));

					if (lookupDelay > 0)
						Thread.sleep(lookupDelay);
				}
			}
			
			stream.close();
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
	}
	
	private void stopNode() {
		nodeServer.stopClientNode();
	}
	
	private ArrayList<File> splitBySize(File file, int maxSize) {
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

}
