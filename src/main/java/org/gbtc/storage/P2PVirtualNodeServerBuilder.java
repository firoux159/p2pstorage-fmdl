package org.gbtc.storage;

import java.math.BigInteger;

import org.gbtc.storage.repository.FileDTO;
import org.gbtc.storage.repository.FileRepository;
import org.gbtc.storage.serialization.P2PGsonFactory;
import io.ep2p.kademlia.connection.MessageSender;
import io.ep2p.kademlia.netty.builder.NettyKademliaDHTNodeBuilder;
import io.ep2p.kademlia.netty.builder.NettyKademliaMessageHandlerFactoryProvider;
import io.ep2p.kademlia.netty.client.OkHttpMessageSender;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.netty.factory.NettyChannelInboundHandlerFactory;
import io.ep2p.kademlia.netty.factory.NettyChannelInitializerFactory;
import io.ep2p.kademlia.netty.server.KademliaNodeServer;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.node.builder.DHTKademliaNodeBuilder;
import io.ep2p.kademlia.serialization.gson.GsonFactory;
import io.ep2p.kademlia.serialization.gson.GsonMessageSerializer;
import io.ep2p.kademlia.services.DHTLookupServiceFactory;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import io.ep2p.kademlia.table.Bucket;
import io.ep2p.kademlia.table.DefaultRoutingTableFactory;
import io.ep2p.kademlia.table.RoutingTableFactory;

import org.gbtc.storage.node.builder.P2PNodeBuilder;


public class P2PVirtualNodeServerBuilder extends NettyKademliaDHTNodeBuilder<String, FileDTO> {
	
	public P2PVirtualNodeServerBuilder(BigInteger id, NettyConnectionInfo connectionInfo, FileRepository repository, 
			P2PKeyHashGenerator keyHashGenerator, Class<String> keyClass, Class<FileDTO> valueClass, P2PNodeSettings nodeSettings) {
		
		super(id, connectionInfo, repository, keyHashGenerator, keyClass, valueClass);
		this.nodeSettings(nodeSettings);
	}
	
	@Override
	public P2PVirtualNodeServer build() {
        fillDefaults();
        return new P2PVirtualNodeServer(buildDHTKademliaNodeAPI(), getKademliaNodeServer());
    }
	
	@Override
    protected void fillDefaults() {
//		if (this.getNodeSettings() == null)
//			this.nodeSettings(NodeSettings.Default.build());
		if (this.getRoutingTable() == null) {
            RoutingTableFactory<BigInteger, NettyConnectionInfo, Bucket<BigInteger, NettyConnectionInfo>> routingTableFactory = new DefaultRoutingTableFactory<>(this.getNodeSettings());
            this.routingTable(routingTableFactory.getRoutingTable(this.getId()));
		}

		if (this.getGsonFactory() == null) {
            //this.gsonFactory(new GsonFactory.DefaultGsonFactory<BigInteger, NettyConnectionInfo, String, FileDTO>(BigInteger.class, NettyConnectionInfo.class, this.getKeyClass(), this.getValueClass()));
			this.gsonFactory(new P2PGsonFactory.DefaultGsonFactory<BigInteger, NettyConnectionInfo, String, FileDTO>(BigInteger.class, NettyConnectionInfo.class, this.getKeyClass(), this.getValueClass()));
        }

		if (this.getMessageSerializer() == null){
            this.messageSerializer(new GsonMessageSerializer<BigInteger, NettyConnectionInfo, String, FileDTO>(this.getGsonFactory().gsonBuilder()));
        }

		if (this.getMessageSender() == null) {
            if (this.getOkHttpClient() == null)
                this.messageSender( new OkHttpMessageSender<>(this.getMessageSerializer()));
            else
                this.messageSender( new OkHttpMessageSender<>(this.getMessageSerializer(), this.getOkHttpClient()));
        }

        if (this.getNettyChannelInboundHandlerFactory() == null) {
            this.nettyChannelInboundHandlerFactory(new NettyChannelInboundHandlerFactory.DefaultNettyChannelInboundHandlerFactory());
        }

        if (this.getNettyKademliaMessageHandlerFactoryProvider() == null) {
            this.nettyKademliaMessageHandlerFactoryProvider(new NettyKademliaMessageHandlerFactoryProvider.DefaultNettyKademliaMessageHandlerFactoryProvider());
        }

        if (this.getNettyChannelInitializerFactory() == null) {
            this.nettyChannelInitializerFactory(new NettyChannelInitializerFactory.DefaultNettyChannelInitializerFactory(this.getSslContext()));
        }

        if (this.getKademliaNodeServer() == null){
            this.kademliaNodeServer(
                    new KademliaNodeServer<>(
                            this.getConnectionInfo().getHost(),
                            this.getConnectionInfo().getPort(),
                            this.getNettyChannelInitializerFactory(),
                            this.getNettyChannelInboundHandlerFactory(),
                            this.getNettyKademliaMessageHandlerFactoryProvider().getNettyKademliaMessageHandlerFactory(this)
                    )
            );
        }
    }

	
	@Override
    protected DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> buildDHTKademliaNodeAPI(){
        
		P2PNodeBuilder builder = new P2PNodeBuilder(
                this.getId(),
                this.getConnectionInfo(),
                this.getRoutingTable(),
                this.getMessageSender(),
                (P2PKeyHashGenerator) this.getKeyHashGenerator(),
                (FileRepository) this.getRepository(),
                (P2PNodeSettings) this.getNodeSettings());
        
        if (this.getMessageSender() != null){
            builder.setMessageSender(this.getMessageSender());
        }
		if (this.getDhtLookupServiceFactory() != null){
            builder.setDhtLookupServiceFactory(this.getDhtLookupServiceFactory());
        }
        if (this.getDhtStoreServiceFactory() != null){
            builder.setDhtStoreServiceFactory(this.getDhtStoreServiceFactory());
        }
        
        return builder.setNodeSettings(this.getNodeSettings()).build();
        
    }
    
    public P2PVirtualNodeServerBuilder messageSender(MessageSender<BigInteger, NettyConnectionInfo> messageSender){
    	super.messageSender(messageSender);
        return this;
    }
	
	public P2PVirtualNodeServerBuilder dhtStoreServiceFactory(DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO> dhtStoreServiceFactory){
    	super.dhtStoreServiceFactory(dhtStoreServiceFactory);
        return this;
    }

    public P2PVirtualNodeServerBuilder dhtLookupServiceFactory(DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO> dhtLookupServiceFactory){
        super.dhtLookupServiceFactory(dhtLookupServiceFactory);
        return this;
    }

}
