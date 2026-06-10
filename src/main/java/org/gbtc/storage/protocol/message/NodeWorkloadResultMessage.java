package org.gbtc.storage.protocol.message;

import java.io.Serializable;

import com.google.common.base.Objects;

import org.gbtc.storage.model.NodeWorkloadAnswer;
import org.gbtc.storage.protocol.P2PMessageType;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString(callSuper = true)
public class NodeWorkloadResultMessage<I extends Number, C extends ConnectionInfo, V extends Serializable> extends KademliaMessage<I, C, NodeWorkloadResultMessage.NodeWorkloadResult<V>> {

	public NodeWorkloadResultMessage(NodeWorkloadResult<V> data) {
        this();
        setData(data);
    }

    public NodeWorkloadResultMessage() {
        super(P2PMessageType.NODE_WORKLOAD_RESULT);
    }
    
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    public static class NodeWorkloadResult<V extends Serializable> implements Serializable{

    	private static final long serialVersionUID = 1L;
		
    	private V workload;
    	
        private NodeWorkloadAnswer.Result result;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeWorkloadResult<?> that = (NodeWorkloadResult<?>) o;
            return Objects.equal(getWorkload(), that.getWorkload()) && getResult() == that.getResult();
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getWorkload(), getResult());
        }
    }  

}
