package org.gbtc.storage.serialization;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.gbtc.storage.protocol.message.*;
import org.gbtc.storage.serialization.P2PKademliaMessageDeserializer;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.model.FindNodeAnswer;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.protocol.message.*;
import io.ep2p.kademlia.serialization.gson.DHTLookUpDataDeserializer;
import io.ep2p.kademlia.serialization.gson.DHTLookUpResultDeserializer;
import io.ep2p.kademlia.serialization.gson.DHTStoreDataDeserializer;
import io.ep2p.kademlia.serialization.gson.DHTStoreResultDataDataDeserializer;
import io.ep2p.kademlia.serialization.gson.ExternalNodeDeserializer;
import io.ep2p.kademlia.serialization.gson.ExternalNodeSerializer;
import io.ep2p.kademlia.serialization.gson.FindNodeAnswerDeserializer;
import io.ep2p.kademlia.serialization.gson.GsonFactory;
import io.ep2p.kademlia.serialization.gson.NodeDeserializer;
import io.ep2p.kademlia.serialization.gson.NodeSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;

public interface P2PGsonFactory {
    Gson gson();
    GsonBuilder gsonBuilder();

    @AllArgsConstructor
    @Getter
    class DefaultGsonFactory<ID extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> implements GsonFactory {

        private final Class<ID> idClass;
        private final Class<C> connectionInfoClass;
        private final Class<K> keyClass;
        private final Class<V> valueClass;

        @Override
        public GsonBuilder gsonBuilder(){
            GsonBuilder gsonBuilder = new GsonBuilder();
            return gsonBuilder
                    .enableComplexMapKeySerialization()
                    .serializeNulls()
                    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)

                    .registerTypeAdapter(KademliaMessage.class, new P2PKademliaMessageDeserializer<ID, C, K, V>(getIdClass()))
                    .registerTypeAdapter(DHTLookupKademliaMessage.DHTLookup.class, new DHTLookUpDataDeserializer<ID, C, K>(getKeyClass()))
                    .registerTypeAdapter(DHTLookupResultKademliaMessage.DHTLookupResult.class, new DHTLookUpResultDeserializer<K, V>(getKeyClass(), getValueClass()))
                    .registerTypeAdapter(DHTStoreKademliaMessage.DHTData.class, new DHTStoreDataDeserializer<ID, C, K, V>(getKeyClass(), getValueClass()))
                    .registerTypeAdapter(DHTStoreResultKademliaMessage.DHTStoreResult.class, new DHTStoreResultDataDataDeserializer<K>(getKeyClass()))
                    .registerTypeAdapter(ExternalNode.class, new ExternalNodeDeserializer<ID, C>(getIdClass(), getConnectionInfoClass()))
                    .registerTypeAdapter(FindNodeAnswer.class, new FindNodeAnswerDeserializer<ID, C>(getIdClass()))
//                    .registerTypeAdapter(Node.class, new NodeInstanceCreator())
                    .registerTypeAdapter(Node.class, new NodeSerializer<ID, C>())
                    .registerTypeAdapter(Node.class, new NodeDeserializer<ID, C>(getIdClass(), getConnectionInfoClass()))
                    .registerTypeAdapter(ExternalNode.class, new ExternalNodeSerializer<ID, C>())
                    .registerTypeAdapter(NodeStatusMessage.NodeStatus.class, new NodeStatusDataDeserializer<ID, C>())
                    .registerTypeAdapter(NodeStatusResultMessage.NodeStatusResult.class, new NodeStatusResultDataDeserializer<String>(String.class))
                    .registerTypeAdapter(NodeWorkloadMessage.NodeWorkload.class, new NodeWorkloadDataDeserializer<ID, C>())
                    .registerTypeAdapter(NodeWorkloadResultMessage.NodeWorkloadResult.class, new NodeWorkloadResultDataDeserializer<Double>(Double.class));
//                    .registerTypeAdapter(ClientStoreMessage.DHTData.class, new ClientStoreDataDeserializer<ID, C, K, V>(getKeyClass(), getValueClass()))
//                    .registerTypeAdapter(ClientStoreResultMessage.DHTStoreResult.class, new ClientStoreResultDataDeserializer<K>(getKeyClass()));
        }

        @Override
        public Gson gson() {
            return gsonBuilder().create();
        }
    }

}
