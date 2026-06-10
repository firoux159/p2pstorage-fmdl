package org.gbtc.storage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.gbtc.storage.node.P2PNode;
import org.gbtc.storage.repository.FileDTO;
import org.gbtc.storage.repository.FileRepository;
import org.gbtc.storage.services.P2PLookupService;
import org.gbtc.storage.services.P2PStoreService;

import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.netty.NettyKademliaDHTNode;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.netty.common.NettyExternalNode;
import io.ep2p.kademlia.netty.server.KademliaNodeServer;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.services.DHTLookupServiceAPI;
import io.ep2p.kademlia.services.DHTLookupServiceFactory;
import io.ep2p.kademlia.services.DHTStoreServiceAPI;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class P2PNodeServer extends NettyKademliaDHTNode<String, FileDTO> {
	
	private static final long serialVersionUID = 1L;
	
	public static final int TYPE_MONITOR = 0;
	public static final int TYPE_CLIENT = 1;
	public static final int TYPE_DEFAULT = 2;
	public static final int TYPE_REPUTATION = 3;
	public static final int TYPE_PT = 4;
	public static final int TYPE_VN = 5;
	
    private P2PNodeSettings nodeSettings;
    
    @Setter
    @Getter
    private String name;
    
    @Setter
	private long startTimestamp;
    @Setter
    private long previousPeriodicTimestamp;
	@Setter
	private int lookupDelay = 0;
	@Setter
	private int storeDelay = 0;

    private int estimatePeriod = 0;
    private int entrySize = 0;
    
    private Timer arrivalTimer;
    
    @Setter
    private int scheduledMinute;

    UniformRandomGenerator uniformRandomGenerator;
    
    BufferedWriter workloadWriter;
    
    @Setter
    @Getter
    private int type = 0; // 0=Random, 1=Reputation, 2=PT
    
    // parameters related to virtual nodes
    private ExecutorService executorService;
    private ArrayList<P2PVirtualNodeServer> virtualNodes;
    
    
	public P2PNodeServer(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNode, KademliaNodeServer<String, FileDTO> kademliaNodeServer) {
		super(kademliaNode, kademliaNodeServer);
		
		nodeSettings = (P2PNodeSettings)this.getNodeSettings();
		uniformRandomGenerator = new UniformRandomGenerator(nodeSettings.getIdentifierSize(), nodeSettings.getSeed());
		
//		if (type == P2PNodeServer.TYPE_VN) {
//			executorService = Executors.newFixedThreadPool(nodeSettings.getVirtualNodeCount());
//			virtualNodes = new ArrayList<P2PVirtualNodeServer>(nodeSettings.getVirtualNodeCount());
//		}
	}
	
    @SneakyThrows
    public boolean startNode() {

    	boolean success = false;
    	
		log.info(String.format("Starting Node Server %s [Name=%s, ID=%s, Lookup Delay=%s, Store Delay=%s]...", this.getTypeDescription(), this.getName(), this.getId(), lookupDelay, storeDelay));
    	
    	this.start();
    	
    	// longer sleep to allow more time for actual node to populate its routing table
    	Thread.sleep(5000);
        
        if (this.isRunning()) {
        	
        	log.info(String.format("Node Server %s [Name=%s, ID=%s] is running at %s:%s", this.getTypeDescription(), this.getName(), this.getId(), this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()));

        	if (this.type == P2PNodeServer.TYPE_VN) {
            	// setup and run the virtual nodes
    			executorService = Executors.newFixedThreadPool(nodeSettings.getVirtualNodeCount());
    			virtualNodes = new ArrayList<P2PVirtualNodeServer>(nodeSettings.getVirtualNodeCount());
    			
		        // virtual node bootstrapped to the physical/actual node
		    	NettyExternalNode bootstrapNode = new NettyExternalNode(
		    			new NettyConnectionInfo(this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()), this.getId());

        		startVirtualNodes(bootstrapNode);  		
        	}

        	success = true;
        	
        	arrivalTimer = new Timer();
        	arrivalTimer.schedule(new ArrivalRateUpdate(), this.estimatePeriod, this.estimatePeriod);
        }
        else {
        	success = false;
        }
        
        return success;
    }
    
    @SneakyThrows
    public boolean startNode(Node<BigInteger, NettyConnectionInfo> bootstrapNode) {

        boolean success = false;
        
        log.info(String.format("Starting Node Server with Bootstrap %s [Name=%s, ID=%s, Lookup Delay=%s, Store Delay=%s]...", this.getTypeDescription(), this.getName(), this.getId(), lookupDelay, storeDelay));
        
        boolean bootstrapped = this.start(bootstrapNode).get(20, TimeUnit.SECONDS);
        
        log.info(String.format("Node Server %s [Name=%s, ID=%s] is running at %s:%s", this.getTypeDescription(), this.getName(), this.getId(), this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()));
        
        if (bootstrapped) {
        	log.info(String.format("Node Server %s [Name=%s, ID=%s] bootstrapped with [ID=%s, Host=%s, Port=%s]",
        			this.getTypeDescription(), this.getName(), this.getId(), bootstrapNode.getId(), 
        			bootstrapNode.getConnectionInfo().getHost(), bootstrapNode.getConnectionInfo().getPort()));
        	
        	success = true;
        	
        	// longer sleep to allow more time for actual node to populate its routing table
        	Thread.sleep(5000);
        }
        else { 
        	// bootstrap failed
        	stopNow();
        	return false;
        }

        if (this.isRunning()) {

        	if (this.type == P2PNodeServer.TYPE_VN) {
            	// setup and run the virtual nodes
    			executorService = Executors.newFixedThreadPool(nodeSettings.getVirtualNodeCount());
    			virtualNodes = new ArrayList<P2PVirtualNodeServer>(nodeSettings.getVirtualNodeCount());
    			
    			// Virtual node bootstrapped to the same node that of actual node
    			startVirtualNodes(bootstrapNode);  		
        	}

        	success = true;
        	
        	arrivalTimer = new Timer();
        	//arrivalTimer.schedule(new ArrivalRateUpdate(), nodeSettings.getEstimatePeriod(), nodeSettings.getEstimatePeriod());
        	arrivalTimer.schedule(new ArrivalRateUpdate(), this.estimatePeriod, this.estimatePeriod);

        }
        else {
        	success = false;
        }
        
        return success;
    }
    
    
    private void startVirtualNodes(Node<BigInteger, NettyConnectionInfo> bootstrapNode) {
    	// setup and run the virtual nodes associated to this Node
		for (int i=1; i<=nodeSettings.getVirtualNodeCount(); i++) {
			try {
				// clone and customize nodeSettings
				P2PNodeSettings virtualNodeSettings = (P2PNodeSettings) nodeSettings.clone();

				String virtualNodeName = this.getName() + "_vn"+i;
				
				// host is the same with actual node, port is incremental of the actual node
				virtualNodeSettings.name = virtualNodeName;
				virtualNodeSettings.host = this.getConnectionInfo().getHost();
				virtualNodeSettings.port = this.getConnectionInfo().getPort() + i;
				
		        P2PKeyHashGenerator keyHashGenerator = new P2PKeyHashGenerator(virtualNodeSettings.getIdentifierSize());
		        
		        virtualNodeSettings.dataPath = virtualNodeSettings.dataPath + File.separator + "vn"+i;
		        
				Path dataPath = Paths.get(virtualNodeSettings.getDataPath());
				Files.createDirectories(dataPath);
		        FileRepository fileRepository = new FileRepository(dataPath.toString(), lookupDelay, storeDelay);
				
		        BigInteger nodeId = keyHashGenerator.generateHash(virtualNodeName.hashCode() & 0x7fffffff);
				
		        P2PVirtualNodeServer virtualNodeServer = new P2PVirtualNodeServerBuilder(
		                nodeId,
		                new NettyConnectionInfo(this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort() + i),
		                fileRepository,
		                keyHashGenerator,
		                String.class, FileDTO.class, virtualNodeSettings)
						.dhtStoreServiceFactory(new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
							@Override
							public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
		                    	return new P2PStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
		                    }
						})
						.dhtLookupServiceFactory(new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
							@Override
							public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
		                    	return new P2PLookupService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
		                    }
						})
						.build();
		        
		        virtualNodeServer.setType(P2PNodeServer.TYPE_DEFAULT);
		        virtualNodeServer.setName(virtualNodeName);
		        virtualNodeServer.setLookupDelay(lookupDelay);
				virtualNodeServer.setStoreDelay(storeDelay);
				
				// pass the reference of parent node to virtual node
				// needed for statistic collection
				((P2PNode)virtualNodeServer.getKademliaNode()).setActualNode((P2PNode)this.getKademliaNode());

		        // virtual node bootstrapped to the physical/actual node
//		    	NettyExternalNode bootstrapNode = new NettyExternalNode(
//		    			new NettyConnectionInfo(this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()), this.getId());
		    	
		    	virtualNodeServer.setBootstrapNode(bootstrapNode);
		    	virtualNodes.add(virtualNodeServer);

		    	executorService.execute(virtualNodeServer);
			}
			catch (Exception e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}      	
    }
    
    @SneakyThrows
    public void stopNode() {
		try {
			log.info(String.format("Stopping Node Server %s [Name=%s, ID=%s]...", this.getTypeDescription(), this.getName(), this.getId()));			
			
			arrivalTimer.cancel();

			if (this.getType() == P2PNodeServer.TYPE_VN) {
				// Random server, stop all the Virtual Nodes attached this node
				for (P2PVirtualNodeServer virtualNode : virtualNodes) {
					try {
						if (virtualNode.isRunning())
							virtualNode.stopNode();
					}
					catch (Exception e) {
						e.printStackTrace();
						log.error(String.format("Virtual Node Server [Name=%s, ID=%s] failed to stop!", virtualNode.getName(), virtualNode.getId()));
					}
				}

				executorService.shutdownNow();
			}
			
			stop();
			
			log.info(String.format("Node Server %s [Name=%s, ID=%s] has stopped at %s:%s", this.getTypeDescription(), this.getName(), this.getId(), this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()));
			
		}
		catch (Exception e) {
			log.error(String.format("Node Server %s [Name=%s, ID=%s] failed to stop!", this.getTypeDescription(), this.getName(), this.getId()));
		}
	}
    

    @SneakyThrows
    public boolean startClientNode() {

    	boolean success = false;
    	log.info(String.format("Starting Node Server %s [Name=%s, ID=%s]...", this.getTypeDescription(), this.getName(), this.getId()));

    	this.start();
        
    	Thread.sleep(5000);
        
        if (this.isRunning()) {
        	success = true;
        	log.info(String.format("Node Server %s [Name=%s, ID=%s] is running at %s:%s", this.getTypeDescription(), this.getName(), this.getId(), this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()));
        }
        else {
        	log.info(String.format("Node Server %s [Name=%s, ID=%s] failed to start!", this.getTypeDescription(), this.getName(), this.getId()));
        	success = false;
        }
        
        return success;
    }    

    @SneakyThrows
    public void stopClientNode() {
		try {
			log.info(String.format("Stopping Node Server %s [Name=%s, ID=%s]...", this.getTypeDescription(), this.getName(), this.getId()));			
			
			stop();
			
			log.info(String.format("Node Server %s [Name=%s, ID=%s] has stopped at %s:%s", this.getTypeDescription(), this.getName(), this.getId(), this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()));
		}
		catch (Exception e) {
			log.error(String.format("Node Server %s [Name=%s, ID=%s] failed to stop!", this.getTypeDescription(), this.getName(), this.getId()));
		}
	}    

	
	public BigInteger clientStore(Node<BigInteger, NettyConnectionInfo> receiver, String key, FileDTO value, int timeout) {

		BigInteger storerNodeId = null;
		
		try {
			
			log.info(String.format("Store data: %s(%s), node: %s, timeout: %s", value.getName(), key, receiver.getId(), timeout));
			
			StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = ((P2PNode) this.getKademliaNode()).clientStore(receiver, key, value).get(timeout, TimeUnit.MILLISECONDS);
			if (storeAnswer.getNode() != null)
				storerNodeId = storeAnswer.getNode().getId();
		}
		catch (TimeoutException te) {
			log.info(String.format("Store data: %s(%s), node: %s, result: %s", value.getName(), key, storerNodeId, "TIMEOUT"));
		}
		catch (Exception e) {
	    	e.printStackTrace();
			log.error(e.getMessage());
		}
		
        return storerNodeId;
	}

	
	public FileDTO clientLookup(Node<BigInteger, NettyConnectionInfo> receiver, String key, int timeout) {

		// key consists of storer node id and the data key (storerId@key) 
		FileDTO value = null;
		
		try {
            LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer = ((P2PNode) this.getKademliaNode()).clientLookup(receiver, key).get(timeout, TimeUnit.MILLISECONDS);
			// note: value can be null if the message passed to other node
            value = lookupAnswer.getValue();
		}
		catch (TimeoutException te) {
			log.info(String.format("Lookup data: %s from node: %s, result: %s", key, receiver.getId(), "TIMEOUT"));
		}
		catch (Exception e) {
	    	e.printStackTrace();
			log.error(e.getMessage());
		}
		
        return value;
	}
	
	
	public ArrayList<File> splitBySize(File file, int maxSize) {
		ArrayList<File> list = new ArrayList<>();
	    
	    try {
	    	InputStream in = Files.newInputStream(file.toPath());
	    	final byte[] buffer = new byte[maxSize];
	        int dataRead = in.read(buffer);
	        
	        int sequence = 1;
	        
	        while (dataRead > -1) {
	            File fileChunk = stageFile(file.getName(), sequence, buffer, dataRead);
	            list.add(fileChunk);
	            dataRead = in.read(buffer);
	            sequence++;
	        }
	    }
	    catch (Exception e) {
	    	e.printStackTrace();
	    	log.error(e.getMessage());
	    }
	    
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
	
	
	protected class ArrivalRateUpdate extends TimerTask {
		
		// this task is to periodically calculate the arrival rate (lambda) in the node every <estimatePeriod> 
		// (currently set every 5s) then put in the list. 
		// will only keep latest <statusCount> entries
		
		@Override
		public void run() {
			P2PNode node =  (P2PNode) getKademliaNode();
			node.updatePeriodicArrivalRate();
		}
	}

	
//	protected class WorkloadStatus extends TimerTask {
//		
//		// this task is to periodically calculate the workload in the node every <estimatePeriod> 
//		// (currently set every 5s)
//		// then put in the list.will only keep latest <statusCount> entries
//		
//		@Override
//		public void run() {
//			P2PNode node =  (P2PNode) getKademliaNode();
//			//logWorkload.info(node.getWorkloadStatus());
//			
//			try {
//				workloadWriter.write(node.getWorkloadStatus());
//				workloadWriter.newLine();
//				workloadWriter.flush();
//			}
//			catch (Exception e) {
//				e.printStackTrace();
//				log.error(e.getMessage());
//			}
//		}
//	}	
	
	private String getTypeDescription() {
		String typeDescription = "Unknown";
		
		switch (type) {
			case TYPE_MONITOR:
				typeDescription = "Monitor";
				break;		
			case TYPE_CLIENT:
				typeDescription = "Client";
				break;		
			case TYPE_DEFAULT:
				typeDescription = "Default";
				break;
			case TYPE_REPUTATION:
				typeDescription = "Reputation";
				break;
			case TYPE_PT:
				typeDescription = "PT";
				break;
			case TYPE_VN:
				typeDescription = "Virtual Node";
				break;
			default:
		   		log.error("Invalid Type");
		}
		
		return typeDescription;
	}
	
	public void setEstimatePeriod(int estimatePeriod) {
		this.estimatePeriod = estimatePeriod;
		((P2PNode) this.getKademliaNode()).setEstimatePeriod(estimatePeriod);
	}
	
	public void setEntrySize(int entrySize) {
		this.entrySize = entrySize;
		((P2PNode) this.getKademliaNode()).setEntrySize(entrySize);
	}

}
