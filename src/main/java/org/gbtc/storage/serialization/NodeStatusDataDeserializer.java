package org.gbtc.storage.serialization;

import java.io.Serializable;
import java.lang.reflect.Type;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import org.gbtc.storage.protocol.message.*;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.Node;


public class NodeStatusDataDeserializer<ID extends Number, C extends ConnectionInfo> implements JsonDeserializer<NodeStatusMessage.NodeStatus<ID, C>> {

    @Override
    public NodeStatusMessage.NodeStatus<ID, C> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
    	NodeStatusMessage.NodeStatus<ID, C> nodeStatus = new NodeStatusMessage.NodeStatus<>();
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        nodeStatus.setRequester(jsonDeserializationContext.deserialize(jsonObject.getAsJsonObject("requester"), Node.class));
        return nodeStatus;
    }
}
