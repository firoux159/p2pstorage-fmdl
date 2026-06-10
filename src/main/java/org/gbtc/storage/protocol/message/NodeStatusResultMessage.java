package org.gbtc.storage.protocol.message;

import java.io.Serializable;

import com.google.common.base.Objects;

import org.gbtc.storage.model.NodeStatusAnswer;
import org.gbtc.storage.protocol.P2PMessageType;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString(callSuper = true)
public class NodeStatusResultMessage<I extends Number, C extends ConnectionInfo, V extends Serializable> extends KademliaMessage<I, C, NodeStatusResultMessage.NodeStatusResult<V>> {

	public NodeStatusResultMessage(NodeStatusResult<V> data) {
        this();
        setData(data);
    }

    public NodeStatusResultMessage() {
        super(P2PMessageType.NODE_STATUS_RESULT);
    }
    
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    public static class NodeStatusResult<V extends Serializable> implements Serializable{

    	private static final long serialVersionUID = 1L;
		
    	private V status;
    	
        private NodeStatusAnswer.Result result;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeStatusResult<?> that = (NodeStatusResult<?>) o;
            return Objects.equal(getStatus(), that.getStatus()) && getResult() == that.getResult();
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getStatus(), getResult());
        }
    }    
}
