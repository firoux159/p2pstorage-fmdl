package org.gbtc.storage.model;

import java.io.Serializable;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.model.Answer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class NodeWorkloadAnswer<I extends Number, C extends ConnectionInfo, V extends Serializable> extends Answer<I, C> {
	private static final long serialVersionUID = 1L;
	
	private V workload;
	
    private Result result = Result.FAILED;

    public enum Result {
    	QUERIED, FAILED
    }

}
