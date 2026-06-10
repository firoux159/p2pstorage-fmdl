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

/**
 * Answer to a "FIND_NODE" query. Contains the nodes closest to an id given
 * @param <I> Number type of node ID between supported types
 * @param <C> Your implementation of connection info
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class NodeStatusAnswer<I extends Number, C extends ConnectionInfo, V extends Serializable> extends Answer<I, C> {

	private static final long serialVersionUID = 1L;
	
	private String status;
	
    private Result result = Result.FAILED;

    public enum Result {
    	QUERIED, FAILED
    }
}
