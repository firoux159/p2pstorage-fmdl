package org.gbtc.storage.services;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.repository.FileDTO;

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
import io.ep2p.kademlia.services.PushingDHTStoreService;
import io.ep2p.kademlia.util.DateUtil;
import io.ep2p.kademlia.util.NodeUtil;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PStoreService extends PushingDHTStoreService<BigInteger, NettyConnectionInfo, String, FileDTO> {
	
	//private static final Logger logger = LogManager.getLogger(P2PStoreService.class);

    public P2PStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode, ExecutorService executorService) {
    	super(dhtKademliaNode, executorService);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public KademliaMessage<BigInteger, NettyConnectionInfo, ?> handle(
    		KademliaNodeAPI kademliaNode, 
    		KademliaMessage message) {
        
    	System.out.println("StoreService: P2PStoreService");
    	
    	if (message.isAlive()){
    		// do not update routing table if the request is from client node
    		if (!P2PMessageType.CLIENT_STORE_REQ.equals(message.getType())) {
    			this.dhtKademliaNode.getRoutingTable().forceUpdate(message.getNode());
    			System.out.println("[P2PStoreService.handle] Routing table of node "+this.dhtKademliaNode.getId()+" updated with node="+message.getNode().getId());
    		}
        }
    	
        switch (message.getType()) {
	    	case P2PMessageType.CLIENT_STORE_REQ:
	    		if (!(message instanceof DHTStoreKademliaMessage))
	    			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreKademliaMessage");
	    		
	    		// special logic: if the request came from client node, set the caller to current node
	    		// note: the requester node (i.e., the client node) is preserved in the message.getData().getRequester()
	    		//message.setNode(this.dhtKademliaNode);
	    		
	    		return handleStoreRequest((DHTStoreKademliaMessage) message);        		

        	case P2PMessageType.DHT_STORE:
        		if (!(message instanceof DHTStoreKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreKademliaMessage");
        		return handleStoreRequest((DHTStoreKademliaMessage) message);
        		
        	case P2PMessageType.DHT_STORE_RESULT:
        		if (!(message instanceof DHTStoreResultKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreResultKademliaMessage");
        		return handleStoreResult((DHTStoreResultKademliaMessage) message);

        	default:
        		throw new IllegalArgumentException("message param is not supported");
        }	
    }
    
    @Override
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleStoreRequest(
    		DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message) {

    	System.out.println("[handleStoreRequest 1]");
    	
		DHTStoreKademliaMessage.DHTData<BigInteger, NettyConnectionInfo, String, FileDTO> data = message.getData();
		
		log.debug("[handleStoreRequest 2] node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey()+", value="+data.getValue().getName());

//    	long start = System.currentTimeMillis();
//        this.dhtKademliaNode.initiateStore();

    	this.handlerExecutorService.submit(() -> {
    		//DHTStoreKademliaMessage.DHTData<BigInteger, NettyConnectionInfo, String, FileDTO> data = message.getData();
    		
    		log.debug("[handleStoreRequest 3] node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey()+", value="+data.getValue().getName());
    		
            StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleStore(message.getNode(), data.getRequester(), data.getKey(), data.getValue());
            if (storeAnswer.getResult().equals(StoreAnswer.Result.STORED)) {
            	
            	log.debug("[handleStoreRequest 4] requester="+data.getRequester().getId()+", key="+data.getKey()+", value="+data.getValue().getName());

                this.dhtKademliaNode.getMessageSender().sendAsyncMessage(
                        this.dhtKademliaNode,
                        data.getRequester(),
                        new DHTStoreResultKademliaMessage<>(
                                new DHTStoreResultKademliaMessage.DHTStoreResult<>(data.getKey(), StoreAnswer.Result.STORED)
                        )
                );
            }
            
//        	long end = System.currentTimeMillis();
//            this.dhtKademliaNode.completeStore(end-start);

        });
    	
    	log.debug("[handleStoreRequest 5]");
    	
        return new EmptyKademliaMessage<>();
    }

    
    @Override
    public Future<StoreAnswer<BigInteger, NettyConnectionInfo, String>> store(String key, FileDTO value) {

    	System.out.println("StoreService: P2PStoreService");
    	
    	CompletableFuture<StoreAnswer<BigInteger, NettyConnectionInfo, String>> completableFuture = new CompletableFuture<>();
       
    	long start = System.currentTimeMillis();
        this.dhtKademliaNode.initiateStore();

    	storeFutureMap.computeIfAbsent(key, k -> {
            
    		completableFuture.whenComplete((a, t) -> storeFutureMap.remove(key));
            
    		StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleStore(this.dhtKademliaNode, this.dhtKademliaNode, key, value);
    		
        	long end = System.currentTimeMillis();
            this.dhtKademliaNode.completeStore(end-start);

    		if (storeAnswer.getResult().equals(StoreAnswer.Result.STORED) || storeAnswer.getResult().equals(StoreAnswer.Result.FAILED)){
                completableFuture.complete(storeAnswer);
                
                System.out.println("[P2PStoreService.store] 1");
                
                return null;
    		}
            
            System.out.println("[P2PStoreService.store] 2");
            
            return completableFuture;
        });
        
        //System.out.println("[P2PStoreService.store] 3");
        
        return completableFuture;
    }


    @Override
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> handleStore(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		String key, FileDTO value){
        
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer;
        BigInteger hash = this.dhtKademliaNode.getKeyHashGenerator().generateHash(key);
        
        log.debug("[P2PStoreService.handleStore 1] This="+this.dhtKademliaNode.getId()+", Caller="+caller.getId()+", Requester="+requester.getId()+", key="+key);
        
        long start = 0, end = 0;

        // If some other node is calling the store, and that other node is not this node,
        // But the origin request is by this node, then persist it
        // The closest node we know to the key knows us as the closest know to the key and not themselves (?!?)
        // Useful only in case of nodeSettings.isEnabledFirstStoreRequestForcePass()

        // remarked by Fitrio
        // this logic will never returns true because the requester is always a client node which does not join the network
        // the below is valid if the client node joins the network
        if (!caller.getId().equals(this.dhtKademliaNode.getId()) && requester.getId().equals(this.dhtKademliaNode.getId())){
            //return doStore(key, value);

        	log.debug("[P2PStoreService.handleStore] 2");
        	
        	storeAnswer = doStore(key, value);
        	
        	return storeAnswer;
        	// end added
        }

        // If current node should persist the data, do it immediately
        // For smaller networks this helps to avoid the process of finding alive close nodes to pass data to

        // the following logic look into routing table and get the closest node the key, ordered by distance ascending
        // IMPORTANT TO-DO: I assume that current node can be included in the result if it itself is the closest node to the key
        // consider this as FIND request
//        start = System.currentTimeMillis();
//        this.dhtKademliaNode.initiateFind();
        
        FindNodeAnswer<BigInteger, NettyConnectionInfo> findNodeAnswer = this.dhtKademliaNode.getRoutingTable().findClosest(hash);
        
//        end = System.currentTimeMillis();
//        this.dhtKademliaNode.completeFind(end-start);
        
        // added by Fitrio to break the 'cyclic' issue - temporary solution, not a good one
        // if the current node is the closest compared to closest node in routing table,
        // store the value to the current node
        BigInteger distanceFromCurrentNode = this.dhtKademliaNode.getRoutingTable().getDistance(hash);
        BigInteger distanceFromClosestsNode = findNodeAnswer.getNodes().get(0).getDistance();
        
        log.debug("[handleStore] distance from current node="+distanceFromCurrentNode+", distance from closest node="+distanceFromClosestsNode);
        
        if (distanceFromCurrentNode.compareTo(distanceFromClosestsNode) < 0) {
        	log.debug("[handleStore] key closer to current node, store to current node");
        	
        	storeAnswer = doStore(key, value);
        	
        	return storeAnswer;
        }
        
    	log.debug("[handleStore] key closer to closest node, proceed to closest node");
    	storeAnswer = storeDataToClosestNode(caller, requester, findNodeAnswer.getNodes(), key, value);	

        if(storeAnswer.getResult().equals(StoreAnswer.Result.FAILED)){
        	//storeAnswer = doStore(key, value);
        	log.debug("[handleStore] failed to store closest node, store to current node");
        	
            storeAnswer = doStore(key, value);
        }
        
        return storeAnswer;
    }
    
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> storeDataToClosestNode(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		List<ExternalNode<BigInteger, NettyConnectionInfo>> externalNodeList, 
    		String key, FileDTO value){
        
        // added by Fitrio
    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer;
    	long start = 0, end = 0;
        // end added
    	
    	//System.out.println("[P2PStoreService.storeDataToClosestNode] 1");
    	log.debug("[P2PStoreService.storeDataToClosestNode 1]");
    	
    	String flattenClosestNode = "";
    	for (ExternalNode<BigInteger, NettyConnectionInfo> externalNode : externalNodeList) {
    		flattenClosestNode += (externalNode.getId() + ", " + externalNode.getDistance()+"|"); 
    	}
    	log.debug("[P2PStoreService.storeDataToClosestNode 2] Closest node="+flattenClosestNode);
    	
    	Date date = DateUtil.getDateOfSecondsAgo(this.dhtKademliaNode.getNodeSettings().getMaximumLastSeenAgeToConsiderAlive());
    	
        for (ExternalNode<BigInteger, NettyConnectionInfo> externalNode : externalNodeList) {
        	
        	//System.out.println("[P2PStoreService.storeDataToClosestNode] 2, external="+externalNode.getId());
        	log.debug("[P2PStoreService.storeDataToClosestNode 2] this="+this.dhtKademliaNode.getId()+", external="+externalNode.getId()+", requester="+requester.getId()+", caller="+caller.getId());

            //if current node is the closest node, store the value (Scenario A)
            if(externalNode.getId().equals(this.dhtKademliaNode.getId())){
            	log.debug("[P2PStoreService.storeDataToClosestNode 3] current node is closest node, proceed to store at this node");
            	//return doStore(key, value);
                storeAnswer = doStore(key, value);
                
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
            if (requester.getId().equals(externalNode.getId()) && requester.getId().equals(caller.getId())
                    && this.dhtKademliaNode.getNodeSettings().isEnabledFirstStoreRequestForcePass()
            ){
                continue;
            }
            
            // otherwise, try next closest node in routing table
            // if close node is alive, tell it to store the data
            // to know if it's alive the last seen should either be close or we ping and check the result
            if(NodeUtil.recentlySeenOrAlive(this.dhtKademliaNode, externalNode, date)){
                
            	//System.out.println("[P2PStoreService.storeDataToClosestNode] 3, sending message to external");
            	log.debug("[P2PStoreService.storeDataToClosestNode] 3, sending message to external");
            	
            	KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> response = this.dhtKademliaNode.getMessageSender().sendMessage(
                        this.dhtKademliaNode,
                        externalNode,
                        new DHTStoreKademliaMessage<>(
                                new DHTStoreKademliaMessage.DHTData<>(requester, key, value)
                        )
                );
                if (response.isAlive()){
                    //return getNewStoreAnswer(key, StoreAnswer.Result.PASSED, requester);
                	
                	log.debug("[P2PStoreService.storeDataToClosestNode] 4, return message PASSED to caller");

                	storeAnswer = getNewStoreAnswer(key, StoreAnswer.Result.PASSED, requester);
                    
                    return storeAnswer;
                }
            }
        }
        
        log.debug("[P2PStoreService.storeDataToClosestNode] 4, return message FAILED to caller");
        
        return getNewStoreAnswer(key, StoreAnswer.Result.FAILED, requester);
    }
    
    @Override
    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> doStore(String key, FileDTO value) {
    	
    	long start = System.currentTimeMillis();
        this.dhtKademliaNode.initiateStore();

    	this.dhtKademliaNode.getKademliaRepository().store(key, value);
    	
    	long end = System.currentTimeMillis();
        this.dhtKademliaNode.completeStore(end-start);
        
        return getNewStoreAnswer(key, StoreAnswer.Result.STORED, this.dhtKademliaNode);
    }

}
