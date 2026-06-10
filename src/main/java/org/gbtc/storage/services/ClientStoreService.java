package org.gbtc.storage.services;

import java.io.File;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;

import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.repository.FileDTO;
import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.message.DHTStoreKademliaMessage;
import io.ep2p.kademlia.protocol.message.DHTStoreResultKademliaMessage;
import io.ep2p.kademlia.protocol.message.EmptyKademliaMessage;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.services.PushingDHTStoreService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientStoreService extends PushingDHTStoreService<BigInteger, NettyConnectionInfo, String, FileDTO> {

//	private static final Logger logger = LogManager.getLogger(ClientStoreService.class);
	
	public ClientStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode, ExecutorService executorService) {
    	super(dhtKademliaNode, executorService);
    }
    
    
    public KademliaMessage handle(KademliaNodeAPI kademliaNode, KademliaMessage message) {
    	
    	switch (message.getType()) {
//    		case P2PMessageType.CLIENT_STORE_REQ :
//    			if (!(message instanceof DHTStoreKademliaMessage))
//    				throw new IllegalArgumentException("Cant handle message. Required: DHTStoreKademliaMessage");
//    			return handleClientStoreRequest((DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO>) message);
    			
        	case P2PMessageType.DHT_STORE_RESULT:
        		if (!(message instanceof DHTStoreResultKademliaMessage))
        			throw new IllegalArgumentException("Cant handle message. Required: DHTStoreResultKademliaMessage");
        		return handleClientStoreResult((DHTStoreResultKademliaMessage) message);
    		
    		default:
    			throw new IllegalArgumentException("message param is not supported");
    	}
    }
    
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleClientStoreResult(DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String> message) {
        DHTStoreResultKademliaMessage.DHTStoreResult<String> data = message.getData();
        this.finalizeStoreResult(data.getKey(), data.getResult(), message.getNode());
        
        System.out.println("[P2PStoreService.clientStore] handleClientStoreResult keys ="+storeFutureMap.keySet());
        
        return new EmptyKademliaMessage<>();
    }
    
    
    @Override
    protected void finalizeStoreResult(String key, StoreAnswer.Result result, Node<BigInteger, NettyConnectionInfo> node) {
        CompletableFuture<StoreAnswer<BigInteger, NettyConnectionInfo, String>> completableFuture = storeFutureMap.get(key);
        if (completableFuture != null){
            completableFuture.complete(getNewStoreAnswer(key, result, node));
        }
    }    

    
    // sync approach, doesnt work - TO-DO: explore
//    protected DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String> handleClientStoreRequest(
//    		DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message){
//    	
//    	DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String> response = null;
//
//    	DHTStoreKademliaMessage.DHTData<BigInteger, NettyConnectionInfo, String, FileDTO> data = message.getData();
//    	
//    	System.out.println("[ClientStoreService.handleClientStoreRequest] 1 node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey()+", value="+data.getValue().getName());
//    	
//    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleClientStore(this.dhtKademliaNode, this.dhtKademliaNode, data.getKey(), data.getValue());
//    	
//    	System.out.println("[ClientStoreService.handleClientStoreRequest] Data store at node="+storeAnswer.getNode().getId()+" "+storeAnswer.getResult());
//    	
//    	response = new DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String>(
//    			new DHTStoreResultKademliaMessage.DHTStoreResult<>(data.getKey(), storeAnswer.getResult()));
//    	response.setNode(storeAnswer.getNode());
//
//    	return response;
//    	
//    }

//    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleClientStoreRequest(
//    		DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> message) {
//    	
//    	DHTStoreKademliaMessage.DHTData<BigInteger, NettyConnectionInfo, String, FileDTO> data = message.getData(); 
//    	
//		System.out.println("[ClientStoreService.handleClientStoreRequest] 1 node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey()+", value="+data.getValue().getName());
//
//    	this.handlerExecutorService.submit(() -> {
//    		
//    		StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleClientStore(this.dhtKademliaNode, data.getRequester(), data.getKey(), data.getValue());
//            if (storeAnswer.getResult().equals(StoreAnswer.Result.STORED)) {
//            	
//            	System.out.println("[ClientStoreService.handleClientStoreRequest] Data store at node="+storeAnswer.getNode().getId()+" "+storeAnswer.getResult());
//
////                this.dhtKademliaNode.getMessageSender().sendAsyncMessage(
////                        this.dhtKademliaNode,
////                        data.getRequester(),
////                        new DHTStoreResultKademliaMessage<>(
////                                new DHTStoreResultKademliaMessage.DHTStoreResult<>(data.getKey(), StoreAnswer.Result.STORED)
////                        )
////                );
//            }
//            
//            DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String> response = 
//            		new DHTStoreResultKademliaMessage<BigInteger, NettyConnectionInfo, String>(
//                			new DHTStoreResultKademliaMessage.DHTStoreResult<>(data.getKey(), storeAnswer.getResult()));
//            
//            response.setNode(storeAnswer.getNode());
//            return response;
//        });
//    	
//		System.out.println("[ClientStoreService.handleClientStoreRequest] 3 node="+message.getNode().getId()+", requester="+data.getRequester().getId()+", key="+data.getKey()+", value="+data.getValue().getName());
//
//        return new EmptyKademliaMessage<>();
//    }
//    
//    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> handleClientStore(
//    		Node<BigInteger, NettyConnectionInfo> caller, 
//    		Node<BigInteger, NettyConnectionInfo> requester, String key, FileDTO value){
//
//    	StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = null;
//    	
//    	// read file
//    	File file = new File("data/input", value.getName());
//    	double size = (double) file.length() / 1024; // size in Kilobytes
//    	
//    	logger.debug("File (name, size) : "+file.getName()+", "+size/1024);
//    	
//    	String innerKey = ""+file.getName().hashCode();
//		FileDTO innerValue = new FileDTO();
//		innerValue.setName(file.getName());
//
//    	try {
//    		//System.out.println("handleClientStore 1");
//    		
//    		innerValue.setContent(Files.readAllBytes(file.toPath()));
//    		storeAnswer = this.dhtKademliaNode.store(innerKey, innerValue).get();
//    		
//    		//System.out.println("handleClientStore 2");
//    		
//    		//System.out.println("[ClientStoreService].handleClientStore " + storeAnswer.getNode().getConnectionInfo().getPort()+", "+storeAnswer.getResult());
//    		
//    		return storeAnswer;
//    	}
//    	catch (Exception e) {
//    		// TO-DO
//    		e.printStackTrace();
//    	}
//    	
//        return getNewStoreAnswer(key, StoreAnswer.Result.STORED, this.dhtKademliaNode);
//    }
    
    
    public Future<StoreAnswer<BigInteger, NettyConnectionInfo, String>> clientStore(Node<BigInteger, NettyConnectionInfo> receiver, String key, FileDTO value) {

    	CompletableFuture<StoreAnswer<BigInteger, NettyConnectionInfo, String>> completableFuture = new CompletableFuture<>();
    	
    	System.out.println("[P2PStoreService.clientStore] storeFutureMap keys (BEFORE)="+storeFutureMap.keySet());
       
    	storeFutureMap.computeIfAbsent(key, k -> {
            
    		completableFuture.whenComplete((a, t) -> storeFutureMap.remove(key));
            
    		// this.dhtKademliaNode = CLIENT node
    		StoreAnswer<BigInteger, NettyConnectionInfo, String> storeAnswer = handleClientStore(this.dhtKademliaNode, receiver, key, value);
            
    		if (storeAnswer.getResult().equals(StoreAnswer.Result.STORED) || storeAnswer.getResult().equals(StoreAnswer.Result.FAILED)){
                completableFuture.complete(storeAnswer);
                
                System.out.println("[ClientStoreService.clientStore 1]");
                
                return null;
            }
            
            System.out.println("[ClientStoreService.clientStore 2]");
            
            return completableFuture;
        });
    	
        System.out.println("[P2PStoreService.clientStore] storeFutureMap keys (AFTER)="+storeFutureMap.keySet());
        
        return completableFuture;
    }


    protected StoreAnswer<BigInteger, NettyConnectionInfo, String> handleClientStore(
    		Node<BigInteger, NettyConnectionInfo> requester, 
    		Node<BigInteger, NettyConnectionInfo> receiver, 
    		String key, FileDTO value){
        
    	log.info(String.format("[ClientStoreService.handleClientStore 1] Sending data %s (key=%s) to node %s for store", value.getName(), key, receiver.getId()));

    	
        DHTStoreKademliaMessage<BigInteger, NettyConnectionInfo, String, FileDTO> storeMessage = 
        		new DHTStoreKademliaMessage<>(new DHTStoreKademliaMessage.DHTData<>(requester, key, value));
        // override the message type to inform the server that this is a client message (not from other node/server)
        storeMessage.setType(P2PMessageType.CLIENT_STORE_REQ);
        
    	KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> response = this.dhtKademliaNode.getMessageSender().sendMessage(
                this.dhtKademliaNode,
                receiver,
                storeMessage
        );
    	
//    	KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> response = this.dhtKademliaNode.getMessageSender().sendMessage(
//                this.dhtKademliaNode,
//                receiver,
//                new DHTStoreKademliaMessage<>(
//                        new DHTStoreKademliaMessage.DHTData<>(requester, key, value)
//                )
//        );
        if (response.isAlive()){
        	log.info(String.format("[ClientStoreService.handleClientStore 2] return message PASSED to caller"));
        	
        	return getNewStoreAnswer(key, StoreAnswer.Result.PASSED, requester);
        }
        
        log.info(String.format("[ClientStoreService.handleClientStore 3] return message FAILED to caller"));
        
        return getNewStoreAnswer(key, StoreAnswer.Result.FAILED, requester);
    }    

}
