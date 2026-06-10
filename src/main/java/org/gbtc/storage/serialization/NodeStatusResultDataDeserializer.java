package org.gbtc.storage.serialization;

import java.io.Serializable;
import java.lang.reflect.Type;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import org.gbtc.storage.model.NodeStatusAnswer;
import org.gbtc.storage.protocol.message.NodeStatusResultMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class NodeStatusResultDataDeserializer<V extends Serializable> implements JsonDeserializer<NodeStatusResultMessage.NodeStatusResult<V>> {

    private final Class<V> statusClass;

    @Override
    public NodeStatusResultMessage.NodeStatusResult<V> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
    	NodeStatusResultMessage.NodeStatusResult<V> nodeStatusResult = new NodeStatusResultMessage.NodeStatusResult<>();
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        nodeStatusResult.setStatus(jsonDeserializationContext.deserialize(jsonObject.get("status"), getStatusClass()));

        nodeStatusResult.setResult(NodeStatusAnswer.Result.valueOf(jsonObject.get("result").getAsString()));
        
        return nodeStatusResult;
    }
}
