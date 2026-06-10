package org.gbtc.storage.client;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.ep2p.kademlia.connection.MessageSender;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.netty.common.NettyExternalNode;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.node.external.ExternalNode;
import io.ep2p.kademlia.protocol.MessageType;
import io.ep2p.kademlia.protocol.message.EmptyKademliaMessage;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.serialization.api.MessageSerializer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
public class P2PMessageSender implements MessageSender<BigInteger, NettyConnectionInfo> {
    
	public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
   
	private final MessageSerializer<BigInteger, NettyConnectionInfo> messageSerializer;
    private final OkHttpClient client;
    private final ExecutorService executorService;
    
    public P2PMessageSender(
    		MessageSerializer<BigInteger, NettyConnectionInfo> messageSerializer, 
    		int numThread, int connectTimeout, int readTimeout, int writeTimeout) {
    	
        this(messageSerializer, Executors.newSingleThreadExecutor(), new OkHttpClient.Builder()
        		.connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(writeTimeout, TimeUnit.MILLISECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(64, 1, TimeUnit.MINUTES))
                .build());
    }

	public P2PMessageSender(MessageSerializer<BigInteger, NettyConnectionInfo> messageSerializer, ExecutorService executorService, OkHttpClient client) {
        this.messageSerializer = messageSerializer;
        this.executorService = executorService;
        this.client = client;
    }


    public <U extends Serializable, O extends Serializable> KademliaMessage<BigInteger, NettyConnectionInfo, O> sendMessage(
    		KademliaNodeAPI<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> receiver, 
    		KademliaMessage<BigInteger, NettyConnectionInfo, U> message) {
        
    	message.setNode(caller);
        String messageStr = messageSerializer.serialize(message);
        
        RequestBody body = RequestBody.create(messageStr, JSON);
        Request request = new Request.Builder()
                .url(String.format("http://%s:%d/", receiver.getConnectionInfo().getHost(), receiver.getConnectionInfo().getPort()))
                .post(body)
                .build();

        //log.debug(String.format("Message string: [%s]", messageStr));
        //log.debug(String.format("Request string: [%s]", request.toString()));
        
        try (Response response = client.newCall(request).execute()) {
            String responseStr = Objects.requireNonNull(response.body()).string();
            
            return messageSerializer.deserialize(responseStr);
        } 
        catch (IOException e) {
            log.error("Failed to send message to " + caller.getId(), e);
            return new KademliaMessage<BigInteger, NettyConnectionInfo, O>() {
                @Override
                public O getData() {
                    return null;
                }

                @Override
                public String getType() {
                    return MessageType.EMPTY;
                }

                @Override
                public Node<BigInteger, NettyConnectionInfo> getNode() {
                    return receiver;
                }

                @Override
                public boolean isAlive() {
                    return false;
                }
            };
        }
    }	
	
	public <U extends Serializable, O extends Serializable> KademliaMessage<BigInteger, NettyConnectionInfo, O> sendMessage(
			Node<BigInteger, NettyConnectionInfo> caller, 
			Node<BigInteger, NettyConnectionInfo> receiver, 
			KademliaMessage<BigInteger, NettyConnectionInfo, U> message) {
		
        message.setNode(caller);
        
        String messageStr = messageSerializer.serialize(message);
        RequestBody body = RequestBody.create(messageStr, JSON);
        Request request = new Request.Builder()
                .url(String.format("http://%s:%d/", receiver.getConnectionInfo().getHost(), receiver.getConnectionInfo().getPort()))
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseStr = Objects.requireNonNull(response.body()).string();
            
            //System.out.println("[P2PMessageSender].sendMessage, response= "+responseStr);
            
            return messageSerializer.deserialize(responseStr);
        } 
        catch (IOException e) {
        	log.error("Failed to send message to " + caller.getId(), e);
            return new KademliaMessage<BigInteger, NettyConnectionInfo, O>() {
                @Override
                public O getData() {
                    return null;
                }

                @Override
                public String getType() {
                    return MessageType.EMPTY;
                }

                @Override
                public Node<BigInteger, NettyConnectionInfo> getNode() {
                    return receiver;
                }

                @Override
                public boolean isAlive() {
                    return false;
                }
            };
        }
    }

    @Override
    public <O extends Serializable> void sendAsyncMessage(
    		KademliaNodeAPI<BigInteger, NettyConnectionInfo> caller, 
    		Node<BigInteger, NettyConnectionInfo> receiver, 
    		KademliaMessage<BigInteger, NettyConnectionInfo, O> message) {
    	
        executorService.submit(() -> sendMessage(caller, receiver, message));
    }

    public void stop(){
        this.executorService.shutdownNow();
    }
}
