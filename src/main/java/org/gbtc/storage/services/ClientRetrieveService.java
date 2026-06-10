package org.gbtc.storage.services;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;

import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.repository.FileDTO;
import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.MessageType;
import io.ep2p.kademlia.protocol.message.DHTLookupKademliaMessage;
import io.ep2p.kademlia.protocol.message.DHTLookupResultKademliaMessage;
import io.ep2p.kademlia.protocol.message.DHTStoreKademliaMessage;
import io.ep2p.kademlia.protocol.message.EmptyKademliaMessage;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.services.DHTLookupService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientRetrieveService extends DHTLookupService<BigInteger, NettyConnectionInfo, String, FileDTO> {
	
//	private static final Logger logger = LogManager.getLogger(ClientRetrieveService.class);
	
    public ClientRetrieveService(
            DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode,
            ExecutorService executorService) {
    	
    	super(dhtKademliaNode, executorService);
    }

    @Override
    public KademliaMessage handle(KademliaNodeAPI kademliaNode, KademliaMessage message) {

    	switch (message.getType()) {
//            case P2PMessageType.CLIENT_RETRIEVE_REQ :
//                if (!(message instanceof DHTLookupKademliaMessage))
//                    throw new IllegalArgumentException("Cant handle message. Required: DHTLookupKademliaMessage");
//                return handleClientLookupRequest((DHTLookupKademliaMessage<BigInteger, NettyConnectionInfo, String>) message);
                
            case MessageType.DHT_LOOKUP_RESULT:
                if (!(message instanceof DHTLookupResultKademliaMessage))
                    throw new IllegalArgumentException("Cant handle message. Required: DHTLookupResultKademliaMessage");
                return handleLookupResult((DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO>) message);
                
            default:
                throw new IllegalArgumentException("message param is not supported");
        }
    }
    
    public Future<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>> clientLookup(Node<BigInteger, NettyConnectionInfo> receiver, String compositeKey) {

    	// key consists of storer node id and the data key (storerId@key)
    	//split the key
    	
    	String key = compositeKey;
    	
    	if (compositeKey.indexOf("@") > 0) {
        	String[] split = compositeKey.split("@");
        	BigInteger storerNodeId = new BigInteger(split[0]);
        	key = split[1];
    	}
    	
    	
    	List<CompletableFuture<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>>> futures = this.lookupFutureMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());

        CompletableFuture<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>> lookupAnswerFuture = new CompletableFuture<>();
        lookupAnswerFuture.whenComplete((a, t) -> futures.remove(lookupAnswerFuture));

        futures.add(lookupAnswerFuture);
        
        this.handlerExecutorService.submit(() -> {
            LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer = handleClientLookup(this.dhtKademliaNode, receiver, compositeKey, 0);
            if (lookupAnswer.getResult().equals(LookupAnswer.Result.FOUND) || lookupAnswer.getResult().equals(LookupAnswer.Result.FAILED)) {
                lookupAnswerFuture.complete(lookupAnswer);
                futures.remove(lookupAnswerFuture);
                
                System.out.println("[ClientRetrieveService.clientLookup 1]");
            }
            
            System.out.println("[ClientRetrieveService.clientLookup 2]");
        });

    	
        return lookupAnswerFuture;
    }
    
    protected LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> handleClientLookup(
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		Node<BigInteger, NettyConnectionInfo> receiver, 
    		String compositeKey, int currentTry){
        
    	// key consists of storer node id and the data key (storerId@key)
    	//split the key

    	String key = compositeKey;
    	
    	if (compositeKey.indexOf("@") > 0) {
        	String[] split = compositeKey.split("@");
        	BigInteger storerNodeId = new BigInteger(split[0]);
        	key = split[1];
    	}

    	//logger.info(String.format("[ClientRetrieveService.handleClientLookup 1] Sending composite=%s, key=%s to node %s for lookup", compositeKey, key, receiver.getId()));
    	log.info(String.format("[ClientRetrieveService.handleClientLookup 1] Sending composite=%s, key=%s to node %s for lookup", compositeKey, key, receiver.getId()));

        DHTLookupKademliaMessage<BigInteger, NettyConnectionInfo, String> retrieveMessage = 
        		new DHTLookupKademliaMessage<>(new DHTLookupKademliaMessage.DHTLookup<>(requester, compositeKey, currentTry)); 
        // override the message type to inform the server that this is a client message (not from other node/server)
        retrieveMessage.setType(P2PMessageType.CLIENT_RETRIEVE_REQ);

    	KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> response = this.dhtKademliaNode.getMessageSender().sendMessage(
                this.dhtKademliaNode,
                receiver,
                retrieveMessage
        );
    	
        if (response.isAlive()){
        	log.info(String.format("[ClientRetrieveService.handleClientLookup 2], return message PASSED to caller"));
        	
        	return getNewLookupAnswer(key, LookupAnswer.Result.PASSED, requester, null);
        }
        
        log.info(String.format("[ClientRetrieveService.handleClientLookup 3], return message FAILED to caller"));
        
        return getNewLookupAnswer(key, LookupAnswer.Result.FAILED, requester, null);
    }
    
    @Override
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleLookupResult(DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message) {

    	DHTLookupResultKademliaMessage.DHTLookupResult<String, FileDTO> data = message.getData();
    	
    	//logger.info(String.format("[ClientRetrieveService.handleLookupResult 1] key=%s, value=%s, result=%s", data.getKey(), data.getValue().getName(), data.getResult()));
    	
        List<CompletableFuture<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>>> futuresList = this.lookupFutureMap.get(data.getKey());
        if (futuresList != null){
            LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> answer = new LookupAnswer<>();
            answer.setResult(data.getResult());
            answer.setKey(data.getKey());
            answer.setValue(data.getValue());
            answer.setNode(message.getNode());
            for (CompletableFuture<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>> future : futuresList) {
                future.complete(answer);
            }
        }
        
        return new EmptyKademliaMessage<>();
    }
    
    
    
//    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleClientLookupRequest(
//    		DHTLookupKademliaMessage<BigInteger, NettyConnectionInfo, String> message) {
//    	
//    	DHTLookupKademliaMessage.DHTLookup<BigInteger, NettyConnectionInfo, String> data = message.getData();
//    	
//    	System.out.println("[ClientRetrieveService.handleClientLookupRequest 1]  node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey());
//    	
//    	this.handlerExecutorService.submit(() -> {
//    		
//    		LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer = handleClientLookup(this.dhtKademliaNode, this.dhtKademliaNode, data.getKey(), data.getCurrentTry());
//    		
//            if (lookupAnswer.getResult().equals(LookupAnswer.Result.FAILED) || lookupAnswer.getResult().equals(LookupAnswer.Result.FOUND)){
//                
//            	System.out.println("[ClientRetrieveService.handleClientLookupRequest 2] Data lookup at node "+ lookupAnswer.getNode().getId() +" "+lookupAnswer.getResult());
//            	
////            	this.dhtKademliaNode.getMessageSender().sendAsyncMessage(this.dhtKademliaNode, data.getRequester(), new DHTLookupResultKademliaMessage<>(
////                        new DHTLookupResultKademliaMessage.DHTLookupResult<>(
////                                lookupAnswer.getResult(),
////                                data.getKey(),
////                                lookupAnswer.getValue()
////                        )
////                ));
//            }
//            
//        	DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> response = 
//			new DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO>(
//					new DHTLookupResultKademliaMessage.DHTLookupResult<String, FileDTO>(
//							lookupAnswer.getResult(), lookupAnswer.getKey(), lookupAnswer.getValue()));
//        	
//        	response.setNode(lookupAnswer.getNode());
//        	return response;
//        });
//    	
//		System.out.println("[ClientRetrieveService.handleClientLookupRequest] 3 node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey());
//
//        return new EmptyKademliaMessage<>();
//    }
//    
    
    // sync approach
//    protected DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> handleClientLookupRequest(
//    		DHTLookupKademliaMessage<BigInteger, NettyConnectionInfo, String> message) {	
//    	
//    	DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> response = null;
//
//    	DHTLookupKademliaMessage.DHTLookup<BigInteger, NettyConnectionInfo, String> data = message.getData(); 
//    	
//    	System.out.println("[ClientRetrieveService.handleClientLookupRequest 1]  node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey());
//
//    	LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer = handleClientLookup(this.dhtKademliaNode, this.dhtKademliaNode, data.getKey(), data.getCurrentTry());
//    	
//    	System.out.println("[ClientRetrieveService.handleClientLookupRequest] Data lookup at node "+ lookupAnswer.getNode().getId() +" "+lookupAnswer.getResult());
//    	
//    	response = new DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO>(
//    			new DHTLookupResultKademliaMessage.DHTLookupResult<String, FileDTO>(lookupAnswer.getResult(), lookupAnswer.getKey(), lookupAnswer.getValue()));
//    	response.setNode(lookupAnswer.getNode());
//
//    	return response;
//    }
    
//    @Override
//    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleClientLookupRequest(DHTLookupKademliaMessage<BigInteger, NettyConnectionInfo, String> message) {
//    	
//    	System.out.println("[ClientRetrieveService.handleClientLookupRequest 1]  node="+message.getNode().getId());
//
//    	this.handlerExecutorService.submit(() -> {
//
//    		DHTLookupKademliaMessage.DHTLookup<BigInteger, NettyConnectionInfo, String> data = message.getData();
//    		
//        	System.out.println("[ClientRetrieveService.handleClientLookupRequest 2]  node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey());
//    		
//            LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer = handleLookup(this.dhtKademliaNode, data.getRequester(), data.getKey(), data.getCurrentTry());
//            if (lookupAnswer.getResult().equals(LookupAnswer.Result.FAILED) || lookupAnswer.getResult().equals(LookupAnswer.Result.FOUND)){
////            	this.dhtKademliaNode.getMessageSender().sendAsyncMessage(this.dhtKademliaNode, data.getRequester(), new DHTLookupResultKademliaMessage<>(
////                        new DHTLookupResultKademliaMessage.DHTLookupResult<>(
////                                lookupAnswer.getResult(),
////                                data.getKey(),
////                                lookupAnswer.getValue()
////                        )
////                ));
//            }
//            
//        	System.out.println("[ClientRetrieveService.handleClientLookupRequest 3] Data lookup at node "+ lookupAnswer.getNode().getId() +" "+lookupAnswer.getResult());
//            
//        	DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> response = 
//        			new DHTLookupResultKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO>(
//        					new DHTLookupResultKademliaMessage.DHTLookupResult<String, FileDTO>(
//        							lookupAnswer.getResult(), lookupAnswer.getKey(), lookupAnswer.getValue()));
//        	
//        	response.setNode(lookupAnswer.getNode());
//        	
//        	return response;
//
//        });
//    	
//        return new EmptyKademliaMessage<>();
//    }
    	
    
//    protected LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> handleClientLookup(
//    		Node<BigInteger, NettyConnectionInfo> caller, 
//    		Node<BigInteger, NettyConnectionInfo> requester, 
//    		String key, int currentTry){
//
//    	LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO> lookupAnswer = null;
//    	long start = 0, end = 0;
//    	
//    	try {
//    		// node: key is the hash of the file name, already hashed by client
//    		lookupAnswer = this.dhtKademliaNode.lookup(key).get();
//    	}
//    	catch (Exception e) {
//    		// TO-DO
//    	}
//    	
//    	return lookupAnswer;
//    }  
}
