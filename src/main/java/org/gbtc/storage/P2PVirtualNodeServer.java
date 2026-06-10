package org.gbtc.storage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.gbtc.storage.node.P2PNode;
import org.gbtc.storage.repository.FileDTO;

import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.netty.NettyKademliaDHTNode;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.netty.server.KademliaNodeServer;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class P2PVirtualNodeServer extends NettyKademliaDHTNode<String, FileDTO> implements Runnable {
	
	private static final long serialVersionUID = 1L;
	
    private P2PNodeSettings nodeSettings;
    
    @Setter
    @Getter
    private String name;
    
    @Setter
    private Node<BigInteger, NettyConnectionInfo> bootstrapNode;
    
    @Setter
	private long startTimestamp;
    @Setter
    private long previousPeriodicTimestamp;
    
    @Setter
    private int entrySize = 100;
    
	@Setter
	private int estimatePeriod = 5000;
	
	@Setter
	private int lookupDelay = 0;
	@Setter
	private int storeDelay = 0;
    
    @Setter
    private int statusCount = 0;
    
    @Setter
    private int scheduledMinute;

    UniformRandomGenerator uniformRandomGenerator;
    
    BufferedWriter workloadWriter;
    
    @Setter
    @Getter
    private int type = 0; // 0=Random, 1=Reputation, 2=PT
    
	public P2PVirtualNodeServer(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNode, KademliaNodeServer<String, FileDTO> kademliaNodeServer) {
		super(kademliaNode, kademliaNodeServer);
		
		nodeSettings = (P2PNodeSettings)this.getNodeSettings();
		uniformRandomGenerator = new UniformRandomGenerator(nodeSettings.getIdentifierSize(), nodeSettings.getSeed());
	}
	
	public void run() {
		startNode(bootstrapNode);
	}
	
    @SneakyThrows
    public boolean startNode() {

    	boolean success = false;
    	
    	this.start();
        
    	Thread.sleep(5000);
        
        if (this.isRunning()) {
        	success = true;
        }
        
        return success;
    }
    
    @SneakyThrows
    public boolean startNode(Node<BigInteger, NettyConnectionInfo> bootstrapNode) {

        boolean success = false;
        
    	log.info(String.format("Starting Virtual Node Server [Name=%s, ID=%s, Lookup Delay=%s, Store Delay=%s]...", this.getName(), this.getId(), lookupDelay, storeDelay));
        
        boolean bootstrapped = this.start(bootstrapNode).get(20, TimeUnit.SECONDS);
        
    	log.info(String.format("Virtual Node Server [Name=%s, ID=%s] is running at %s:%s", name, this.getId(), this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()));

        if (bootstrapped) {
        	log.info(String.format("Virtual Node Server [Name=%s, ID=%s] bootstrapped with [ID=%s, Host=%s, Port=%s]",
        			nodeSettings.getName(), this.getId(), bootstrapNode.getId(), 
        			bootstrapNode.getConnectionInfo().getHost(), bootstrapNode.getConnectionInfo().getPort()));
        	
        	// allow sometime for network binding 
        	Thread.sleep(5000);
        }
        else 
        	stopNow();

        if (this.isRunning() && bootstrapped) {
        	success = true;
        }
        
        return success;
    }
    
    @SneakyThrows
    public void stopNode() {
		try {
			log.info(String.format("Stopping Virtual Node Server [Name=%s, ID=%s]...", name, this.getId()));
			
			stop();
			
			log.info(String.format("Virtual Node Server [Name=%s, ID=%s] has stopped at %s:%s", name, this.getId(), this.getConnectionInfo().getHost(), this.getConnectionInfo().getPort()));
			
		}
		catch (Exception e) {
			log.info(String.format("Virtual Node Server [Name=%s, ID=%s] failed to stop!", name, this.getId()));
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

}
