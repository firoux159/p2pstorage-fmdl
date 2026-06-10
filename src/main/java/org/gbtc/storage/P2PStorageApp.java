package org.gbtc.storage;

import lombok.extern.slf4j.Slf4j;


@Slf4j
public class P2PStorageApp {
	
	private static int type; // see P2PNodeServer.TYPE_*
	private static int mode; // 0-automated, 1-manual (menu-based)
	
	private static String name;
	private static String host;
	private static int port;
	
	private static String bootName;
	private static String bootHost;
	private static int bootPort;
	
	public static void main(String args[]) {
		
		if (args.length < 8) {
			log.error("Missing required arguments <mode> <type> <hostname> <ip_address> <port> <boot hostname> <boot ip_address> <boot port> - System Aborted.");
		}
		else {
			try {
				mode = Integer.parseInt(args[0].trim());
				type = Integer.parseInt(args[1].trim());

				name = args[2].trim();
				host = args[3].trim();
				port = Integer.parseInt(args[4].trim());

				bootName = args[5].trim();
				bootHost = args[6].trim();
				bootPort = Integer.parseInt(args[7].trim());
				
				//P2PStorageManager storageManager = new P2PStorageManager(type, name, host, port, virtualNodeCount, randomCount, bootName, bootHost, bootPort);
				P2PStorageManager storageManager = new P2PStorageManager(type, name, host, port, bootName, bootHost, bootPort);
				
				storageManager.control(mode);
				
			}
			catch (Exception e) {
				System.err.println("FATAL ERROR - System Aborted. [Reason: "+e.getMessage()+"]");
			}
		}
	}

}
