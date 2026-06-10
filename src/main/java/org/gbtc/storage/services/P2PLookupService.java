package org.gbtc.storage.services;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.repository.FileDTO;
import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.protocol.MessageType;
import io.ep2p.kademlia.protocol.message.DHTLookupKademliaMessage;
import io.ep2p.kademlia.protocol.message.DHTLookupResultKademliaMessage;
import io.ep2p.kademlia.protocol.message.EmptyKademliaMessage;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.services.DHTLookupService;
import io.ep2p.kademlia.util.DateUtil;
import io.ep2p.kademlia.util.NodeUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PLookupService extends DHTLookupService<BigInteger, NettyConnectionInfo, String, FileDTO> {

    public P2PLookupService(
            DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode,
            ExecutorService executorService) {
    	
        super(dhtKademliaNode, executorService);
    }
    
    
    @Override
    @SuppressWarnings("unchecked")
    public KademliaMessage<BigInteger, NettyConnectionInfo, ?> handle(
    		KademliaNodeAPI kademliaNode, 
    		KademliaMessage message) {
    	
    	log.debug("LookupService: P2PLookupService");
    	
    	if (message.isAlive()){
    		// do not update routing table if the request is from client node
    		if (!P2PMessageType.CLIENT_RETRIEVE_REQ.equals(message.getType())) {
    			this.dhtKademliaNode.getRoutingTable().forceUpdate(message.getNode());
    			log.debug("[P2PStoreService.handle] Routing table of node "+this.dhtKademliaNode.getId()+" updated with node="+message.getNode().getId());
    		}
        }

        switch (message.getType()) {
	        case P2PMessageType.CLIENT_RETRIEVE_REQ:
	            if (!(message instanceof DHTLookupKademliaMessage))
	                throw new IllegalArgumentException("Cant handle message. Required: DHTLookupKademliaMessage");
	            return handleLookupRequest((DHTLookupKademliaMessage<BigInteger, NettyConnectionInfo, String>) message);
        
            case MessageType.DHT_LOOKUP:
                if (!(message instanceof DHTLookupKademliaMessage))
                    throw new IllegalArgumentException("Cant handle message. Required: DHTLookupKademliaMessage");
                return handleLookupRequest((DHTLookupKademliaMessage<BigInteger, NettyConnectionInfo, String>) message);

            case MessageType.DHT_LOOKUP_RESULT:
                if (!(message instanceof DHTLookupResultKademliaMessage))
                    throw new IllegalArgumentException("Cant handle message. Required: DHTLookupResultKademliaMessage");
                return handleLookupResult((DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO>) message);
            
            default:
                throw new IllegalArgumentException("message param is not supported");
        }
    }
    
    
    @Override
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleLookupRequest(DHTLookupKademliaMessage<BigInteger, NettyConnectionInfo, String> message) {

//    	long start = System.currentTimeMillis();
//    	this.dhtKademliaNode.initiateLookup();
    	
    	this.handlerExecutorService.submit(() -> {
            DHTLookupKademliaMessage.DHTLookup<BigInteger, NettyConnectionInfo, String> data = message.getData();
            
            LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer = handleLookup(this.dhtKademliaNode, data.getRequester(), data.getKey(), data.getCurrentTry());
            
            if (lookupAnswer.getResult().equals(LookupAnswer.Result.FAILED) || lookupAnswer.getResult().equals(LookupAnswer.Result.FOUND)){
            
            	this.dhtKademliaNode.getMessageSender().sendAsyncMessage(
            			this.dhtKademliaNode, 
            			data.getRequester(), 
            			new DHTLookupResultKademliaMessage<>(
            					new DHTLookupResultKademliaMessage.DHTLookupResult<>(lookupAnswer.getResult(), data.getKey(), lookupAnswer.getValue()
                        )
                ));
            }
            
//        	long end = System.currentTimeMillis();
//        	this.dhtKademliaNode.completeLookup(end-start);

        });
    	
        return new EmptyKademliaMessage<>();
    }
    
    @Override
    public Future<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>> lookup(String key){
        List<CompletableFuture<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>>> futures = 
        		this.lookupFutureMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());

        CompletableFuture<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>> lookupAnswerFuture = new CompletableFuture<>();
        
        lookupAnswerFuture.whenComplete((a, t) -> futures.remove(lookupAnswerFuture));

        futures.add(lookupAnswerFuture);
        
//    	long start = System.currentTimeMillis();
//    	this.dhtKademliaNode.initiateLookup();

        this.handlerExecutorService.submit(() -> {
            LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer = handleLookup(this.dhtKademliaNode, this.dhtKademliaNode, key, 0);
            
            if (lookupAnswer.getResult().equals(LookupAnswer.Result.FOUND) || lookupAnswer.getResult().equals(LookupAnswer.Result.FAILED)) {
                lookupAnswerFuture.complete(lookupAnswer);
                futures.remove(lookupAnswerFuture);
            }
            
//        	long end = System.currentTimeMillis();
//        	this.dhtKademliaNode.completeLookup(end-start);

        });
        
        return lookupAnswerFuture;
    }

    @Override
    protected LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> handleLookup(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		String key, int currentTry){

    	log.debug("[handleLookup 1] key="+key);
    	
    	LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer;
    	
    	// Check if current node contains data
        if(this.dhtKademliaNode.getKademliaRepository().contains(key)){

        	log.debug("[handleLookup 2] key found in DHT of node="+this.dhtKademliaNode.getId());
        	
        	long start = System.currentTimeMillis();
        	this.dhtKademliaNode.initiateLookup();

        	FileDTO value = this.dhtKademliaNode.getKademliaRepository().get(key);
        	lookupAnswer = getNewLookupAnswer(key, LookupAnswer.Result.FOUND, this.dhtKademliaNode, value);
        	
        	long end = System.currentTimeMillis();
        	this.dhtKademliaNode.completeLookup(end-start);
            
        	return lookupAnswer;
        }

        // If max tries has reached then return failed
        if (currentTry == this.dhtKademliaNode.getNodeSettings().getIdentifierSize()){
  
        	log.debug("[handleLookup 3] maximum try reached, return FAILED to caller");
        	
        	return getNewLookupAnswer(key, LookupAnswer.Result.FAILED, this.dhtKademliaNode, null);
        }

        lookupAnswer = getDataFromClosestNodes(caller, requester, key, currentTry);

        return lookupAnswer;
    }
    
    protected LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> getDataFromClosestNodes(
    		Node<BigInteger, NettyConnectionInfo> caller, Node<BigInteger, NettyConnectionInfo> requester, 
    		String key, int currentTry){
        
    	log.debug("[getDataFromClosestNodes 1]");
    	
    	BigInteger hash = this.dhtKademliaNode.getKeyHashGenerator().generateHash(key);
    	
    	FindNodeAnswer<BigInteger, NettyConnectionInfo> findNodeAnswer = this.dhtKademliaNode.getRoutingTable().findClosest(hash);
        
        Date date = DateUtil.getDateOfSecondsAgo(this.dhtKademliaNode.getNodeSettings().getMaximumLastSeenAgeToConsiderAlive());
        
        for (ExternalNode<BigInteger, NettyConnectionInfo> externalNode : findNodeAnswer.getNodes()) {

        	log.debug("[getDataFromClosestNodes 2] external="+externalNode.getId());
        	
        	//ignore self because we already checked if current node holds the data or not
            //Also ignore nodeToIgnore if its not null
            if(externalNode.getId().equals(this.dhtKademliaNode.getId()) || (caller != null && externalNode.getId().equals(caller.getId())))
                continue;

            // If requester knew the data, it wouldn't have asked for it
            if (externalNode.getId().equals(requester.getId())){
                continue;
            }
            
            //if node is alive, ask for data
            if(NodeUtil.recentlySeenOrAlive(this.dhtKademliaNode, externalNode, date)){
            	
            	log.debug("[getDataFromClosestNodes 3] sending message to external");
            	
                KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> response = this.dhtKademliaNode.getMessageSender().sendMessage(
                        this.dhtKademliaNode,
                        externalNode,
                        new DHTLookupKademliaMessage<>(
                                new DHTLookupKademliaMessage.DHTLookup<>(requester, key, currentTry + 1)
                        )
                );
                if (response.isAlive()){

                	log.debug("[getDataFromClosestNodes 4] return message PASSED to caller");
                	
                	return getNewLookupAnswer(key, LookupAnswer.Result.PASSED, this.dhtKademliaNode, null);
                }
            }
        }

        log.debug("[getDataFromClosestNodes 5] return message FAILED to caller");
        
        return getNewLookupAnswer(key, LookupAnswer.Result.FAILED, this.dhtKademliaNode, null);

    }
}
