package org.gbtc.storage.node.builder;

import java.math.BigInteger;

import org.gbtc.storage.P2PKeyHashGenerator;
import org.gbtc.storage.P2PNodeSettings;
import org.gbtc.storage.node.P2PNode;
import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.repository.FileDTO;
import org.gbtc.storage.repository.FileRepository;
import org.gbtc.storage.services.NodeStatusService;
import io.ep2p.kademlia.connection.MessageSender;
import io.ep2p.kademlia.netty.builder.NettyKademliaDHTNodeBuilder;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.builder.DHTKademliaNodeBuilder;
import io.ep2p.kademlia.protocol.MessageType;
import io.ep2p.kademlia.table.Bucket;
import io.ep2p.kademlia.table.RoutingTable;


public class P2PNodeBuilder extends DHTKademliaNodeBuilder<BigInteger, NettyConnectionInfo, String, FileDTO> {
	
	public P2PNodeBuilder(BigInteger id, NettyConnectionInfo connectionInfo, 
			RoutingTable<BigInteger, NettyConnectionInfo, Bucket<BigInteger, NettyConnectionInfo>> routingTable, 
			MessageSender<BigInteger, NettyConnectionInfo> messageSender, 
			P2PKeyHashGenerator keyHashGenerator, FileRepository kademliaRepository, P2PNodeSettings nodeSettings) {
		
		super(id, connectionInfo, routingTable, messageSender, keyHashGenerator, kademliaRepository);
		this.setNodeSettings(nodeSettings);
	}
	
	public P2PNode build(){
		P2PNode node = new P2PNode(
        		this.buildKademliaNode(), (P2PKeyHashGenerator)getKeyHashGenerator(), 
        		(FileRepository) getKademliaRepository(), getDhtStoreServiceFactory(), getDhtLookupServiceFactory());
		
		//node.getKademliaNode().registerMessageHandler(MessageType.NODE_STATUS_REQ , new NodeStatusMessageHandler(node, getDhtExecutorService())
		
		return node;
    }

}
