package org.gbtc.storage;

import java.io.File;
import java.io.FileReader;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import io.ep2p.kademlia.NodeSettings;
import lombok.Getter;

@Getter
public class P2PNodeSettings extends NodeSettings implements Serializable, Cloneable {

	private static final long serialVersionUID = 1L;

	public long seed;

	public String name;
	public String host;
	public int port = 0;
	
	public int maximumAttempt = 0;
	
	public BigInteger bootId;
	public String bootHost;
	public int bootPort = 0;
	
	public int virtualNodeCount = 0;
	
//	public int statusCount = 0;
//	public int estimatePeriod = 0;
//	public int entrySize = 0;

	public String dataPath;
	public String dataInputPath;
	public String dataTempPath;
	public int dataSize;

	public P2PNodeSettings() {
		
		// read configuration from file
		File configFile = new File("node.properties");

		try {
			FileReader reader = new FileReader(configFile);
			Properties props = new Properties();
			props.load(reader);

			this.virtualNodeCount = Integer.parseInt(props.getProperty("VIRTUAL_NODES"));
			
//			this.name = props.getProperty("NAME");
			
			// temporary approach to minimize work to change manual seed
			// use nodeName.hashCode() as the seed
//			this.seed = this.name.hashCode();
			
//			this.host = props.getProperty("HOST");
//			this.port = Integer.parseInt(props.getProperty("PORT"));
//			this.maximumAttempt = Integer.parseInt(props.getProperty("MAX_ATTEMPT"));
//			
//			this.bootId = new BigInteger(props.getProperty("BOOT_ID"));
//			this.bootHost = props.getProperty("BOOT_HOST");
//			this.bootPort = Integer.parseInt(props.getProperty("BOOT_PORT"));

//			this.statusCount = Integer.parseInt(props.getProperty("STATUS_COUNT"));
//			this.estimatePeriod = Integer.parseInt(props.getProperty("ESTIMATE_PERIOD"));
//			this.entrySize = Integer.parseInt(props.getProperty("ENTRY_SIZE"));
			
			this.dataPath = props.getProperty("DATA_PATH");
			this.dataInputPath = props.getProperty("DATA_INPUT_PATH");
			this.dataTempPath = props.getProperty("DATA_TEMP_PATH");
			this.dataSize = Integer.parseInt(props.getProperty("DATA_SIZE"));
			
			// from parent class
			int pingSchedule = Integer.parseInt(props.getProperty("PING_SCHEDULE_TIME_UNIT"));
			TimeUnit defaultPingScheduleTimeUnit = TimeUnit.SECONDS;

			switch (pingSchedule) {
			case 1:
				defaultPingScheduleTimeUnit = TimeUnit.NANOSECONDS;
				break;
			case 2:
				defaultPingScheduleTimeUnit = TimeUnit.MICROSECONDS;
				break;
			case 3:
				defaultPingScheduleTimeUnit = TimeUnit.MILLISECONDS;
				break;
			case 4:
				defaultPingScheduleTimeUnit = TimeUnit.SECONDS;
				break;
			case 5:
				defaultPingScheduleTimeUnit = TimeUnit.HOURS;
				break;
			case 6:
				defaultPingScheduleTimeUnit = TimeUnit.DAYS;
				break;
			}
			
			this.identifierSize = Integer.parseInt(props.getProperty("IDENTIFIER_SIZE"));
			this.bucketSize = Integer.parseInt(props.getProperty("BUCKET_SIZE"));
			this.findNodeSize = Integer.parseInt(props.getProperty("FIND_NODE_SIZE"));
			this.maximumLastSeenAgeToConsiderAlive = Integer.parseInt(props.getProperty("MAXIMUM_LAST_SEEN_AGE_TO_CONSIDER_ALIVE"));
			this.pingScheduleTimeUnit = defaultPingScheduleTimeUnit;
			this.pingScheduleTimeValue = Integer.parseInt(props.getProperty("PING_SCHEDULE_TIME_VALUE"));
			this.dhtExecutorPoolSize = Integer.parseInt(props.getProperty("DHT_EXECUTOR_POOL_SIZE"));
			this.scheduledExecutorPoolSize = Integer.parseInt(props.getProperty("SCHEDULED_EXECUTOR_POOL_SIZE"));
			this.enabledFirstStoreRequestForcePass = Boolean.parseBoolean(props.getProperty("ENABLED_FIRST_STORE_REQUEST_FORCE_PASS"));
			
			// Set the default values, Default is static inner class inside parent class 
			// not sure whether this is necessary, but just to be safe
			Default.IDENTIFIER_SIZE = getIdentifierSize();
			Default.BUCKET_SIZE = getBucketSize();
			Default.FIND_NODE_SIZE = getFindNodeSize();
			Default.MAXIMUM_LAST_SEEN_AGE_TO_CONSIDER_ALIVE = getMaximumLastSeenAgeToConsiderAlive();
			Default.PING_SCHEDULE_TIME_UNIT = getPingScheduleTimeUnit();
			Default.PING_SCHEDULE_TIME_VALUE = getPingScheduleTimeValue();
			Default.DHT_EXECUTOR_POOL_SIZE = getDhtExecutorPoolSize();
			Default.SCHEDULED_EXECUTOR_POOL_SIZE = getScheduledExecutorPoolSize();
			Default.ENABLED_FIRST_STORE_REQUEST_FORCE_PASS = isEnabledFirstStoreRequestForcePass();
			
			// This is where I pretend to code something so that I'll look busy. But I have no idea what to type.

		} 
		catch (Exception e) {
			// TO-DO
			e.printStackTrace();
		}		
	}
	
	@Override
	public Object clone() {
	    P2PNodeSettings clone = null;
		try {
	        clone = (P2PNodeSettings) super.clone();
	    } catch (CloneNotSupportedException e) {
	        // TO-DO
	    }
		
		return clone;
	}
}
