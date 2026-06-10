package org.gbtc.storage.protocol.message;

import org.gbtc.storage.protocol.P2PMessageType;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.protocol.message.KademliaMessage;

public class NodeShutdownMessage<I extends Number, C extends ConnectionInfo> extends KademliaMessage<I, C, String> {
    
	public NodeShutdownMessage() {
        super(P2PMessageType.NODE_SHUTDOWN);
        setData("SHUTDOWN");
    }
}
