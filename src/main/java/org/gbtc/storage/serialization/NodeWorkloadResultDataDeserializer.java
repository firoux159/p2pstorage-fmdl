package org.gbtc.storage.serialization;

import java.io.Serializable;
import java.lang.reflect.Type;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import org.gbtc.storage.model.NodeWorkloadAnswer;
import org.gbtc.storage.protocol.message.NodeWorkloadResultMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class NodeWorkloadResultDataDeserializer<V extends Serializable> implements JsonDeserializer<NodeWorkloadResultMessage.NodeWorkloadResult<V>> {

	private final Class<V> workloadClass;

    @Override
    public NodeWorkloadResultMessage.NodeWorkloadResult<V> deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
    	NodeWorkloadResultMessage.NodeWorkloadResult<V> nodeWorkloadResult = new NodeWorkloadResultMessage.NodeWorkloadResult<>();
 
    	JsonObject jsonObject = jsonElement.getAsJsonObject();
    	nodeWorkloadResult.setWorkload(jsonDeserializationContext.deserialize(jsonObject.get("workload"), getWorkloadClass()));
    	nodeWorkloadResult.setResult(NodeWorkloadAnswer.Result.valueOf(jsonObject.get("result").getAsString()));
        
        return nodeWorkloadResult;
    }

}
