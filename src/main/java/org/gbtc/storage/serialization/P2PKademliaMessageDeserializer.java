package org.gbtc.storage.serialization;

import java.io.Serializable;

import com.google.gson.reflect.TypeToken;

import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.protocol.message.*;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.protocol.message.*;
import io.ep2p.kademlia.serialization.gson.KademliaMessageDeserializer;

public class P2PKademliaMessageDeserializer<I extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> extends KademliaMessageDeserializer<I, C, K, V> {

	public P2PKademliaMessageDeserializer(Class<I> idClass) {
		super(idClass);
		
        this.registerMessageClass(P2PMessageType.DHT_EXTERNAL_STORE, DHTStoreKademliaMessage.class);
        this.registerMessageClass(P2PMessageType.DHT_EXTERNAL_STORE_RESULT, DHTStoreResultKademliaMessage.class);
        
        this.registerMessageClass(P2PMessageType.DHT_EXTERNAL_NEXT_STORE, DHTStoreKademliaMessage.class);
        this.registerMessageClass(P2PMessageType.DHT_EXTERNAL_NEXT_STORE_RESULT, DHTStoreResultKademliaMessage.class);
		
		this.registerMessageClass(P2PMessageType.NODE_STATUS, NodeStatusMessage.class);
		this.registerMessageClass(P2PMessageType.NODE_STATUS_RESULT, NodeStatusResultMessage.class);
		
		this.registerMessageClass(P2PMessageType.NODE_WORKLOAD, NodeWorkloadMessage.class);
		this.registerMessageClass(P2PMessageType.NODE_THROUGHPUT, NodeWorkloadMessage.class);
		this.registerMessageClass(P2PMessageType.NODE_WORKLOAD_RESULT, NodeWorkloadResultMessage.class);

		this.registerMessageClass(P2PMessageType.CLIENT_STORE_REQ, DHTStoreKademliaMessage.class);
		this.registerMessageClass(P2PMessageType.CLIENT_STORE_RES, DHTStoreResultKademliaMessage.class);
		
		this.registerMessageClass(P2PMessageType.CLIENT_RETRIEVE_REQ, DHTLookupKademliaMessage.class);
		this.registerMessageClass(P2PMessageType.CLIENT_RETRIEVE_RES, DHTLookupResultKademliaMessage.class);
		
		this.registerDataType(P2PMessageType.DHT_EXTERNAL_STORE, new TypeToken<DHTStoreKademliaMessage.DHTData<I, C, K, V>>(){}.getType());
		this.registerDataType(P2PMessageType.DHT_EXTERNAL_STORE_RESULT, new TypeToken<DHTStoreResultKademliaMessage.DHTStoreResult<K>>(){}.getType());
		
        this.registerDataType(P2PMessageType.DHT_EXTERNAL_NEXT_STORE, new TypeToken<DHTStoreKademliaMessage.DHTData<I, C, K, V>>(){}.getType());
        this.registerDataType(P2PMessageType.DHT_EXTERNAL_NEXT_STORE_RESULT, new TypeToken<DHTStoreResultKademliaMessage.DHTStoreResult<K>>(){}.getType());

		this.registerDataType(P2PMessageType.NODE_STATUS, new TypeToken<NodeStatusMessage.NodeStatus<I, C>>(){}.getType());
        this.registerDataType(P2PMessageType.NODE_STATUS_RESULT, new TypeToken<NodeStatusResultMessage.NodeStatusResult<V>>(){}.getType());
        
		this.registerDataType(P2PMessageType.NODE_WORKLOAD, new TypeToken<NodeWorkloadMessage.NodeWorkload<I, C>>(){}.getType());
		this.registerDataType(P2PMessageType.NODE_THROUGHPUT, new TypeToken<NodeWorkloadMessage.NodeWorkload<I, C>>(){}.getType());
		this.registerDataType(P2PMessageType.NODE_WORKLOAD_RESULT, new TypeToken<NodeWorkloadResultMessage.NodeWorkloadResult<V>>(){}.getType());

        this.registerDataType(P2PMessageType.CLIENT_STORE_REQ, new TypeToken<DHTStoreKademliaMessage.DHTData<I, C, K, V>>(){}.getType());
		this.registerDataType(P2PMessageType.CLIENT_STORE_RES, new TypeToken<DHTStoreResultKademliaMessage.DHTStoreResult<K>>(){}.getType());
		
		this.registerDataType(P2PMessageType.CLIENT_RETRIEVE_REQ, new TypeToken<DHTLookupKademliaMessage.DHTLookup<I, C, K>>(){}.getType());
		this.registerDataType(P2PMessageType.CLIENT_RETRIEVE_RES, new TypeToken<DHTLookupResultKademliaMessage.DHTLookupResult<K, V>>(){}.getType());

	}

}
