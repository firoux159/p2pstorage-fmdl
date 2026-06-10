package org.gbtc.storage.protocol.message;

import java.io.Serializable;

import com.google.common.base.Objects;

import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.protocol.message.DHTStoreExternalNextKademliaMessage.DHTData;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

public class DHTStoreExternalKademliaMessage<I extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> extends KademliaMessage<I, C, DHTStoreExternalKademliaMessage.DHTData<I, C, K, V>> {

    public DHTStoreExternalKademliaMessage(DHTData<I, C, K, V> data) {
        this();
        setData(data);
    }

    public DHTStoreExternalKademliaMessage() {
        super(P2PMessageType.DHT_EXTERNAL_STORE);
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    public static class DHTData<I extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> implements Serializable{
        private Node<I, C> requester;
        private K key;
        private V value;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DHTData<?, ?, ?, ?> dhtData = (DHTData<?, ?, ?, ?>) o;
            return Objects.equal(getRequester(), dhtData.getRequester()) && Objects.equal(getKey(), dhtData.getKey()) && Objects.equal(getValue(), dhtData.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getRequester(), getKey(), getValue());
        }
    }
}
