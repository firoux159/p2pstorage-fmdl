package org.gbtc.storage.client;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
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
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PStorageClientCallable implements Callable<Map<String, BigInteger>> {
	
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
	
	private Random random;
	
	//private Map<String, BigInteger> dataMap = new HashMap<>();
	
	public P2PStorageClientCallable(P2PNodeSettings nodeSettings, ArrayList<NodeServer> servers, String name, String host, int port, 
			int lookupDelay, int storeDelay, int numThread, int connectTimeout, int readTimeout, int writeTimeout, int dataCount, int dataSize) {

		this.nodeSettings = nodeSettings;
		this.servers = servers;
		this.name = name;
		this.host = host;
		this.port = port;
		
		this.lookupDelay = lookupDelay;
		this.storeDelay = storeDelay;
		
		this.numThread = numThread;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.writeTimeout = writeTimeout;

		this.dataCount = dataCount;
		this.dataSize = dataSize;
	}
	
	public Map<String, BigInteger> call() {
		
		Map<String, BigInteger> dataMap = null;
		
		try {
			random = new Random(name.hashCode() & 0x7fffffff);
			
			generateData(dataCount, dataSize);
			
			startNode();
			Thread.sleep(1000);
			
 			dataMap = storeData();
			
			Thread.sleep(5000);
			
			stopNode();
			Thread.sleep(1000);
			
			log.info("<<<<< Client " + this.name+ " (Load) -- Completed >>>>>");
		}
		catch (Exception e) {
			log.error(e.getMessage());
		}		
		
		return dataMap;
	}
	
	
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
        nodeServer.setName(name);
        nodeServer.setLookupDelay(lookupDelay);
		nodeServer.setStoreDelay(storeDelay);

        boolean success = nodeServer.startClientNode();
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
	
	private Map<String, BigInteger> storeData() {
		// TO-DO: Use workload generator with exponential distribution
		Map<String, BigInteger> dataMap = new HashMap<>();
		
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
		
		return dataMap;
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
	
//	private Map<String, BigInteger> storeRetrieveData() {
//		// TO-DO: Use workload generator with exponential distribution
//		Map<String, BigInteger> dataMap = new HashMap<>();
//		
//		try {
//			List<File> files = new ArrayList<File>();
//			Stream<Path> stream = Files.list(Paths.get("data",this.name));
//			
//	        files = stream
//	  	          .filter(file -> !Files.isDirectory(file))
//	  	          .map(Path::toFile)
//	  	          .collect(Collectors.toList());
//			
//			for (File file : files) {
//				// if size greater than threshold, split the file into multiple chunks
//				//Path path = Paths.get(file.getPath());
//				double size = (double) file.length() / 1024; // size in Kilobytes
//				
//				log.debug("File (name, size) : "+file.getName()+", "+size);
//				
//				ArrayList<File> chunks = new ArrayList<File>();
//				
//				if (size > nodeSettings.getDataSize()) {
//					chunks = splitBySize(file, nodeSettings.getDataSize() * 1024);
//				}
//				else {
//					chunks.add(file);
//				}
//				
//				for (File chunk : chunks) {
//					
//					// Note: during store, for the purpose of lookup the closest nodes, 
//					// the StoreService will generate hash of the key using KeyHashGenerator
//					// that bounds the key to the network size
//
//					// transform the chunks into byte array
//		        	byte[] content = new byte[(int) chunk.length()];
//		        	FileInputStream inputStream = new FileInputStream(chunk);
//		        	inputStream.read(content);
//					
//					FileDTO value = new FileDTO();
//					value.setName(chunk.getName());
//					value.setContent(content);
//					inputStream.close();
//					
//					String key = ""+ (value.getName().hashCode() & 0x7fffffff);
//					
//					// randomly selects a server
//					NodeServer server = servers.get(random.nextInt(servers.size()));
//					
//					log.debug("Selected server: "+server.getId()+", "+server.getConnectionInfo().getHost()+", "+server.getConnectionInfo().getPort());
//					
//					BigInteger storerNodeId = nodeServer.clientStore(server, key, value, writeTimeout);
//					
//					if (storerNodeId != null) {
//						// put into data map because it will be used during lookup
//						dataMap.put(key, storerNodeId);
//						log.info(String.format("Store object: %s(%s), node: %s, storer: %s, result: STORED", value.getName(), key, server.getId(), storerNodeId));
//					}
//					else
//						log.info(String.format("Store object: %s(%s), node: %s, result: FAILED", value.getName(), key, server.getId()));
//					
//					if (storeDelay > 0)
//						Thread.sleep(storeDelay);
//					
//					// proceed to lookup
//					FileDTO lookupValue = nodeServer.clientLookup(server, key, readTimeout);
//					
//					if (lookupValue != null)
//						log.info(String.format("Retrieve object: %s(%s), node: %s, result: %s", lookupValue.getName(), key, nodeServer.getId(), "FOUND"));
//					else
//						// failed
//						log.info(String.format("Retrieve object: (%s), node: %s, result: %s", key, nodeServer.getId(), "FAILED"));
//
//					if (lookupDelay > 0)
//						Thread.sleep(lookupDelay);
//				}
//			}
//			
//			stream.close();
//		}
//		catch (Exception e) {
//			e.printStackTrace();
//			log.error(e.getMessage());
//		}
//		
//		return dataMap;
//	}
//	
//	
//	private Map<String, BigInteger> storeRetrieveDataPT() {
//		// TO-DO: Use workload generator with exponential distribution
//		Map<String, BigInteger> dataMap = new HashMap<>();
//		
//		try {
//			List<File> files = new ArrayList<File>();
//			Stream<Path> stream = Files.list(Paths.get("data",this.name));
//			
//	        files = stream
//	  	          .filter(file -> !Files.isDirectory(file))
//	  	          .map(Path::toFile)
//	  	          .collect(Collectors.toList());
//			
//			for (File file : files) {
//				// if size greater than threshold, split the file into multiple chunks
//				//Path path = Paths.get(file.getPath());
//				double size = (double) file.length() / 1024; // size in Kilobytes
//				
//				log.debug("File (name, size) : "+file.getName()+", "+size);
//				
//				ArrayList<File> chunks = new ArrayList<File>();
//				
//				if (size > nodeSettings.getDataSize()) {
//					chunks = splitBySize(file, nodeSettings.getDataSize() * 1024);
//				}
//				else {
//					chunks.add(file);
//				}
//				
//				for (File chunk : chunks) {
//					
//					// Note: during store, for the purpose of lookup the closest nodes, 
//					// the StoreService will generate hash of the key using KeyHashGenerator
//					// that bounds the key to the network size
//
//					// transform the chunks into byte array
//		        	byte[] content = new byte[(int) chunk.length()];
//		        	FileInputStream inputStream = new FileInputStream(chunk);
//		        	inputStream.read(content);
//					
//					FileDTO value = new FileDTO();
//					value.setName(chunk.getName());
//					value.setContent(content);
//					inputStream.close();
//					
//					String key = ""+ (value.getName().hashCode() & 0x7fffffff);
//					
//					// randomly selects a server
//					NodeServer server = servers.get(random.nextInt(servers.size()));
//					
//					log.debug("Selected server: "+server.getId()+", "+server.getConnectionInfo().getHost()+", "+server.getConnectionInfo().getPort());
//					
//					BigInteger storerNodeId = nodeServer.clientStore(server, key, value, writeTimeout);
//					
//					if (storerNodeId != null) {
//						// put into data map because it will be used during lookup
//						dataMap.put(key, storerNodeId);
//						log.info(String.format("Store object: %s(%s), node: %s, storer: %s, result: STORED", value.getName(), key, server.getId(), storerNodeId));
//					}
//					else
//						log.info(String.format("Store object: %s(%s), node: %s, result: FAILED", value.getName(), key, server.getId()));
//					
//					if (storeDelay > 0)
//						Thread.sleep(storeDelay);
//					
//					FileDTO lookupValue = nodeServer.clientLookup(server, storerNodeId+"@"+key, readTimeout);
//					
//					if (lookupValue != null)
//						log.info(String.format("Retrieve object: %s(%s), node: %s, result: %s", lookupValue.getName(), key, nodeServer.getId(), "FOUND"));
//					else
//						// failed
//						log.info(String.format("Retrieve object: (%s), node: %s, result: %s", key, nodeServer.getId(), "FAILED"));
//
//					if (lookupDelay > 0)
//						Thread.sleep(lookupDelay);
//				}
//			}
//			
//			stream.close();
//		}
//		catch (Exception e) {
//			e.printStackTrace();
//			log.error(e.getMessage());
//		}
//		
//		return dataMap;
//	}
	

}
