package org.gbtc.storage.node;

import java.math.BigInteger;

import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.Node;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class P2PExternalNode implements Node<BigInteger, NettyConnectionInfo> {
    private BigInteger id;
	private NettyConnectionInfo connectionInfo;
	
	private Double latency = 0.0;
	private Double workload = 0.0;
}
