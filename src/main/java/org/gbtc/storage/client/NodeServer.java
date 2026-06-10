package org.gbtc.storage.client;

import java.math.BigInteger;

import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.Node;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NodeServer implements Node<BigInteger, NettyConnectionInfo> {
	
	private static final long serialVersionUID = 1L;
	
	@Setter
	@Getter
	private String name;
	
	private BigInteger id;
	private NettyConnectionInfo connectionInfo;
	
	public NodeServer() {
	}
	
	public NodeServer(BigInteger id, NettyConnectionInfo connectionInfo) {
		this.id = id;
		this.connectionInfo = connectionInfo;
	}
}
