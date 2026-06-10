package org.gbtc.storage.client;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;

import org.gbtc.storage.P2PKeyHashGenerator;
import org.gbtc.storage.P2PNodeServer;
import org.gbtc.storage.P2PNodeServerBuilder;
import org.gbtc.storage.P2PNodeSettings;
import org.gbtc.storage.protocol.message.NodeStatusMessage;
import org.gbtc.storage.protocol.message.NodeStatusResultMessage;
import org.gbtc.storage.repository.FileDTO;
import org.gbtc.storage.repository.FileRepository;
import org.gbtc.storage.serialization.P2PGsonFactory;
import org.gbtc.storage.services.ClientRetrieveService;
import org.gbtc.storage.services.ClientStoreService;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNodeAPI;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.serialization.gson.GsonFactory;
import io.ep2p.kademlia.services.DHTLookupServiceAPI;
import io.ep2p.kademlia.services.DHTLookupServiceFactory;
import io.ep2p.kademlia.services.DHTStoreServiceAPI;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import lombok.extern.slf4j.Slf4j;

import io.ep2p.kademlia.serialization.gson.GsonMessageSerializer;

@Slf4j
public class P2PNodeStatusMonitor {
	
	private int type;

	private P2PNodeSettings nodeSettings;
	private ArrayList<NodeServer> servers = new ArrayList<NodeServer>();
	private P2PNodeServer nodeServer;
	private String name;
	private String host;
	private int port;
	
	private int numThread;
	private int connectTimeout;
	private int readTimeout;
	private int writeTimeout;
	
	private int statusPeriod;
	
	private static BufferedWriter statusWriter;
	private static Timer statusTimer;
	
	public P2PNodeStatusMonitor(int type, P2PNodeSettings nodeSettings, ArrayList<NodeServer> servers, String host,
			int numThread, int connectTimeout, int readTimeout, int writeTimeout, int statusPeriod) {

		this.type = type;
		this.nodeSettings = nodeSettings;
		this.servers = servers;
		this.host = host;
		this.port = 59111;
		
		this.numThread = numThread;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.writeTimeout = writeTimeout;
		
		this.statusPeriod = statusPeriod; 
	}
	
	public void start() {
		try {
			
			// start a client node
			this.name = "MonitorClient";
			
	        P2PKeyHashGenerator keyHashGenerator = new P2PKeyHashGenerator(nodeSettings.getIdentifierSize()); 
	        FileRepository fileRepository = new FileRepository(nodeSettings.getDataPath(), 0, 0);
	        
	        BigInteger nodeId = keyHashGenerator.generateHash(name.hashCode() & 0x7fffffff);
	        
	        GsonFactory gsonFactory = new P2PGsonFactory.DefaultGsonFactory<BigInteger, NettyConnectionInfo, String, FileDTO>(
	        		BigInteger.class, NettyConnectionInfo.class, String.class, FileDTO.class);

	        P2PMessageSender messageSender = new P2PMessageSender(
					new GsonMessageSerializer<BigInteger, NettyConnectionInfo, String, FileDTO> (gsonFactory.gsonBuilder()),
					numThread, connectTimeout, readTimeout, writeTimeout);

	        nodeServer = new P2PNodeServerBuilder(
	                nodeId,
	                new NettyConnectionInfo(host, port),
	                fileRepository,
	                keyHashGenerator,
	                String.class, FileDTO.class, nodeSettings)
	        		.messageSender(messageSender)
					.dhtStoreServiceFactory(new DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
	                    @Override
	                    public DHTStoreServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtStoreService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
	                    	return new ClientStoreService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
	                    }
					})
					.dhtLookupServiceFactory(new DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO>() {
	                    @Override
	                    public DHTLookupServiceAPI<BigInteger, NettyConnectionInfo, String, FileDTO> getDhtLookupService(DHTKademliaNodeAPI<BigInteger, NettyConnectionInfo, String, FileDTO> kademliaNodeAPI) {
	                    	return new ClientRetrieveService(kademliaNodeAPI, Executors.newSingleThreadExecutor());
	                    }
					})
					.build();
	        
	        nodeServer.setType(P2PNodeServer.TYPE_MONITOR);
	        nodeServer.setName(name);
	        
	        //log.info(String.format("Starting Client Node (Random) [Name=%s, ID=%s]...", name, node.getId()));
	        
	        boolean success = nodeServer.startClientNode();
	        
	        //log.info(String.format("Client Node (Random) [Name=%s, ID=%s] is running at %s:%s", name, node.getId(), node.getConnectionInfo().getHost(), node.getConnectionInfo().getPort()));
			
			// setup periodic nodes workload
	        String header = "";
	        if (type == P2PNodeServer.TYPE_DEFAULT) {
				// Default (Random) placement
	        	header = "Date, R-Node,Lookup Req,Store Req,Lookup TPS,Store TPS,"
		    			+ "Lookup Load,Store Load,Total Load,"
		    			+ "Lookup Duration,Store Duration,Total Duration,"
		    			+ "Avg Lookup Load,Avg Store Load,Total Avg Load,"
		    			+ "Avg Lookup Duration,Avg Store Duration,Total Avg Duration,"
		    			+ "Lookup L,Lookup M,Store L,Store M,"
		    			+ "Lookup Est,Store Est,Lookup Est-Alt,Store Est-Alt,Total Est, DHT Size, Node Name";
	        	
		        statusWriter = new BufferedWriter(new FileWriter("logs/r_nodes_workload.csv", true));
			}
	        if (type == P2PNodeServer.TYPE_VN) {
				// Virtual Node placement
	        	header = "Date, VN-Node,Lookup Req,Store Req,Lookup TPS,Store TPS,"
		    			+ "Lookup Load,Store Load,Total Load,"
		    			+ "Lookup Duration,Store Duration,Total Duration,"
		    			+ "Avg Lookup Load,Avg Store Load,Total Avg Load,"
		    			+ "Avg Lookup Duration,Avg Store Duration,Total Avg Duration,"
		    			+ "Lookup L,Lookup M,Store L,Store M,"
		    			+ "Lookup Est,Store Est,Lookup Est-Alt,Store Est-Alt,Total Est, DHT Size, Node Name";
	        	
		        statusWriter = new BufferedWriter(new FileWriter("logs/vn_nodes_workload.csv", true));
			}
			else if (type == P2PNodeServer.TYPE_REPUTATION) {
				// Reputation placement
	        	header = "Date, RP-Node,Lookup Req,Store Req,Lookup TPS,Store TPS,"
		    			+ "Lookup Load,Store Load,Total Load,"
		    			+ "Lookup Duration,Store Duration,Total Duration,"
		    			+ "Avg Lookup Load,Avg Store Load,Total Avg Load,"
		    			+ "Avg Lookup Duration,Avg Store Duration,Total Avg Duration,"
		    			+ "Lookup L,Lookup M,Store L,Store M,"
		    			+ "Lookup Est,Store Est,Lookup Est-Alt,Store Est-Alt,Total Est, DHT Size, Node Name";

		        statusWriter = new BufferedWriter(new FileWriter("logs/rp_nodes_workload.csv", true));
			}
			else if (type == P2PNodeServer.TYPE_PT) {
				// PT placement
	        	header = "Date, PT-Node,Lookup Req,Store Req,Lookup TPS,Store TPS,"
		    			+ "Lookup Load,Store Load,Total Load,"
		    			+ "Lookup Duration,Store Duration,Total Duration,"
		    			+ "Avg Lookup Load,Avg Store Load,Total Avg Load,"
		    			+ "Avg Lookup Duration,Avg Store Duration,Total Avg Duration,"
		    			+ "Lookup L,Lookup M,Store L,Store M,"
		    			+ "Lookup Est,Store Est,Lookup Est-Alt,Store Est-Alt,Total Est, DHT Size, Node Name";

		        statusWriter = new BufferedWriter(new FileWriter("logs/pt_nodes_workload.csv", true));
			}
	        
			statusWriter.write(header);
			statusWriter.newLine();
			statusWriter.flush();
			
			statusTimer = new Timer();
			statusTimer.schedule(new NodeStatus(), 0, statusPeriod);
			
		}
		catch (Exception e) {
			e.printStackTrace();
			log.error(e.getMessage());
		}
	}
	
	public void stop() {
		statusTimer.cancel();
		nodeServer.stopClientNode();
	}
	
	private class NodeStatus extends TimerTask {
		
		// this task is to periodically retrieve the workload status in every node every <estimatePeriod> 
		// (currently set every 5s)
		// then put in the list.will only keep latest <statusCount> entries
		
		@Override
		public void run() {
		        
			for (NodeServer server : servers) {
				try {
					// get the status of each node
					NodeStatusMessage<BigInteger, NettyConnectionInfo> statusMessage = new NodeStatusMessage<>(
							new NodeStatusMessage.NodeStatus<>(nodeServer.getKademliaNode()));

					KademliaMessage<BigInteger, NettyConnectionInfo, Serializable> statusResponse = 
							nodeServer.getMessageSender().sendMessage(nodeServer.getKademliaNode(), server, statusMessage);

					log.debug("[WorkloadStatus] statusResponse=" + statusResponse.toString());

					NodeStatusResultMessage.NodeStatusResult<String> statusData = (NodeStatusResultMessage.NodeStatusResult<String>) statusResponse.getData();
					String nodeStatus = statusData.getStatus();

					statusWriter.write(nodeStatus + "," + server.getName());
					statusWriter.newLine();
					statusWriter.flush();
				} 
				catch (Exception e) {
					e.printStackTrace();
					log.error(e.getMessage());
				}
			}
		}
	}	

}
