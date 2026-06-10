package org.gbtc.storage.serialization;

import java.lang.reflect.Type;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import org.gbtc.storage.protocol.message.NodeWorkloadMessage;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;

public class NodeWorkloadDataDeserializer<ID extends Number, C extends ConnectionInfo> implements JsonDeserializer<NodeWorkloadMessage.NodeWorkload<ID, C>> {

    @Override
    public NodeWorkloadMessage.NodeWorkload<ID, C> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
    	NodeWorkloadMessage.NodeWorkload<ID, C> nodeWorkload = new NodeWorkloadMessage.NodeWorkload<>();
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        nodeWorkload.setRequester(jsonDeserializationContext.deserialize(jsonObject.getAsJsonObject("requester"), Node.class));
        return nodeWorkload;
    }

}
