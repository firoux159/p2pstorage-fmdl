package org.gbtc.storage.services;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;

import org.gbtc.storage.model.NodeStatusAnswer;
import org.gbtc.storage.model.NodeWorkloadAnswer;
import org.gbtc.storage.node.P2PNode;
import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.protocol.message.NodeStatusMessage;
import org.gbtc.storage.protocol.message.NodeStatusResultMessage;
import org.gbtc.storage.protocol.message.NodeWorkloadMessage;
import org.gbtc.storage.protocol.message.NodeWorkloadResultMessage;
import org.gbtc.storage.repository.FileDTO;
import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.handler.MessageHandler;
import io.ep2p.kademlia.protocol.message.DHTLookupKademliaMessage;
import io.ep2p.kademlia.protocol.message.DHTLookupResultKademliaMessage;
import io.ep2p.kademlia.protocol.message.EmptyKademliaMessage;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NodeStatusService implements MessageHandler<BigInteger, NettyConnectionInfo> {
	
	protected final Map<BigInteger, List<CompletableFuture<NodeStatusAnswer<BigInteger, NettyConnectionInfo, Double>>>> nodeStatusFutureMap = new ConcurrentHashMap<>();
	
    protected final DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode;
    protected final ExecutorService handlerExecutorService;

    public NodeStatusService(
            DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode,
            ExecutorService executorService
    ) {
        this.dhtKademliaNode = dhtKademliaNode;
        this.handlerExecutorService = executorService;
    }
    
    public KademliaMessage handle(KademliaNodeAPI kademliaNode, KademliaMessage message) {

    	switch (message.getType()) {
    	
          case P2PMessageType.NODE_STATUS :
              if (!(message instanceof NodeStatusMessage))
                  throw new IllegalArgumentException("Cant handle message. Required: NodeStatusMessage");
              //return handleNodeStatusRequest((NodeStatusMessage<BigInteger, NettyConnectionInfo>) message);
              return handleNodeStatusRequest((NodeStatusMessage<BigInteger, NettyConnectionInfo>) message);
              
          case P2PMessageType.NODE_STATUS_RESULT:
              if (!(message instanceof NodeStatusResultMessage))
                  throw new IllegalArgumentException("Cant handle message. Required: NodeStatusResultMessage");
              return handleNodeStatusResult((NodeStatusResultMessage<BigInteger, NettyConnectionInfo, Double>) message);

          default:
              throw new IllegalArgumentException("message param is not supported");
      }
    }
    
    
    // TO-DO: this does not work if using async approach
    // for now, just use sync. To explore further
//    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleNodeStatusRequest(NodeStatusMessage<BigInteger, NettyConnectionInfo> message) {
//    	 
//    	NodeStatusMessage.NodeStatus<BigInteger, NettyConnectionInfo> data = 
//    			(NodeStatusMessage.NodeStatus<BigInteger, NettyConnectionInfo>) message.getData();
//		
//    	// Node: data.getRequester() is used to send response message back to the requester
//    	NodeStatusAnswer<BigInteger, NettyConnectionInfo, Double> nodeStatusAnswer = handleNodeStatus(this.dhtKademliaNode, this.dhtKademliaNode);
//
//    	if (nodeStatusAnswer.getResult().equals(NodeStatusAnswer.Result.QUERIED)) {
//        	
//        	System.out.println("[NodeStatusService].handleNodeStatusRequest " + nodeStatusAnswer.getNode().getConnectionInfo().getPort()+", "+nodeStatusAnswer.getResult()+", "+nodeStatusAnswer.getStatus() );
//        	
//            this.dhtKademliaNode.getMessageSender().sendAsyncMessage(this.dhtKademliaNode, data.getRequester(), 
//            		new NodeStatusResultMessage<>(
//            				new NodeStatusResultMessage.NodeStatusResult<Double>(
//            						nodeStatusAnswer.getStatus(),
//            						nodeStatusAnswer.getResult()))
//            );
//        }
//
//        return new EmptyKademliaMessage<>();
//    }
    

    // TO-DO: added key to the message
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleNodeStatusResult(NodeStatusResultMessage<BigInteger, NettyConnectionInfo, Double> message) {

    	// currently do nothing
    	// TO-DO: explore
        return new EmptyKademliaMessage<>();
    }
    
    // TO-DO: added key to the message
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleNodeWorkloadResult(NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double> message) {

    	// currently do nothing
    	// TO-DO: explore
        return new EmptyKademliaMessage<>();
    }

    
//    // TO-DO: added key to the message
//    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleNodeStatusResult(NodeStatusResultMessage<BigInteger, NettyConnectionInfo, Double> message) {
//
//    	NodeStatusResultMessage.NodeStatusResult<Double> data = message.getData();
//        List<CompletableFuture<NodeStatusAnswer<BigInteger, NettyConnectionInfo, Double>>> futuresList = this.nodeStatusFutureMap.get(message.getNode().getId());
//        if (futuresList != null){
//        	NodeStatusAnswer<BigInteger, NettyConnectionInfo, Double> answer = new NodeStatusAnswer<>();
//        	answer.setResult(data.getResult());
//        	answer.setStatus(data.getStatus());
//            answer.setNode(message.getNode());
//
//            for (CompletableFuture<NodeStatusAnswer<BigInteger, NettyConnectionInfo, Double>> future : futuresList) {
//                future.complete(answer);
//            }
//        }
//        
//        return new EmptyKademliaMessage<>();
//    }
    
    
    protected NodeStatusResultMessage<BigInteger, NettyConnectionInfo, String> handleNodeStatusRequest(NodeStatusMessage<BigInteger, NettyConnectionInfo> nodeStatusMessage) {
    	
    	NodeStatusResultMessage<BigInteger, NettyConnectionInfo, String> response = null;
    	
    	NodeStatusMessage.NodeStatus<BigInteger, NettyConnectionInfo> data = 
    			(NodeStatusMessage.NodeStatus<BigInteger, NettyConnectionInfo>) nodeStatusMessage.getData();
    	
    	// Node: data.getRequester() is used to send response message back to the requester
    	// for this scenario, it is not being used because we are using sync approach
    	
    	NodeStatusAnswer<BigInteger, NettyConnectionInfo, String> nodeStatusAnswer = handleNodeStatus(this.dhtKademliaNode, this.dhtKademliaNode);
    	
        if (nodeStatusAnswer.getResult().equals(NodeStatusAnswer.Result.QUERIED)) {
        	
        	log.debug("[NodeStatusService].handleNodeStatusRequest " + nodeStatusAnswer.getNode().getConnectionInfo().getPort()+", "+nodeStatusAnswer.getResult()+", "+nodeStatusAnswer.getStatus() );
        	
        	response = new NodeStatusResultMessage<>(new NodeStatusResultMessage.NodeStatusResult<String>(nodeStatusAnswer.getStatus(), NodeStatusAnswer.Result.QUERIED));
        	
        	response.setNode(nodeStatusAnswer.getNode());

        	return response;
        }
        
        response = new NodeStatusResultMessage<>(new NodeStatusResultMessage.NodeStatusResult<String>(nodeStatusAnswer.getStatus(), NodeStatusAnswer.Result.FAILED));
    	response.setNode(nodeStatusAnswer.getNode());

    	return response;
    }
    
    
    protected NodeStatusAnswer<BigInteger, NettyConnectionInfo, String> handleNodeStatus(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester){

    	NodeStatusAnswer<BigInteger, NettyConnectionInfo, String> nodeStatusAnswer = new NodeStatusAnswer<BigInteger, NettyConnectionInfo, String>();
    	
    	String status = ((P2PNode) this.dhtKademliaNode).getWorkloadStatus();
    	
    	nodeStatusAnswer.setAlive(true);
    	nodeStatusAnswer.setNode(this.dhtKademliaNode);
    	nodeStatusAnswer.setStatus(status);
    	nodeStatusAnswer.setResult(NodeStatusAnswer.Result.QUERIED);

    	return nodeStatusAnswer;
    }
    
    
    protected NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double> handleNodeWorkloadRequest(NodeWorkloadMessage<BigInteger, NettyConnectionInfo> nodeWorkloadMessage) {
    	
    	NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double> response = null;
    	
    	NodeWorkloadMessage.NodeWorkload<BigInteger, NettyConnectionInfo> data = 
    			(NodeWorkloadMessage.NodeWorkload<BigInteger, NettyConnectionInfo>) nodeWorkloadMessage.getData();
    	
    	// Node: data.getRequester() is used to send response message back to the requester
    	// for this scenario, it is not being used because we are using sync approach
    	
    	NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double> nodeWorkloadAnswer = handleNodeWorkload(this.dhtKademliaNode, this.dhtKademliaNode);
    	
        if (nodeWorkloadAnswer.getResult().equals(NodeWorkloadAnswer.Result.QUERIED)) {
        	
        	log.debug("[NodeStatusService].handleNodeWorkloadRequest " + nodeWorkloadAnswer.getNode().getConnectionInfo().getPort()+", "+nodeWorkloadAnswer.getResult()+", "+nodeWorkloadAnswer.getWorkload());
        	
        	response = new NodeWorkloadResultMessage<>(new NodeWorkloadResultMessage.NodeWorkloadResult<Double>(nodeWorkloadAnswer.getWorkload(), NodeWorkloadAnswer.Result.QUERIED));
        	
        	response.setNode(nodeWorkloadAnswer.getNode());

        	return response;
        }
        
        response = new NodeWorkloadResultMessage<>(new NodeWorkloadResultMessage.NodeWorkloadResult<Double>(nodeWorkloadAnswer.getWorkload(), NodeWorkloadAnswer.Result.FAILED));
        
    	response.setNode(nodeWorkloadAnswer.getNode());

    	return response;
    }
    
    
    protected NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double> handleNodeWorkload(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester){

    	NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double> nodeWorkloadAnswer = new NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double>();
    	
    	Double workload = ((P2PNode) this.dhtKademliaNode).estimateWorkload();
    	
    	nodeWorkloadAnswer.setAlive(true);
    	nodeWorkloadAnswer.setNode(this.dhtKademliaNode);
    	nodeWorkloadAnswer.setWorkload(workload);
    	nodeWorkloadAnswer.setResult(NodeWorkloadAnswer.Result.QUERIED);

    	return nodeWorkloadAnswer;
    }
    
    public void cleanUp(){
        this.nodeStatusFutureMap.forEach((k, completableFutures) -> completableFutures.forEach(answerCompletableFuture -> answerCompletableFuture.cancel(true)));
        this.nodeStatusFutureMap.clear();
    }
    
}
