package org.gbtc.storage.protocol.message;

import java.io.Serializable;

import com.google.common.base.Objects;

import org.gbtc.storage.protocol.P2PMessageType;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString(callSuper = true)
public class NodeWorkloadMessage <I extends Number, C extends ConnectionInfo> extends KademliaMessage<I, C, NodeWorkloadMessage.NodeWorkload<I, C>> {
	
    public NodeWorkloadMessage(NodeWorkload<I, C> data) {
        this();
        setData(data);
    }

	public NodeWorkloadMessage() {
        super(P2PMessageType.NODE_WORKLOAD);
    }
	
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    public static class NodeWorkload<I extends Number, C extends ConnectionInfo> implements Serializable{
		
    	private static final long serialVersionUID = 1L;
		
    	private Node<I, C> requester;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeWorkload<?, ?> statusData = (NodeWorkload<?, ?>) o;
            return Objects.equal(getRequester(), statusData.getRequester());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getRequester());
        }
    }	

}
