package org.gbtc.storage.services;

import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.protocol.handler.GeneralResponseMessageHandler;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.protocol.message.PingKademliaMessage;

public class NodeShutdownMessageHandler<I extends Number, C extends ConnectionInfo> extends GeneralResponseMessageHandler<I, C> {

    @Override
    @SuppressWarnings("unchecked")
    public <U extends KademliaMessage<I, C, ?>, O extends KademliaMessage<I, C, ?>> O doHandle(KademliaNodeAPI<I, C> kademliaNode, U message) {
        return (O) doHandle(kademliaNode, (PingKademliaMessage<I, C>) message);
    }
    
    
//    public KademliaMessage handle(KademliaNodeAPI kademliaNode, KademliaMessage message) {
//
//    	switch (message.getType()) {
//    	
//          case P2PMessageType.NODE_SHUTDOWN :
//              if (!(message instanceof NodeShutdownMessage))
//                  throw new IllegalArgumentException("Cant handle message. Required: NodeShutdownMessage");
//              return handleNodeShutdownRequest((NodeShutdownMessage<BigInteger, NettyConnectionInfo>) message);
//              
//          default:
//              throw new IllegalArgumentException("message param is not supported");
//      }
//    }

}
