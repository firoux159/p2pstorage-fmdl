package org.gbtc.storage.repository;

import lombok.*;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.List;

import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.external.ExternalNode;

@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Setter
public class FileDTO implements Serializable {
	private static final long serialVersionUID = 1234567L;
	
	private String name;
	private String description;
	private BigInteger fileId;
	private BigInteger previousId;
	private BigInteger nextId;
	private String externalNode;
	private List<String> names;
	
	//private Object file;
	private byte[] content;
	
	// field to record read time
	private long readLatency;
	
}
