package org.gbtc.storage.services;

import java.io.File;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;

import org.gbtc.storage.P2PKeyHashGenerator;
import org.gbtc.storage.P2PNodeSettings;
import org.gbtc.storage.UniformRandomGenerator;
import org.gbtc.storage.client.NodeServer;
import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.protocol.message.DHTStoreExternalKademliaMessage;
import org.gbtc.storage.protocol.message.NodeStatusMessage;
import org.gbtc.storage.protocol.message.NodeStatusResultMessage;
import org.gbtc.storage.protocol.message.NodeWorkloadMessage;
import org.gbtc.storage.protocol.message.NodeWorkloadResultMessage;
import org.gbtc.storage.repository.FileDTO;
import io.ep2p.kademlia.exception.HandlerNotFoundException;
import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.protocol.message.DHTStoreKademliaMessage;
import io.ep2p.kademlia.protocol.message.DHTStoreResultKademliaMessage;
import io.ep2p.kademlia.protocol.message.EmptyKademliaMessage;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.protocol.message.PingKademliaMessage;
import io.ep2p.kademlia.services.PushingDHTStoreService;
import io.ep2p.kademlia.util.DateUtil;
import io.ep2p.kademlia.util.NodeUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PRPStoreService extends PushingDHTStoreService<BigInteger, NettyConnectionInfo, String, FileDTO> {
	
    private UniformRandomGenerator uniformRandomGenerator;
    private Random random;
    private int randomCount;
    private ArrayList<NodeServer> nodes = new ArrayList<NodeServer>();
	
	public P2PRPStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode, ExecutorService executorService) {
    	super(dhtKademliaNode, executorService);
    	
    	P2PNodeSettings nodeSettings = (P2PNodeSettings) this.dhtKademliaNode.getNodeSettings();
    	
    	uniformRandomGenerator = new UniformRandomGenerator(
    			nodeSettings.getIdentifierSize(), nodeSettings.getSeed());
    	
    	random = new Random(nodeSettings.getSeed());
    	
    	loadNodes();
    }
	
	public P2PRPStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode, ExecutorService executorService, int randomCount) {
    	this(dhtKademliaNode, executorService);
    	this.randomCount = randomCount;
    	
    	log.debug("[P2PRPStoreService] random count="+randomCount);
    }	
    
    @Override
    @SuppressWarnings("unchecked")
    public KademliaMessage<BigInteger, NettyConnectionInfo, ?> handle(
    		KademliaNodeAPI kademliaNode, 
    		KademliaMessage message) {
        
    	log.debug("StoreService: P2PRPStoreService");

    	if (message.isAlive()){
    		// do not update routing table if the request is from client node
    		if (!P2PMessageType.CLIENT_STORE_REQ.equals(message.getType())) {
    			this.dhtKademliaNode.getRoutingTable().forceUpdate(message.getNode());
    			log.debug("[handle] Routing table of node "+this.dhtKademliaNode.getId()+" updated with node="+message.getNode().getId());
    		}
        }

        switch (message.getType()) {
        
	    	case P2PMessageType.CLIENT_STORE_REQ:
	    		if (!(message instanceof DHTStoreKademliaMessage))
	    			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreKademliaMessage");
	    		return handleStoreRequest((DHTStoreKademliaMessage) message);        		
        
        	case P2PMessageType.DHT_STORE:
        		if (!(message instanceof DHTStoreKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreKademliaMessage");
        		return handleStoreRequest((DHTStoreKademliaMessage) message);

        	case P2PMessageType.DHT_STORE_RESULT:
        		if (!(message instanceof DHTStoreResultKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreResultKademliaMessage");
        		return handleStoreResult((DHTStoreResultKademliaMessage) message);

        	case P2PMessageType.DHT_EXTERNAL_STORE:
        		if (!(message instanceof DHTStoreKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreKademliaMessage");
        		return this.handleStoreExternalRequest((DHTStoreKademliaMessage) message);
        		
        	case P2PMessageType.DHT_EXTERNAL_STORE_RESULT:
        		if (!(message instanceof DHTStoreResultKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreResultKademliaMessage");
        		return this.handleStoreResult((DHTStoreResultKademliaMessage) message);
        		
        	case P2PMessageType.DHT_EXTERNAL_NEXT_STORE:
        		if (!(message instanceof DHTStoreKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreKademliaMessage");
        		return this.handleStoreExternalNextRequest((DHTStoreKademliaMessage) message);
        		
        	case P2PMessageType.DHT_EXTERNAL_NEXT_STORE_RESULT:
        		if (!(message instanceof DHTStoreResultKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreResultKademliaMessage");
        		return this.handleStoreResult((DHTStoreResultKademliaMessage) message);

        		
        	default:
        		throw new IllegalArgumentException("message param is not supported");
        }	
    }
    
    
    @Override
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleStoreResult(DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String> message) {
        DHTStoreResultKademliaMessage.DHTStoreResult<String> data = message.getData();
        this.finalizeStoreResult(data.getKey(), data.getResult(), message.getNode());
        return new EmptyKademliaMessage<>();
    }

    
    @Override
    public Future<StoreAnswer<BigInteger, NettyConnectionInfo, String>> store(String key, FileDTO value) {
    	
    	log.debug("[store] key = "+key);
    	
    	CompletableFuture<StoreAnswer<BigInteger, NettyConnectionInfo, String>> completableFuture = new CompletableFuture<>();

//    	long start = System.currentTimeMillis();
//        this.dhtKademliaNode.initiateStore();

    	storeFutureMap.computeIfAbsent(key, k -> {
            completableFuture.whenComplete((a, t) -> storeFutureMap.remove(key));
            
            StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleStore(this.dhtKademliaNode, this.dhtKademliaNode, key, value);

//        	long end = System.currentTimeMillis();
//            this.dhtKademliaNode.completeStore(end-start);
            
            if (storeAnswer.getResult().equals(StoreAnswer.Result.STORED) || storeAnswer.getResult().equals(StoreAnswer.Result.FAILED)){
                completableFuture.complete(storeAnswer);
                return null;
            }
            return completableFuture;
        });
        return completableFuture;
    }
    
    @Override
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleStoreRequest(
    		DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message) {

    	log.debug("[handleStoreRequest 1]");
    	
		DHTStoreKademliaMessage.DHTData<BigInteger, NettyConnectionInfo, String, FileDTO> data = message.getData();
		
		log.debug("[handleStoreRequest 2] node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey()+", value="+data.getValue().getName());
    	
//    	long start = System.currentTimeMillis();
//        this.dhtKademliaNode.initiateStore();

		this.handlerExecutorService.submit(() -> {
            StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleStore(message.getNode(), data.getRequester(), data.getKey(), data.getValue());
            if (storeAnswer.getResult().equals(StoreAnswer.Result.STORED)) {
                this.dhtKademliaNode.getMessageSender().sendAsyncMessage(
                        this.dhtKademliaNode,
                        data.getRequester(),
                        new DHTStoreResultKademliaMessage<>(
                                new DHTStoreResultKademliaMessage.DHTStoreResult<>(data.getKey(), StoreAnswer.Result.STORED)
                        )
                );
            }

//            long end = System.currentTimeMillis();
//            this.dhtKademliaNode.completeStore(end-start);

        });
		
    	log.debug("[handleStoreRequest 3]");

        return new EmptyKademliaMessage<>();
    }
    
    @Override
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> handleStore(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		String key, FileDTO value){
    	
    	log.debug("[handleStore 1] requester="+requester.getId()+", key="+key+", value="+value.getName()+", ext="+value.getExternalNode());
    	
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = null;
    	
    	// generate 2 random ID (uniform distribution) then look for the node matching or closest to these ID
    	// note: the resulting ID already bounded to the network size
    	
        // temporary: just look into local routing table
        // TO-DO: Implement FIND_NODE
//        FindNodeAnswer<BigInteger, NettyConnectionInfo> findNodeAnswer1 = this.dhtKademliaNode.getRoutingTable().findClosest(uniformRandomGenerator.generate());
//        FindNodeAnswer<BigInteger, NettyConnectionInfo> findNodeAnswer2 = this.dhtKademliaNode.getRoutingTable().findClosest(uniformRandomGenerator.generate());
//        
//        // get the closest one (note: the list is in ascending order)
//        ExternalNode<BigInteger, NettyConnectionInfo> externalNode1 = (ExternalNode<BigInteger, NettyConnectionInfo>)findNodeAnswer1.getNodes().get(0);
//        ExternalNode<BigInteger, NettyConnectionInfo> externalNode2 = (ExternalNode<BigInteger, NettyConnectionInfo>)findNodeAnswer2.getNodes().get(0);
        
        /*
        // get the ping latency of each node
        long start = System.currentTimeMillis(); 
        KademliaMessage<BigInteger, NettyConnectionInfo, ?> pingAnswer = 
        		this.dhtKademliaNode.getMessageSender().sendMessage(
        				this.dhtKademliaNode, externalNode1, new PingKademliaMessage<>());
        if (pingAnswer.isAlive()) {
        	log.debug("[handleStore] pingAnswer="+pingAnswer.toString());
        }
        else {
        	log.error("");
        	// TO-DO: handle if the node is not running 
        }
    	long node1latency = System.currentTimeMillis() - start;
    	log.debug("handleStore: node1=" + externalNode1.getId() +", latency="+ node1latency);
    	
        start = System.currentTimeMillis(); 
        pingAnswer = 
        		this.dhtKademliaNode.getMessageSender().sendMessage(
        				this.dhtKademliaNode, externalNode2, new PingKademliaMessage<>());
        if (pingAnswer.isAlive()) {
        	log.debug("[handleStore] pingAnswer="+pingAnswer.toString());
        }
        else {
        	log.error("");
        	// TO-DO: handle if the node is not running 
        }
    	long node2latency = System.currentTimeMillis() - start;    
    	log.debug("handleStore: node1=" + externalNode2.getId() +", latency="+ node2latency);

        // choose the one with smallest workload
        if (node1latency < node2latency) {
        	// store at node 1
        	// at this stage, caller = requester = current node
        	log.debug("[handleStore] selected node="+externalNode1.getId());
        	
        	// check if the external node is the current node
        	if (externalNode1.getId().equals(this.dhtKademliaNode.getId())) {
        		// external node = current node, no need to send message
        		storeAnswer = handleStoreExternal(this.dhtKademliaNode, requester, key, value);
        		return storeAnswer;
        	}
        	else {
        		// send message to the selected node, it will return status PASSED
        		storeAnswer = storeDataToExternal(requester, externalNode1, key, value);
        	}
        }
        else {
        	// store at node 2
        	log.debug("[handleStore] selected node="+externalNode2.getId());
        	// check if the external node is the current node
        	if (externalNode2.getId().equals(this.dhtKademliaNode.getId())) {
        		// external node = current node, no need to send message
        		storeAnswer = handleStoreExternal(this.dhtKademliaNode, requester, key, value);
        		return storeAnswer;
        	}
        	else {
        		// will return status PASSED
        		storeAnswer = storeDataToExternal(requester, externalNode2, key, value);
        	}
        }
        */
    	
    	HashMap<NodeServer, Double> randomNodes = new HashMap<>(randomCount);
    	
    	int d = 0;
    	int size = nodes.size();
    	while (d < randomCount) {
    		int randomIndex = random.nextInt(size);
    		NodeServer node = nodes.get(randomIndex);
    		if (!randomNodes.containsKey(node)) {
    			
    			// get throughput of each node
    	        NodeWorkloadMessage<BigInteger, NettyConnectionInfo> workloadMessage = 
    	        		new NodeWorkloadMessage<>(new NodeWorkloadMessage.NodeWorkload<>(requester));
    	        workloadMessage.setType(P2PMessageType.NODE_THROUGHPUT);

    	    	log.debug("[handleStore] get throughput for node ="+node.getId());
    	    	
    	    	KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> workloadResponse = 
    	        		this.dhtKademliaNode.getMessageSender().sendMessage(
    	        				this.dhtKademliaNode,
    	        				node, 
    	        				workloadMessage);

    	        log.debug("[handleStore] node="+node.getId()+", statusResponse="+workloadResponse.toString());
    	        
    	        NodeWorkloadResultMessage.NodeWorkloadResult<Double> workloadData = (NodeWorkloadResultMessage.NodeWorkloadResult<Double>) workloadResponse.getData();
    			randomNodes.put(node, workloadData.getWorkload());
    			
    			d++;
    		}
    	}
    	
    	// sort in descending order
    	HashMap<NodeServer, Double> sortedRandomNodes = sortByValue(randomNodes, 2);
    	
    	// select the first entry from the list (node with highest throughput)
    	Map.Entry<NodeServer, Double> firstNode = sortedRandomNodes.entrySet()
    			  .stream()
    			  .findFirst()
    			  .get();
    	
    	NodeServer selectedNode = firstNode.getKey();
    	
    	log.debug("[handleStore] selected node="+selectedNode.getId()+","+selectedNode.getConnectionInfo().getHost()+","+selectedNode.getConnectionInfo().getPort()+", workload="+firstNode.getValue());
    	
    	// store at selected node (actual node)
    	// at this stage, caller = requester = current node
    	// check if the external node is the current node
    	if (selectedNode.getId().equals(this.dhtKademliaNode.getId())) {
    		// external node = current node, no need to send message
    		storeAnswer = handleStoreExternal(this.dhtKademliaNode, requester, key, value);
    		return storeAnswer;
    	}
    	else {
    		// send message to the selected node, it will return status PASSED
    		storeAnswer = storeDataToExternal(requester, selectedNode, key, value);
    	}
    	
        return storeAnswer;
    }
    
    
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> handleStoreMapping(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		String key, FileDTO value){
        
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer;
        BigInteger hash = this.dhtKademliaNode.getKeyHashGenerator().generateHash(key);
        
        log.debug("[P2PPTStoreService.handleStoreMapping] Caller="+caller.getId()+", Requester="+requester.getId());
        
        log.debug(String.format("[P2PPTStoreService.handleStoreMapping] key=%s, value name=%s, value ext=%s", key, value.getName(), value.getExternalNode()));
        
        long start = 0, end = 0;

        // If some other node is calling the store, and that other node is not this node,
        // But the origin request is by this node, then persist it
        // The closest node we know to the key knows us as the closest know to the key and not themselves (?!?)
        // Useful only in case of nodeSettings.isEnabledFirstStoreRequestForcePass()
        if (!caller.getId().equals(this.dhtKademliaNode.getId()) && requester.getId().equals(this.dhtKademliaNode.getId())){
            //return doStore(key, value);

        	storeAnswer = doStore(key, value);
        	
        	log.debug("[P2PPTStoreService.handleStoreMapping] 1");
        	
        	return storeAnswer;
        	// end added
        }

        // If current node should persist the data, do it immediately
        // For smaller networks this helps to avoid the process of finding alive close nodes to pass data to
        
        // consider this as FIND request
//        start = System.currentTimeMillis();
//        this.dhtKademliaNode.initiateFind();
        
        FindNodeAnswer<BigInteger, NettyConnectionInfo> findNodeAnswer = this.dhtKademliaNode.getRoutingTable().findClosest(hash);
        
//        end = System.currentTimeMillis();
//        this.dhtKademliaNode.completeFind(end-start);
        
        
        storeAnswer = storeDataToClosestNode(caller, requester, findNodeAnswer.getNodes(), key, value);
        if(storeAnswer.getResult().equals(StoreAnswer.Result.FAILED)){
        	//storeAnswer = doStore(key, value);
        	
            storeAnswer = doStore(key, value);
            
            log.debug("[P2PPTStoreService.handleStoreMapping] 3");
        }
        
        log.debug("[P2PPTStoreService.handleStoreMapping] 4");

        return storeAnswer;
    }
    
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> storeDataToExternal (
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		Node<BigInteger, NettyConnectionInfo> external, 
    		String key, FileDTO value) {
        
    	log.debug("[storeDataToExternal 1]");
    	
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer;
    	long start = 0, end = 0;
    	
    	DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message = new DHTStoreKademliaMessage<>(new DHTStoreKademliaMessage.DHTData<>(requester, key, value));
    	message.setType(P2PMessageType.DHT_EXTERNAL_STORE);
    	
    	KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> response = this.dhtKademliaNode.getMessageSender().sendMessage(
                this.dhtKademliaNode,
                external,
                message
        );
        if (response.isAlive()){
        	storeAnswer = getNewStoreAnswer(key, StoreAnswer.Result.PASSED, requester);
            
            return storeAnswer;
        }
        
    	end = System.currentTimeMillis();
    	this.dhtKademliaNode.completeStore(end-start);
    	
    	log.debug("[storeDataToExternal 2]");
        
        return getNewStoreAnswer(key, StoreAnswer.Result.FAILED, requester);
    }
    
    
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleStoreExternalRequest(
    		DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message) {

    	log.debug("[handleStoreExternalRequest 1]");
    	
//    	long start = System.currentTimeMillis();
//        this.dhtKademliaNode.initiateStore();

    	this.handlerExecutorService.submit(() -> {
    		DHTStoreKademliaMessage.DHTData<BigInteger, NettyConnectionInfo, String, FileDTO> data = message.getData();
            StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleStoreExternal(message.getNode(), data.getRequester(), data.getKey(), data.getValue());
            if (storeAnswer.getResult().equals(StoreAnswer.Result.STORED)) {
            	
            	log.debug("[handleStoreExternalRequest 2]");

            	DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String> msg = new DHTStoreResultKademliaMessage<>(
            			new DHTStoreResultKademliaMessage.DHTStoreResult<>(data.getKey(), StoreAnswer.Result.STORED));
            	message.setType(P2PMessageType.DHT_EXTERNAL_STORE_RESULT);
            	
            	this.dhtKademliaNode.getMessageSender().sendAsyncMessage(
                        this.dhtKademliaNode,
                        data.getRequester(),
                        msg
                );
            }
            
//        	long end = System.currentTimeMillis();
//            this.dhtKademliaNode.completeStore(end-start);

        });
    	
    	log.debug("[handleStoreExternalRequest] 3");

        return new EmptyKademliaMessage<>();
    }
    
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> handleStoreExternal(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		String key, FileDTO value) {
    	
    	// check the size of the object to store
    	// if greater than [maxSize], split into multiple
    	// temporary: maxSize is hardcoded
    	ArrayList<FileDTO> chunks = new ArrayList<FileDTO>();
    	
    	// temporary approach: use list of file names
    	// preset by the caller
    	ArrayList<String> chunkNames = (ArrayList<String>)value.getNames();

		int size = value.getContent().length; // size in bytes
    	int maxSize = ((P2PNodeSettings) this.dhtKademliaNode.getNodeSettings()).getDataSize() * 1024;
    	
		log.debug("File (name, size) : "+value.getName()+", "+size);
		
    	log.debug("[handleStoreExternal 1] caller="+caller.getId()+", requester="+requester.getId());
    	
    	if (size > maxSize) {
    		// split
    		ByteBuffer byteBuffer = ByteBuffer.wrap(value.getContent());
    		
    		int count = size / maxSize;
    		int remainder = size % maxSize;
    		int index = 0;
    		
    		for (index=0; index<count; index++) {
    			byte[] chunkContent = new byte[maxSize];
    			byteBuffer.get(chunkContent, 0, maxSize);
    			
    			FileDTO fileDTO = new FileDTO();
    			//fileDTO.setName(value.getName()+"-split"+index);
    			fileDTO.setName(chunkNames.get(index));
    			fileDTO.setContent(chunkContent);
    			
    			chunks.add(fileDTO);
    			//index++;
    		}
    		// the remainder
    		if (remainder > 0) {
    			byte[] chunkContent = new byte[remainder];
    			byteBuffer.get(chunkContent, 0, remainder);
    			
    			FileDTO fileDTO = new FileDTO();
    			//fileDTO.setName(value.getName()+"-split"+index);
    			fileDTO.setName(chunkNames.get(index));
    			fileDTO.setContent(chunkContent);
    			
    			chunks.add(fileDTO);
    		}
    	}
    	else {
    		// single file
    		chunks.add(value);
    	}
    	
    	log.debug("[handleStoreExternal 2]");
    	
    	FileDTO chunk = chunks.get(0);
    	
    	String chunkKey = ""+(chunk.getName().hashCode() & 0x7fffffff);
    	
    	log.debug("[handleStoreExternal 3] chunk = "+chunk.getName()+", "+chunkKey);
    	
    	// TO-DO: this will return STORED when successful, need to explore returning PASSED instead
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = doStore(chunkKey, chunk);
    	
		// TO-DO: handle exception i.e. if the store is failed

		// for the remaining chunks, iterate then store each chunk to node
		// with smallest magnitude (aggregated latency and workload)
		for (int i=1; i<chunks.size(); i++) {
			
			chunk = chunks.get(i);
			
			chunkKey = ""+(chunk.getName().hashCode() & 0x7fffffff);
			
			// generate 2 random ID (uniform distribution) then look for the node matching or closest to these ID
	    	// note: the resulting ID already bounded to the network size

			// temporary: just look into local routing table
	        // TO-DO: Implement FIND_NODE
	        FindNodeAnswer<BigInteger, NettyConnectionInfo> findNodeAnswer1 = this.dhtKademliaNode.getRoutingTable().findClosest(uniformRandomGenerator.generate());
	        FindNodeAnswer<BigInteger, NettyConnectionInfo> findNodeAnswer2 = this.dhtKademliaNode.getRoutingTable().findClosest(uniformRandomGenerator.generate());
	        
	        // get the closest one (note: the list is in ascending order)
	        ExternalNode<BigInteger, NettyConnectionInfo> externalNode1 = (ExternalNode<BigInteger, NettyConnectionInfo>)findNodeAnswer1.getNodes().get(0);
	        ExternalNode<BigInteger, NettyConnectionInfo> externalNode2 = (ExternalNode<BigInteger, NettyConnectionInfo>)findNodeAnswer2.getNodes().get(0);
	        
	        /*
	        // get the ping latency of each node
	        long start = System.currentTimeMillis(); 
	        KademliaMessage<BigInteger, NettyConnectionInfo, ?> pingAnswer = 
	        		this.dhtKademliaNode.getMessageSender().sendMessage(
	        				this.dhtKademliaNode, externalNode1, new PingKademliaMessage<>());
	        if (pingAnswer.isAlive()) {
	        	log.debug("[handleStoreExternal 3] pingAnswer="+pingAnswer.toString());
	        }
	        else {
	        	log.error("");
	        	// TO-DO: handle if the node is not running 
	        }
	    	long node1latency = System.currentTimeMillis() - start;
	    	log.debug("handleStoreExternal 3] node1=" + externalNode1.getId() +", latency="+ node1latency);
	    	
	        start = System.currentTimeMillis(); 
	        pingAnswer = 
	        		this.dhtKademliaNode.getMessageSender().sendMessage(
	        				this.dhtKademliaNode, externalNode2, new PingKademliaMessage<>());
	        if (pingAnswer.isAlive()) {
	        	log.debug("[handleStoreExternal 4] pingAnswer="+pingAnswer.toString());
	        }
	        else {
	        	log.error("");
	        	// TO-DO: handle if the node is not running 
	        }
	    	long node2latency = System.currentTimeMillis() - start;    
	    	log.debug("handleStoreExternal 5] node2=" + externalNode2.getId() +", latency="+ node2latency);

	        // choose the one with smallest latency
	        if (node1latency < node2latency) { 
	        	// store at selected node (actual node)
	        	// note: requester is this current node, not the node that calls handleStoreExternal in the first place
	        	storeAnswer = storeDataToExternalNext(this.dhtKademliaNode, externalNode1, chunkKey, chunk);
	        }
	        else {
	        	storeAnswer = storeDataToExternalNext(this.dhtKademliaNode, externalNode2, chunkKey, chunk);
	        }
	        */
	        
	        // get throughput of each node
	        NodeWorkloadMessage<BigInteger, NettyConnectionInfo> workloadMessage = 
	        		new NodeWorkloadMessage<>(new NodeWorkloadMessage.NodeWorkload<>(requester));
	        workloadMessage.setType(P2PMessageType.NODE_THROUGHPUT);
	        
	        KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> workloadResponse = 
	        		this.dhtKademliaNode.getMessageSender().sendMessage(
	        				this.dhtKademliaNode,
	        				externalNode1, 
	        				workloadMessage);

	        log.debug("[handleStoreExternal 4] statusResponse for node1="+workloadResponse.toString());
	        
	        NodeWorkloadResultMessage.NodeWorkloadResult<Double> workloadData = (NodeWorkloadResultMessage.NodeWorkloadResult<Double>) workloadResponse.getData();
	        double node1Throughput = workloadData.getWorkload();
	        log.debug("[handleStoreExternal 5] node1=" + externalNode1.getId() +", throughput="+ node1Throughput);

	        workloadResponse = 
	        		this.dhtKademliaNode.getMessageSender().sendMessage(
	        				this.dhtKademliaNode,
	        				externalNode2, 
	        				workloadMessage);

	        log.debug("[handleStoreExternal 6] statusResponse for node2="+workloadResponse.toString());
	        
	        workloadData = (NodeWorkloadResultMessage.NodeWorkloadResult<Double>) workloadResponse.getData();
	        double node2Throughput = workloadData.getWorkload();
	        log.debug("[handleStoreExternal 7] node2=" + externalNode2.getId() +", throughput="+ node2Throughput);

	        // choose the one with highest throughput
	        if (node1Throughput > node2Throughput) {
	        	// store at node 1
	        	// note: requester is this current node, not the node that calls handleStoreExternal in the first place
	        	storeAnswer = storeDataToExternalNext(this.dhtKademliaNode, externalNode1, chunkKey, chunk);
	        }
	        else {
	        	// store at node 2
	        	storeAnswer = storeDataToExternalNext(this.dhtKademliaNode, externalNode2, chunkKey, chunk);
	        }
	        // TO-DO: handle exception i.e. if the store is failed
		}
		
		log.debug("[handleStoreExternal 10]");
    	
    	return storeAnswer;
    }
    
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> storeDataToExternalNext (
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		Node<BigInteger, NettyConnectionInfo> external, 
    		String key, FileDTO value) {
        
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer;
    	
    	DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message = new DHTStoreKademliaMessage<>(new DHTStoreKademliaMessage.DHTData<>(requester, key, value));
    	message.setType(P2PMessageType.DHT_EXTERNAL_NEXT_STORE);

    	KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> response = this.dhtKademliaNode.getMessageSender().sendMessage(
                this.dhtKademliaNode,
                external,
                message
        );
        if (response.isAlive()){
            //return getNewStoreAnswer(key, StoreAnswer.Result.PASSED, requester);
        	storeAnswer = getNewStoreAnswer(key, StoreAnswer.Result.PASSED, requester);
            return storeAnswer;
        }
        
        return getNewStoreAnswer(key, StoreAnswer.Result.FAILED, requester);
    }

    
    
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleStoreExternalNextRequest(
    		DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message) {

//    	long start = System.currentTimeMillis();
//        this.dhtKademliaNode.initiateStore();

    	this.handlerExecutorService.submit(() -> {
    		DHTStoreKademliaMessage.DHTData<BigInteger, NettyConnectionInfo, String, FileDTO> data = message.getData();
            StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleStoreExternalNext(message.getNode(), data.getRequester(), data.getKey(), data.getValue());
            if (storeAnswer.getResult().equals(StoreAnswer.Result.STORED)) {
                
            	DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String> msg = new DHTStoreResultKademliaMessage<>(
            			new DHTStoreResultKademliaMessage.DHTStoreResult<>(data.getKey(), StoreAnswer.Result.STORED));
            	message.setType(P2PMessageType.DHT_EXTERNAL_NEXT_STORE_RESULT);
            	
            	this.dhtKademliaNode.getMessageSender().sendAsyncMessage(
                        this.dhtKademliaNode,
                        data.getRequester(),
                        msg
                );
            }
            
//        	long end = System.currentTimeMillis();
//            this.dhtKademliaNode.completeStore(end-start);

        });
    	
        return new EmptyKademliaMessage<>();
    }
    
    
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> handleStoreExternalNext(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		String key, FileDTO value) {
    	
    	// note: key is in the hash format 
    	// (i.e., the caller already performed key = fileDTO.getName().hashCode();
    	// store the first chunk of data into this node (actual node)
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = this.doStore(key, value);
		// TO-DO: handle exception i.e. if the store is failed

		// store the mapping of first chunk to the 'supposedly storing node' (mapping node)
		// need to empty the content to save network bandwidth
    	value.setContent(null);
		String actualNodeConnectionInfo = 
				this.dhtKademliaNode.getId() + ":" + this.dhtKademliaNode.getConnectionInfo().getHost() + ":" + this.dhtKademliaNode.getConnectionInfo().getPort();
		
		value.setExternalNode(actualNodeConnectionInfo);
		
    	// bound the key to network size for lookup purpose
		BigInteger hash = this.dhtKademliaNode.getKeyHashGenerator().generateHash(key);
    	
        FindNodeAnswer<BigInteger, NettyConnectionInfo> findNodeAnswer = this.dhtKademliaNode.getRoutingTable().findClosest(hash);
        storeAnswer = storeDataToClosestNode(this.dhtKademliaNode, requester, findNodeAnswer.getNodes(), key, value);
		// TO-DO: handle exception i.e. if the store is failed

    	return storeAnswer;
    }    

    
    @Override
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> doStore(String key, FileDTO value){

    	long start = System.currentTimeMillis();
        this.dhtKademliaNode.initiateStore();
        
    	log.debug("[P2PPTStoreService.doStore] 1 key = "+key+", value="+value.getName()+", ext="+value.getExternalNode());
    	
    	this.dhtKademliaNode.getKademliaRepository().store(key, value);
    	
    	long end = System.currentTimeMillis();
        this.dhtKademliaNode.completeStore(end-start);
        
        return getNewStoreAnswer(key, StoreAnswer.Result.STORED, this.dhtKademliaNode);
    }

    
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> storeDataToClosestNode(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		List<ExternalNode<BigInteger, NettyConnectionInfo>> externalNodeList, 
    		String key, FileDTO value){
        
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer;
    	long start = 0, end = 0;
    	
    	log.debug("[P2PPTStoreService.storeDataToClosestNode 1] caller="+caller.getId()+", requester="+requester.getId()+", key= "+key+", value="+value.getName()+", ext="+value.getExternalNode());
    	
    	Date date = DateUtil.getDateOfSecondsAgo(this.dhtKademliaNode.getNodeSettings().getMaximumLastSeenAgeToConsiderAlive());
        for (ExternalNode<BigInteger, NettyConnectionInfo> externalNode : externalNodeList) {
        	
        	log.debug("[P2PPTStoreService.storeDataToClosestNode 2] external = "+externalNode.getId());
        	
            //if current node is the closest node, store the value (Scenario A)
            if(externalNode.getId().equals(this.dhtKademliaNode.getId())){

                storeAnswer = doStore(key, value);
                
                log.debug("[P2PPTStoreService.storeDataToClosestNode 3]");
                
                return storeAnswer;
                // end added
            }

            // Continue if requester is known to be the closest, but it's also same as caller
            // This means that this is the first time that PASS is happening and requester wants for force pushing it to other nodes,
            // or in other words:
            // This is the first time the requester node has passed the store request to some other node. So we try more.
            // This approach can be disabled through nodeSettings "Enabled First Store Request Force Pass"
            // This has no conflicts with 'Scenario A' because:
            // If we were the closest node we'd have already stored the data
            
            // modified by Fitrio
            // in the application, requester should be excluded from checking
            // because the requester is always a client node which does not join the network
            // the below is valid if the client node joins the network
            // original logic
//            if (requester.getId().equals(externalNode.getId()) && requester.getId().equals(caller.getId())
//                    && this.dhtKademliaNode.getNodeSettings().isEnabledFirstStoreRequestForcePass()) {
//                continue;
//            }
            // new logic
            // continue if caller = external
            log.debug("[P2PStoreService.storeDataToClosestNode] 3, isEnabledFirstStoreRequestForcePass="+this.dhtKademliaNode.getNodeSettings().isEnabledFirstStoreRequestForcePass());
            
            if (externalNode.getId().equals(caller.getId()) && this.dhtKademliaNode.getNodeSettings().isEnabledFirstStoreRequestForcePass()){
            	continue;
            }
            // end modified
            
            // otherwise, try next closest node in routing table
            // if close node is alive, tell it to store the data
            // to know if it's alive the last seen should either be close or we ping and check the result
            if(NodeUtil.recentlySeenOrAlive(this.dhtKademliaNode, externalNode, date)){
            	
            	log.debug("[P2PPTStoreService.storeDataToClosestNode 4]");
            	
                KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> response = this.dhtKademliaNode.getMessageSender().sendMessage(
                        this.dhtKademliaNode,
                        externalNode,
                        new DHTStoreKademliaMessage<>(
                                new DHTStoreKademliaMessage.DHTData<>(requester, key, value)
                        )
                );
                if (response.isAlive()){
                    //return getNewStoreAnswer(key, StoreAnswer.Result.PASSED, requester);

                	storeAnswer = getNewStoreAnswer(key, StoreAnswer.Result.PASSED, requester);
                	
                    return storeAnswer;
                }
            }
        }
        
        return getNewStoreAnswer(key, StoreAnswer.Result.FAILED, requester);
    }
    
    
	public void loadNodes() {
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
    		        	
    		        	nodes.add(node);
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
