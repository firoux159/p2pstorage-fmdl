package org.gbtc.storage;

import java.io.File;
import java.io.FileReader;
import java.math.BigInteger;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Executors;

import org.gbtc.storage.repository.FileDTO;
import org.gbtc.storage.repository.FileRepository;
import org.gbtc.storage.services.P2PLookupService;
import org.gbtc.storage.services.P2PPTLookupService;
import org.gbtc.storage.services.P2PPTStoreService;
import org.gbtc.storage.services.P2PRPLookupService;
import org.gbtc.storage.services.P2PRPStoreService;
import org.gbtc.storage.services.P2PStoreService;

import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.netty.common.NettyExternalNode;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.services.DHTLookupServiceAPI;
import io.ep2p.kademlia.services.DHTLookupServiceFactory;
import io.ep2p.kademlia.services.DHTStoreServiceAPI;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PStorageManager {
	
	private Scanner menuScanner;

	private int findDelay = 0;
	private int lookupDelay = 0;
	private int storeDelay = 0;
	
	private P2PNodeServer nodeServer;
	private P2PNodeSettings nodeSettings;
	
	private int type; // see P2PNodeServer.TYPE_*
	
	private String name;
	private String host;
	private int port;
	
	private String bootName;
	private String bootHost;
	private int bootPort;
	
//	private int statusCount = 0;
//	private int statusPeriod = 0;
	private int estimatePeriod = 0;
	private int entrySize = 0;
	
	private int ptNodeCount;

    
    private P2PKeyHashGenerator keyHashGenerator;
    private BigInteger nodeId;
    
    public P2PStorageManager(int type, String name, String host, int port, String bootName, String bootHost, int bootPort) {
		
    	this.type = type;
    	this.name = name;
    	this.host = host;
    	this.port = port;
    	
    	this.bootName = bootName;
    	this.bootHost = bootHost;
    	this.bootPort = bootPort;
    	
		readConfiguration();
		
		// read address from file
		//String address = P2PStorageUtil.readNodeAddress();
		
		// generate node ID
		keyHashGenerator = new P2PKeyHashGenerator(nodeSettings.getIdentifierSize()); 
    	this.nodeId = keyHashGenerator.generateHash(name.hashCode() & 0x7fffffff);
		//this.nodeId = keyHashGenerator.generateHash(address);

    	this.nodeSettings.seed = name.hashCode() & 0x7fffffff;    	
    }

	
	public void control(int mode) {
		
		if (mode == 1) {
			// manual (menu-based)
			char response = 'X';
			do {
				try {
					response = Character.toUpperCase(displayMenu());

					switch (response) {
						case '1':
							if (bootPort == 0)
								// standalone
								handleStart();
							else
								// bootstrap
								handleStartWithBootstrap();
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
		else {
			// automated
			if (bootPort == 0)
				// standalone
				handleStart();
			else
				// bootstrap
				handleStartWithBootstrap();
		}
	}
	
	
	public void readConfiguration() {
		// read configuration from file
    	File configFile = new File("config.properties");
    	 
    	try {
    	    FileReader reader = new FileReader(configFile);
    	    Properties props = new Properties();
    	    props.load(reader);
    	    
//			statusCount = Integer.parseInt(props.getProperty("STATUS_COUNT"));
//			statusPeriod = Integer.parseInt(props.getProperty("STATUS_PERIOD"));
			estimatePeriod = Integer.parseInt(props.getProperty("ESTIMATE_PERIOD"));
			entrySize = Integer.parseInt(props.getProperty("ENTRY_SIZE"));
    	 
    	    storeDelay = Integer.parseInt(props.getProperty("STORE_DELAY"));
    	    lookupDelay = Integer.parseInt(props.getProperty("LOOKUP_DELAY"));
    	    
    	    ptNodeCount = Integer.parseInt(props.getProperty("PT_NODE_COUNT"));
    	    
    	    reader.close();
    	    
			// read and load specific node parameters
    	    nodeSettings = new P2PNodeSettings();
    	    //nodeSettings.seed = name.hashCode() & 0x7fffffff;
    	} 
    	catch (Exception e) {
    	    // TO-DO
    		e.printStackTrace();
    	}
	}
	
	public char displayMenu() throws Exception {
		System.out.println("\n** P2P Storage **");
		System.out.printf("%4s\n", "1. Start Node "+getTypeDescription(type));
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
	
	@SneakyThrows
	public void handleStart() {
		
        //P2PKeyHashGenerator keyHashGenerator = new P2PKeyHashGenerator(nodeSettings.getIdentifierSize()); 
        //BigInteger nodeId = keyHashGenerator.generateHash(name.hashCode() & 0x7fffffff);

		FileRepository fileRepository = new FileRepository(nodeSettings.getDataPath(), lookupDelay, storeDelay);
        
        DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO> storeService = null;
        DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO> lookupService = null;
        
        switch (type) {
        	case P2PNodeServer.TYPE_DEFAULT:
        		storeService = new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				lookupService = new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				break;
				
        	case P2PNodeServer.TYPE_VN:
        		storeService = new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				lookupService = new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				break;

        	case P2PNodeServer.TYPE_REPUTATION:
        		storeService = new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PRPStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor(), ptNodeCount);
                    }
				};
				lookupService = new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PRPLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				break;

        	case P2PNodeServer.TYPE_PT:
        		storeService = new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PPTStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor(), ptNodeCount);
                    }
				};
				lookupService = new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PPTLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				break;
        }
        
        nodeServer = new P2PNodeServerBuilder(
                nodeId,
                new NettyConnectionInfo(host, port),
                fileRepository,
                keyHashGenerator,
                String.class, FileDTO.class, nodeSettings)
				.dhtStoreServiceFactory(storeService)
				.dhtLookupServiceFactory(lookupService)
				.build();
        
        nodeServer.setType(type);
        nodeServer.setName(name);
        nodeServer.setLookupDelay(lookupDelay);
		nodeServer.setStoreDelay(storeDelay);
		nodeServer.setEstimatePeriod(estimatePeriod);
		nodeServer.setEntrySize(entrySize);
		
    	//logger.info(String.format("Starting Node Server (Random) [Name=%s, ID=%s, Lookup Delay=%s, Store Delay=%s]...", nodeServer.getName(), nodeServer.getId(), lookupDelay, storeDelay));
        
        boolean success = nodeServer.startNode();
        
        if (success) {
        	//logger.info(String.format("Node Server (Random) [Name=%s, ID=%s] is running at %s:%s", nodeServer.getName(), nodeServer.getId(), nodeServer.getConnectionInfo().getHost(), nodeServer.getConnectionInfo().getPort()));
        }
        
	}
	
	
	@SneakyThrows
	public void handleStartWithBootstrap() {
		
		//P2PKeyHashGenerator keyHashGenerator = new P2PKeyHashGenerator(nodeSettings.getIdentifierSize()); 
        //BigInteger nodeId = keyHashGenerator.generateHash(name.hashCode() & 0x7fffffff);        

		FileRepository fileRepository = new FileRepository(nodeSettings.getDataPath(), lookupDelay, storeDelay);
        
        DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO> storeService = null;
        DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO> lookupService = null;
        
        switch (type) {
        	case P2PNodeServer.TYPE_DEFAULT:
        		storeService = new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				lookupService = new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				break;
				
        	case P2PNodeServer.TYPE_VN:
        		storeService = new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				lookupService = new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				break;

        	case P2PNodeServer.TYPE_REPUTATION:
        		storeService = new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PRPStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor(), ptNodeCount);
                    }
				};
				lookupService = new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PRPLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				break;

        	case P2PNodeServer.TYPE_PT:
        		storeService = new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PPTStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor(), ptNodeCount);
                    }
				};
				lookupService = new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
                    @Override
                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
                    	return new P2PPTLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
                    }
				};
				break;
        }
        
        nodeServer = new P2PNodeServerBuilder(
                nodeId,
                new NettyConnectionInfo(host, port),
                fileRepository,
                keyHashGenerator,
                String.class, FileDTO.class, nodeSettings)
				.dhtStoreServiceFactory(storeService)
				.dhtLookupServiceFactory(lookupService)
				.build();
        
		nodeServer.setType(type);
        nodeServer.setName(name);
        nodeServer.setLookupDelay(lookupDelay);
		nodeServer.setStoreDelay(storeDelay);
		nodeServer.setEstimatePeriod(estimatePeriod);
		nodeServer.setEntrySize(entrySize);
    	
        // bootstrap
		nodeSettings.bootHost = bootHost;
        nodeSettings.bootId = keyHashGenerator.generateHash(bootName.hashCode() & 0x7fffffff);
        nodeSettings.bootPort = bootPort;
        
    	NettyExternalNode bootstrapNode = new NettyExternalNode(
    			new NettyConnectionInfo(nodeSettings.getBootHost(), nodeSettings.getBootPort()), nodeSettings.getBootId());

		//logger.info(String.format("Starting Node Server (Random) [Name=%s, ID=%s, Lookup Delay=%s, Store Delay=%s]...", name, nodeServer.getId(), lookupDelay, storeDelay));

        boolean success = nodeServer.startNode(bootstrapNode);
        
        if (success) {
        	//logger.info(String.format("Node Server (Random) [Name=%s, ID=%s] is running at %s:%s", nodeServer.getName(), nodeServer.getId(), nodeServer.getConnectionInfo().getHost(), nodeServer.getConnectionInfo().getPort()));
        }
	}
	
	@SneakyThrows
	public void handleStop() {
		if (nodeServer != null) {
			nodeServer.stopNode();
			Thread.sleep(1000);
		}
	}
	
	private String getTypeDescription(int type) {
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
			case P2PNodeServer.TYPE_VN:
				typeDescription = "Virtual Node";
				break;
			default:
		   		log.error("Invalid Type");
		}
		
		return typeDescription;
	}

}
