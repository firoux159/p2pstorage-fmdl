package org.gbtc.storage.client;

public class P2PClientApp {
	
	private static int type; // The type of the server this client will connect to. See P2PNodeServer.TYPE_*
	
	private static String host;
	private static int port;
	private static int clientCount;
	private static int dataCount;
	private static int dataSize;
	
	private static int iteration = 0;
	private static int sampleCount = 0;
	
	public static void main(String args[]) {
		
		if (args.length < 8) {
			System.err.println("Missing required arguments <type> <host> <port> <number of client> <number of data> <data size (KB)> <iteration> <sample size> - System Aborted.");
		}
		else {
			
			type = Integer.parseInt(args[0].trim());
			host = args[1].trim();
			port = Integer.parseInt(args[2].trim());
			
			clientCount = Integer.parseInt(args[3]);
			dataCount = Integer.parseInt(args[4]);
			dataSize = Integer.parseInt(args[5]);
			
			iteration = Integer.parseInt(args[6]);
			sampleCount = Integer.parseInt(args[7]);
			
			P2PClientManager clientManager = new P2PClientManager(type, host, port, clientCount, dataCount, dataSize, iteration, sampleCount);
			
			clientManager.control();
		}		
	}
}
