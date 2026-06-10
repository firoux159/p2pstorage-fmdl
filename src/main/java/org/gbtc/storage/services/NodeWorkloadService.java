package org.gbtc.storage.services;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.gbtc.storage.model.NodeWorkloadAnswer;
import org.gbtc.storage.node.P2PNode;
import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.protocol.message.NodeWorkloadMessage;
import org.gbtc.storage.protocol.message.NodeWorkloadResultMessage;
import org.gbtc.storage.repository.FileDTO;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.handler.MessageHandler;
import io.ep2p.kademlia.protocol.message.EmptyKademliaMessage;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NodeWorkloadService implements MessageHandler<BigInteger, NettyConnectionInfo> {
	
	protected final Map<BigInteger, List<CompletableFuture<NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double>>>> nodeWorkloadFutureMap = new ConcurrentHashMap<>();
	
    protected final DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode;
    protected final ExecutorService handlerExecutorService;

    public NodeWorkloadService(
            DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> dhtKademliaNode,
            ExecutorService executorService
    ) {
        this.dhtKademliaNode = dhtKademliaNode;
        this.handlerExecutorService = executorService;
    }
    
    public KademliaMessage handle(KademliaNodeAPI kademliaNode, KademliaMessage message) {

    	switch (message.getType()) {
    	
          case P2PMessageType.NODE_WORKLOAD :
              if (!(message instanceof NodeWorkloadMessage))
                  throw new IllegalArgumentException("Cant handle message. Required: NodeWorkloadMessage");
              //return handleNodeStatusRequest((NodeStatusMessage<BigInteger, NettyConnectionInfo>) message);
              return handleNodeWorkloadRequest((NodeWorkloadMessage<BigInteger, NettyConnectionInfo>) message);
              
          case P2PMessageType.NODE_THROUGHPUT :
              if (!(message instanceof NodeWorkloadMessage))
                  throw new IllegalArgumentException("Cant handle message. Required: NodeWorkloadMessage");
              //return handleNodeStatusRequest((NodeStatusMessage<BigInteger, NettyConnectionInfo>) message);
              return handleNodeThroughputRequest((NodeWorkloadMessage<BigInteger, NettyConnectionInfo>) message);
              
          case P2PMessageType.NODE_WORKLOAD_RESULT:
              if (!(message instanceof NodeWorkloadResultMessage))
                  throw new IllegalArgumentException("Cant handle message. Required: NodeStatusResultMessage");
              return handleNodeWorkloadResult((NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double>) message);

          default:
              throw new IllegalArgumentException("message param is not supported");
      }
    }
    
    
    // TO-DO: added key to the message
    protected EmptyKademliaMessage<BigInteger, NettyConnectionInfo> handleNodeWorkloadResult(NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double> message) {

    	// currently do nothing
    	// TO-DO: explore
        return new EmptyKademliaMessage<>();
    }

    
    protected NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double> handleNodeWorkloadRequest(NodeWorkloadMessage<BigInteger, NettyConnectionInfo> nodeWorkloadMessage) {
    	
    	NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double> response = null;
    	
    	NodeWorkloadMessage.NodeWorkload<BigInteger, NettyConnectionInfo> data = 
    			(NodeWorkloadMessage.NodeWorkload<BigInteger, NettyConnectionInfo>) nodeWorkloadMessage.getData();
    	
    	// Node: data.getRequester() is used to send response message back to the requester
    	// for this scenario, it is not being used because we are using sync approach
    	
    	NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double> nodeWorkloadAnswer = handleNodeWorkload(this.dhtKademliaNode, this.dhtKademliaNode);
    	
        if (nodeWorkloadAnswer.getResult().equals(NodeWorkloadAnswer.Result.QUERIED)) {
        	
        	log.debug("[handleNodeWorkloadRequest] " + nodeWorkloadAnswer.getNode().getConnectionInfo().getPort()+", "+nodeWorkloadAnswer.getResult()+", "+nodeWorkloadAnswer.getWorkload());
        	
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
    
    protected NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double> handleNodeThroughputRequest(NodeWorkloadMessage<BigInteger, NettyConnectionInfo> nodeWorkloadMessage) {
    	
    	NodeWorkloadResultMessage<BigInteger, NettyConnectionInfo, Double> response = null;
    	
    	NodeWorkloadMessage.NodeWorkload<BigInteger, NettyConnectionInfo> data = 
    			(NodeWorkloadMessage.NodeWorkload<BigInteger, NettyConnectionInfo>) nodeWorkloadMessage.getData();
    	
    	// Node: data.getRequester() is used to send response message back to the requester
    	// for this scenario, it is not being used because we are using sync approach
    	
    	NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double> nodeWorkloadAnswer = handleNodeThroughput(this.dhtKademliaNode, this.dhtKademliaNode);
    	
        if (nodeWorkloadAnswer.getResult().equals(NodeWorkloadAnswer.Result.QUERIED)) {
        	
        	log.debug("[handleNodeThroughputRequest] " + nodeWorkloadAnswer.getNode().getConnectionInfo().getPort()+", "+nodeWorkloadAnswer.getResult()+", "+nodeWorkloadAnswer.getWorkload());
        	
        	response = new NodeWorkloadResultMessage<>(new NodeWorkloadResultMessage.NodeWorkloadResult<Double>(nodeWorkloadAnswer.getWorkload(), NodeWorkloadAnswer.Result.QUERIED));
        	
        	response.setNode(nodeWorkloadAnswer.getNode());

        	return response;
        }
        
        response = new NodeWorkloadResultMessage<>(new NodeWorkloadResultMessage.NodeWorkloadResult<Double>(nodeWorkloadAnswer.getWorkload(), NodeWorkloadAnswer.Result.FAILED));
        
    	response.setNode(nodeWorkloadAnswer.getNode());

    	return response;
    }
    
    protected NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double> handleNodeThroughput(
    		Node<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> requester){

    	NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double> nodeWorkloadAnswer = new NodeWorkloadAnswer<BigInteger, NettyConnectionInfo, Double>();
    	
    	Double throughput = ((P2PNode) this.dhtKademliaNode).getThroughput();
    	
    	nodeWorkloadAnswer.setAlive(true);
    	nodeWorkloadAnswer.setNode(this.dhtKademliaNode);
    	nodeWorkloadAnswer.setWorkload(throughput);
    	nodeWorkloadAnswer.setResult(NodeWorkloadAnswer.Result.QUERIED);

    	return nodeWorkloadAnswer;
    }
    
    public void cleanUp(){
        this.nodeWorkloadFutureMap.forEach((k, completableFutures) -> completableFutures.forEach(answerCompletableFuture -> answerCompletableFuture.cancel(true)));
        this.nodeWorkloadFutureMap.clear();
    }

}
