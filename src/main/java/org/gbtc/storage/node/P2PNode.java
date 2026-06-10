package org.gbtc.storage.node;

import java.math.BigInteger;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import org.gbtc.storage.P2PKeyHashGenerator;
import org.gbtc.storage.P2PNodeSettings;
import org.gbtc.storage.protocol.P2PMessageType;
import org.gbtc.storage.repository.FileDTO;
import org.gbtc.storage.repository.FileRepository;
import org.gbtc.storage.services.ClientRetrieveService;
import org.gbtc.storage.services.ClientStoreService;
import org.gbtc.storage.services.NodeStatusService;
import org.gbtc.storage.services.NodeWorkloadService;

import io.ep2p.kademlia.exception.HandlerNotFoundException;
import io.ep2p.kademlia.model.LookupAnswer;
import io.ep2p.kademlia.model.StoreAnswer;
import io.ep2p.kademlia.netty.common.NettyConnectionInfo;
import io.ep2p.kademlia.node.DHTKademliaNode;
import io.ep2p.kademlia.node.KademliaNodeAPI;
import io.ep2p.kademlia.node.Node;
import io.ep2p.kademlia.protocol.message.KademliaMessage;
import io.ep2p.kademlia.services.DHTLookupServiceFactory;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class P2PNode extends DHTKademliaNode<BigInteger, NettyConnectionInfo, String, FileDTO> {
	
	private static final long serialVersionUID = 1L;

	@Setter
	private P2PNode actualNode;
	
    @Setter
    private int estimatePeriod = 0;
    @Setter
    private int entrySize = 0;
	
    @Setter
	@Getter
	private int findRequest = 0;
    @Setter
    @Getter
    private int lookupRequest = 0;
    @Setter
    @Getter
    private int storeRequest = 0;
    
    private double lastFindThroughput = 0.0;
    private double lastLookupThroughput = 0.0;
    private double lastStoreThroughput = 0.0;
    
    private double lastFindDuration = 0.0;
    private double lastLookupDuration = 0.0;
    private double lastStoreDuration = 0.0;    

    @Getter
    private int periodicFindRequest = 0;
    @Getter
    private int periodicLookupRequest = 0;
    @Getter
    private int periodicStoreRequest = 0;    
    
    private ArrayList<Integer> findRequestList = new ArrayList<Integer>();
    private ArrayList<Integer> lookupRequestList = new ArrayList<Integer>();
    private ArrayList<Integer> storeRequestList = new ArrayList<Integer>();

    private ArrayList<Integer> periodicFindRequestList = new ArrayList<Integer>();
    private ArrayList<Integer> periodicLookupRequestList = new ArrayList<Integer>();
    private ArrayList<Integer> periodicStoreRequestList = new ArrayList<Integer>();
    
    private ArrayList<Integer> periodicFindArrivalList = new ArrayList<Integer>();
    private ArrayList<Integer> periodicLookupArrivalList = new ArrayList<Integer>();
    private ArrayList<Integer> periodicStoreArrivalList = new ArrayList<Integer>();    
    
	private ArrayList<Double> findThroughputList = new ArrayList<Double>();
	private ArrayList<Double> lookupThroughputList = new ArrayList<Double>();
	private ArrayList<Double> storeThroughputList = new ArrayList<Double>();
	
	private ArrayList<Long> findDurationList = new ArrayList<Long>();
	private ArrayList<Long> lookupDurationList = new ArrayList<Long>();
	private ArrayList<Long> storeDurationList = new ArrayList<Long>();

	public P2PNode(
			KademliaNodeAPI<BigInteger, NettyConnectionInfo> kademliaNode, 
			P2PKeyHashGenerator keyHashGenerator, 
			FileRepository kademliaRepository, 
			DHTStoreServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO> dhtStoreServiceFactory, 
			DHTLookupServiceFactory<BigInteger, NettyConnectionInfo, String, FileDTO> dhtLookupServiceFactory) {
		
		super(kademliaNode, keyHashGenerator, kademliaRepository, dhtStoreServiceFactory, dhtLookupServiceFactory);
	}
	
    public P2PNode(KademliaNodeAPI<BigInteger, NettyConnectionInfo> kademliaNode, P2PKeyHashGenerator keyHashGenerator, FileRepository kademliaRepository) {
    	super(kademliaNode, keyHashGenerator, kademliaRepository);
    }
    
    @Override
    protected void initDHTKademliaNode(){
    	super.initDHTKademliaNode();

//    	this.registerMessageHandler(P2PMessageType.CLIENT_STORE_REQ, new ClientStoreService(this, Executors.newSingleThreadExecutor()));
//    	this.registerMessageHandler(P2PMessageType.CLIENT_RETRIEVE_REQ, new ClientRetrieveService(this, Executors.newSingleThreadExecutor()));
    	
    	//this.registerMessageHandler(P2PMessageType.CLIENT_STORE_REQ, new ClientStoreService(this, Executors.newFixedThreadPool(10)));
    	//this.registerMessageHandler(P2PMessageType.CLIENT_RETRIEVE_REQ, new ClientRetrieveService(this, Executors.newFixedThreadPool(10)));
    	
    	this.registerMessageHandler(P2PMessageType.CLIENT_STORE_REQ, this.getStoreService());
    	this.registerMessageHandler(P2PMessageType.CLIENT_RETRIEVE_REQ, this.getLookupService());
    	
    	this.registerMessageHandler(P2PMessageType.DHT_EXTERNAL_STORE, this.getStoreService());
    	this.registerMessageHandler(P2PMessageType.DHT_EXTERNAL_NEXT_STORE, this.getStoreService());
    	
    	//this.registerMessageHandler(P2PMessageType.NODE_STATUS, new NodeStatusService(this, Executors.newSingleThreadExecutor()));
    	this.registerMessageHandler(P2PMessageType.NODE_STATUS, new NodeStatusService(this, Executors.newFixedThreadPool(2)));
    	this.registerMessageHandler(P2PMessageType.NODE_WORKLOAD, new NodeWorkloadService(this, Executors.newFixedThreadPool(2)));
    	this.registerMessageHandler(P2PMessageType.NODE_THROUGHPUT, new NodeWorkloadService(this, Executors.newFixedThreadPool(2)));
    }
    
    public Future<StoreAnswer<BigInteger, NettyConnectionInfo, String>> clientStore(Node<BigInteger, NettyConnectionInfo> receiver, String key, FileDTO value) {
        if(!isRunning())
            throw new IllegalStateException("Node is not running");
        
        return ((ClientStoreService) this.getStoreService()).clientStore(receiver, key, value);
    }
    
    public Future<LookupAnswer<BigInteger, NettyConnectionInfo, String, FileDTO>> clientLookup(Node<BigInteger, NettyConnectionInfo> receiver, String key) {
        if(!isRunning())
            throw new IllegalStateException("Node is not running");
        
        return ((ClientRetrieveService) this.getLookupService()).clientLookup(receiver, key);
    }
    
    
    @Override
    public KademliaMessage<BigInteger, NettyConnectionInfo, ?> onMessage(KademliaMessage<BigInteger, NettyConnectionInfo, ?> message) throws HandlerNotFoundException {
    	log.info(String.format("Node [%s] receives message from Node [%s], message type [%s]", this.getId(), message.getNode().getId(), message.getType()));

    	//System.out.println(String.format("Node [%s] receives message from Node [%s], message type [%s]", this.getId(), message.getNode().getId(), message.getType()));
    	return super.onMessage(message);
    }
    
	public String getWorkloadStatus() {
		
		// TO-DO: we ignore FIND operation
		
		// all time units are in millisecond
		// need to capture current throughput for consistency in statistical reporting
		// note: that this figure is dynamic
		double averageLookupThroughput = getAverageLookupThroughput();
		double averageStoreThroughput = getAverageStoreThroughput();
		
		// need to capture current active request (in-queue + in-service) for consistency in statistical reporting
		// note: that this figure is dynamic
		double currentLookup = lookupRequest;
		double currentStore = storeRequest;
		
		double actualLookupLoad = 0.0, actualStoreLoad = 0.0;
		
		// load ideally calculated using load factors for each of the task class for each node (recall: nodes are heterogeneous)
		// value of load factors should be derived empirically (e.g., based on number of CPU instructions 
		// of the task and the node's CPU power)
		// for now, we shall just use average throughput/capacity to represent this i.e., we calculate time-to-completion (TTC), instead
		if (currentLookup > 0 && averageLookupThroughput > 0)
			actualLookupLoad = currentLookup * (1/averageLookupThroughput);
		
		if (currentStore > 0 && averageStoreThroughput > 0)
			actualStoreLoad = currentStore * (1/averageStoreThroughput);

		// actual average
		double averageLookupLoad = 0.0, averageStoreLoad = 0.0; 
		
		if (averageLookupThroughput > 0)
			averageLookupLoad = getAverageLookup() * (1/averageLookupThroughput);
		
		if (averageStoreThroughput > 0)
			averageStoreLoad = getAverageStore() * (1/averageStoreThroughput);
		
		// duration average
		double averageLookupDuration = calculateAverage(lookupDurationList);
		double averageStoreDuration = calculateAverage(storeDurationList);
		//long currentLookupDuration = latestLookupDuration;
		
		// calculate wait time of tasks in the system within <T> period
		// using queuing theory (arrival rate lambda and service rate mu)
		// note: currently arrivalRate and serviceRate are captured in real time
		// need to adjust the lambda because the calculation is per (ESTIMATION_DURATION) duration e.g., per 10000 ms
		// for mu, no need to adjust because the calculation is using transaction per 1000 millisecond
		//int estimatePeriod = ((P2PNodeSettings) this.getNodeSettings()).getEstimatePeriod();

		// arrival is request per <estimatePeriod> e.g., 2 requests per 10000 ms
		// adjust it to request per ms
		double arrivalLookupRate = getAverageLookupArrival() / estimatePeriod;
		// throughput is already per ms so no need to adjust
		double serviceLookupRate = averageLookupThroughput; 
		
		double arrivalStoreRate = getAverageStoreArrival() / estimatePeriod;
		double serviceStoreRate = averageStoreThroughput;

		// M/M/C
//		double expectedLookupWaitMMC = calculateWaitMMC(2, arrivalLookupRate, serviceLookupRate);
//		double expectedStoreWaitMMC = calculateWaitMMC(2, arrivalStoreRate, serviceStoreRate);
		
		// M/M/1
		double expectedLookupWaitMM1 = calculateWaitMM1(arrivalLookupRate, serviceLookupRate);
		double expectedStoreWaitMM1 = calculateWaitMM1(arrivalStoreRate, serviceStoreRate);
		
		double expectedLookupWaitMM1Alt = calculateWaitMM1Alt(arrivalLookupRate, serviceLookupRate);
		double expectedStoreWaitMM1Alt = calculateWaitMM1Alt(arrivalStoreRate, serviceStoreRate);		
		
		int dhtSize = ((FileRepository)this.getKademliaRepository()).getDHTSize(); 
		
		Date date = Calendar.getInstance().getTime();  
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");  
		String strDate = dateFormat.format(date);

		String workloadStatus = String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
				strDate, this.getId(),
				currentLookup, currentStore, averageLookupThroughput, averageStoreThroughput,
				actualLookupLoad, actualStoreLoad, (actualLookupLoad+actualStoreLoad),
				lastLookupDuration, lastStoreDuration, (lastLookupDuration+lastStoreDuration),
				averageLookupLoad, averageStoreLoad, (averageLookupLoad+averageStoreLoad),
				averageLookupDuration, averageStoreDuration, (averageLookupDuration+averageStoreDuration),
				arrivalLookupRate, serviceLookupRate, arrivalStoreRate, serviceStoreRate,
				expectedLookupWaitMM1, expectedStoreWaitMM1, expectedLookupWaitMM1Alt, expectedStoreWaitMM1Alt,
				(expectedLookupWaitMM1+expectedStoreWaitMM1), dhtSize);
		
		return workloadStatus;
	}	
	
	public Double getThroughput() {
		
		// TO-DO: we ignore FIND operation
		// all time units are in millisecond
		// need to capture current throughput for consistency in statistical reporting
		// note: that this figure is dynamic
		
		// calculate aggregated throughput using weighted average.
		double lookupWeight = 0.5;
		double storeWeight = 0.5;
		
		double averageLookupThroughput = getAverageLookupThroughput();
		double averageStoreThroughput = getAverageStoreThroughput();
		
		//double aggregatedThroughput = 0.5 * ((lookupWeight*averageLookupThroughput) + (storeWeight*averageStoreThroughput));
		//return aggregatedThroughput;
		
		return averageStoreThroughput;
	}	
	
	public double estimateWorkload() {
		
		// TO-DO: we ignore FIND operation
		
		//int estimatePeriod = ((P2PNodeSettings) this.getNodeSettings()).getEstimatePeriod();
		
		// need to capture current throughput for consistency in statistical reporting
		// note: that this figure is dynamic
		double averageLookupThroughput = getAverageLookupThroughput();
		double averageStoreThroughput = getAverageStoreThroughput();
		
		// calculate wait time of tasks in the system within <T> period
		// using queuing theory (arrival rate lambda and service rate mu)
		// note: currently arrivalRate and serviceRate are captured in real time
		// need to adjust the lambda because the calculation is per (ESTIMATION_DURATION) duration e.g., per 5000 ms
		// for mu, no need to adjust because the calculation is using transaction per 1000 millisecond
		
		// arrival is request per <estimatePeriod> e.g., 2 requests per 5000 ms
		// adjust it to request per ms
		double arrivalLookupRate = getAverageLookupArrival() / estimatePeriod;
		// throughput is already per ms so no need to adjust
		double serviceLookupRate = averageLookupThroughput; 
		
		double arrivalStoreRate = getAverageStoreArrival() / estimatePeriod;
		double serviceStoreRate = averageStoreThroughput;

		// M/M/C
//		double expectedLookupWaitMMC = calculateWaitMMC(2, arrivalLookupRate, serviceLookupRate);
//		double expectedStoreWaitMMC = calculateWaitMMC(2, arrivalStoreRate, serviceStoreRate);
//		
//		double expectedMMC = expectedLookupWaitMMC + expectedStoreWaitMMC;

		// M/M/1
		
		double expectedLookupWaitMM1 = calculateWaitMM1(arrivalLookupRate, serviceLookupRate);
		double expectedStoreWaitMM1 = calculateWaitMM1(arrivalStoreRate, serviceStoreRate);
		
		double expectedMM1 = expectedLookupWaitMM1 + expectedStoreWaitMM1;
		
		return expectedMM1;
	}

	
	public double getAverageFindRequest() {
		return calculateAverage(periodicFindRequestList);
	}

	public double getAverageLookupRequest() {
		return calculateAverage(periodicLookupRequestList);
	}
	
	public double getAverageStoreRequest() {
		return calculateAverage(periodicStoreRequestList);
	}	
	
	public double getAverageFindThroughput() {
		double average = calculateAverage(findThroughputList);
		if (average != 0)
			lastFindThroughput = average;
		else
			average = lastFindThroughput;
			
		return average;
	}

	public double getAverageLookupThroughput() {
		double average = calculateAverage(lookupThroughputList);
		if (average != 0)
			lastLookupThroughput = average;
		else
			average = lastLookupThroughput;
			
		return average;
	}
	
	public double getAverageStoreThroughput() {
		double average = calculateAverage(storeThroughputList);
		if (average != 0)
			lastStoreThroughput = average;
		else
			average = lastStoreThroughput;
			
		return average;
	}
	
//	public double getAverageLookupDuration() {
//		double average = calculateAverage(lookupDurationList);
//		if (average != 0)
//			lastLookupDuration = average;
//		else
//			average = lastLookupThroughput;
//			
//		return average;
//	}
	
	public double getAverageFindArrival() {
		return calculateAverage(periodicFindArrivalList);
//		double average = calculateAverage(periodicFindArrival);
//		if (average != 0)
//			lastFindArrival = average;
//		else
//			average = lastFindArrival;
//		
//		return average; 
	}

	public double getAverageLookupArrival() {
		return calculateAverage(periodicLookupArrivalList);
//		double average = calculateAverage(periodicLookupArrival);
//		if (average != 0)
//			lastLookupArrival = average;
//		else
//			average = lastLookupArrival;
//		
//		return average; 
	}
	
	public double getAverageStoreArrival() {
		return calculateAverage(periodicStoreArrivalList);
//		double average = calculateAverage(periodicStoreArrival);
//		if (average != 0)
//			lastStoreArrival = average;
//		else
//			average = lastStoreArrival;
//		
//		return average; 
	}
	
	public double getAverageFind() {
		return calculateAverage(findRequestList);
	}
	
	public double getAverageLookup() {
		return calculateAverage(lookupRequestList);
	}
	
	public double getAverageStore() {
		return calculateAverage(storeRequestList);
	}
	
	public double getVarianceFindArrival() {
		return calculateVariance(periodicFindArrivalList);
	}

	public double getVarianceLookupArrival() {
		return calculateVariance(periodicLookupArrivalList);
	}

	public double getVarianceStoreArrival() {
		return calculateVariance(periodicStoreArrivalList);
	}
	
	public double getVarianceFindService() {
		return calculateVariance(findThroughputList);
	}
	
	public double getVarianceLookupService() {
		return calculateVariance(lookupThroughputList);
	}
	
	public double getVarianceStoreService() {
		return calculateVariance(storeThroughputList);
	}	
	
	public <T> double calculateVariance(ArrayList<T> list) {
		double result = 0.0, variance = 0.0;
		
		double mean = calculateAverage(list);
		
		for (T entry : list) {
			variance += Math.pow(Double.parseDouble(entry.toString()) - mean, 2);
		}
		
		result = variance/list.size();
		
		return result;
	}
	
//	// M/M/1
//	private double calculateExpectedQueueMM1(double arrivalRate, double serviceRate) {
//
//		double result = 0.0;
//
//		//double utilizationRate = arrivalRate/serviceRate;
//		//result = utilizationRate/(1-utilizationRate);
//		//result = arrivalRate / (serviceRate - arrivalRate);
//		
//		// lambda^2 / (mu * (mu – lambda))
//		result = Math.pow(arrivalRate, 2) / (serviceRate * (serviceRate - arrivalRate));
//		
//		return result;
//	}
//	
//	// M/M/1
//	private double calculateExpectedSystemMM1(double arrivalRate, double serviceRate) {
//
//		double result = 0.0;
//		
//		if (arrivalRate > 0 && serviceRate > 0) {
//			result = arrivalRate / (serviceRate - arrivalRate);
//		}
//		
//		return result;
//	}
//	
//	// G/G/1
//	private double calculateExpectedQueueGG1(double arrivalRate, double serviceRate) {
//
//		double result = 0.0;
//
//		if (arrivalRate > 0 && serviceRate > 0) {
//			double utilizationRate = arrivalRate/serviceRate;
//			result = utilizationRate/(1-utilizationRate);
//		}
//		//result = arrivalRate / (serviceRate - arrivalRate);
//		
//		return result;
//	}
//	
//	// G/G/1
//	private double calculateExpectedSystemGG1(double arrivalRate, double serviceRate, double arrivalVariance, double serviceVariance) {
//
//		double result = 0.0;
//
//		if (arrivalRate > 0 && serviceRate > 0) {
//			double utilizationRate = arrivalRate/serviceRate;
//			double arrivalCoefficient = arrivalVariance / Math.pow(1/arrivalRate, 2);
//			double serviceCoefficient = serviceVariance / Math.pow(1/serviceRate, 2);
//
//			result = Math.pow(utilizationRate, 2) * (arrivalCoefficient+serviceCoefficient) / (2*(1-utilizationRate));
//		}
//
//		return result;
//	}	
	
	// M/M/C
	private double calculateProbabilityZeroMMC(int numServer, double arrivalRate, double serviceRate) {
		// formula: P0 = 1 / ( sigma_n=0_c-1(((lambda/mu)^n)/n!) + (((lambda/mu)^c)/c!) + (1-(1-(lambda/(c*mu)))))
		double probabilityZero = 0.0;
		
		if (arrivalRate == 0 || serviceRate == 0)
			return probabilityZero;

		double a = 0.0, b=0.0, c = 0.0;
				
		for (int n=0; n<numServer; n++) {
			a = Math.pow(arrivalRate/serviceRate, n) / factorial(n);
		}
		
		b = Math.pow(arrivalRate/serviceRate, numServer) / factorial(numServer);
		
		c = 1 / (1-(arrivalRate/(numServer*serviceRate)));
				
		probabilityZero = 1 / (a + (b * c));

		return probabilityZero;
	}
	
	// M/M/C
	private double calculateQueueLengthMMC(int numServer, double arrivalRate, double serviceRate) {
		// formula: Lq = (P0 * (lambda/mu)^c * rho) / (c! * (1-rho)^2), rho = (lambda / (c*mu))
		double expectedQueue = 0.0;
		
		if (arrivalRate == 0 || serviceRate == 0)
			return expectedQueue;
		
		double utilizationRate = arrivalRate / (numServer * serviceRate);		
		
		double probabilityZero = calculateProbabilityZeroMMC(numServer, arrivalRate, serviceRate);
		
		double a = probabilityZero * Math.pow((arrivalRate/serviceRate), numServer) * utilizationRate;
		double b = factorial(numServer) * Math.pow(1-utilizationRate, 2); 
		
		expectedQueue = a/b;
		
		return expectedQueue;
	}

	
	// M/M/C
	private double calculateWaitMMC(int numServer, double arrivalRate, double serviceRate) {
		// formula: Lq = lambda*Wq => Wq = Lq/lambda, W = Wq + 1/mu => W = (Lq/lambda) + 1/mu
		double waitTime = 0.0;
		
		if (arrivalRate == 0 || serviceRate == 0)
			return waitTime;
		
		double queueLength = calculateQueueLengthMMC(numServer, arrivalRate, serviceRate);
		
		waitTime = (queueLength/arrivalRate) + (1/serviceRate);
		
		return waitTime;
	}

	// M/M/1
	private double calculateQueueLengthMM1(double arrivalRate, double serviceRate) {
		// formula: Lq = rho^2 / (1-rho) OR lambda^2 / mu(mu-lambda)
		if (arrivalRate == 0 || serviceRate == 0)
			return 0;
		
		return Math.pow(arrivalRate, 2) / (serviceRate * (serviceRate - arrivalRate)); 
	}
	
	// M/M/1
	private double calculateWaitMM1Alt(double arrivalRate, double serviceRate) {
		// formula: Wq = Lq / lambda, W = Wq + 1/mu
		double waitTime = 0.0;
		
		if (arrivalRate == 0 || serviceRate == 0)
			return 0;
		
		double waitQueue = calculateQueueLengthMM1(arrivalRate, serviceRate) / arrivalRate;
		
		return waitQueue + (1/serviceRate);
	}
	
	// M/M/1
	private double calculateWaitMM1(double arrivalRate, double serviceRate) {
		// formula: W = 1 / (mu - lambda)
		if (arrivalRate == 0 || serviceRate == 0)
			return 0;
		
		return (1 / (serviceRate - arrivalRate));
	}
	
	// M/M/C
//	private double calculateExpectedSystemMMC(int numServer, double arrivalRate, double serviceRate) {
//		double result = 0.0;
//		
//		double wq = calculateExpectedQueueMMC(numServer, arrivalRate, serviceRate) / arrivalRate;
//		
//		double w = wq + (1/serviceRate);
//		
//		result = arrivalRate * w;
//		
//		return result;
//	}
	
	
	private long factorial (int n) {
		return LongStream.rangeClosed(1, n)
		        .reduce(1, (long x, long y) -> x * y);
	}
	
	private <T> double calculateAverage(ArrayList<T> list) {
		// calculate average
		double average = 0.0;
		double tmp = 0.0;
		
		// need to create snapshot of the array because it can be modified
		// by other process
		ArrayList<T> snapshot = new ArrayList<T>(list);
		
		int size = snapshot.size();
		
		if (size > 0) {
			for (T entry : snapshot) {
				tmp += Double.parseDouble(entry.toString());
			}
			average = tmp / size;
		}
		
		// immediately set to null so can be GC'ed
		snapshot = null;
		
		return average;
	}
	
    @Override
    public synchronized void initiateFind() {
    	if (actualNode != null) {
    		actualNode.initiateFind();
    	}
    	else {
        	findRequest++;
        	periodicFindRequest++;
        	log.info(String.format("[initiateFind] Find Request=%s, Periodic Find=%s", findRequest, periodicFindRequest));
    	}
    }

    @Override
    public synchronized void initiateLookup() {
    	if (actualNode != null) {
    		actualNode.initiateLookup();
    	}
    	else {
        	lookupRequest++;
        	periodicLookupRequest++;
        	log.info(String.format("[initiateLookup] Lookup Request=%s, Periodic Lookup=%s", lookupRequest, periodicLookupRequest));
    	}
    }
    
    @Override
    public synchronized void initiateStore() {
    	if (actualNode != null) {
    		actualNode.initiateStore();
    	}
    	else {
        	storeRequest++;
        	periodicStoreRequest++;
        	log.info(String.format("[initiateStore] Store=%s, Periodic Store=%s", storeRequest, periodicStoreRequest));
    	}    	
    }
    
    @Override
    public synchronized void completeFind(long duration) {
    	
    	if (actualNode != null) {
    		actualNode.completeFind(duration);
    	}
    	else {
    		if (findRequest > 0)
    			findRequest--;
    		
    		// add duration to list
    		// will only keep latest <ENTRY_SIZE> entries
    		//if (findDurationList.size() > ((P2PNodeSettings)this.getNodeSettings()).getEntrySize())
    		if (findDurationList.size() > entrySize)    		
    			findDurationList.remove(0);
    		
    		lastFindDuration = duration;
    		findDurationList.add(duration);
    		
    		// calculate throughput and add to list
    		// will only keep latest 100 entries
    		//if (findThroughputList.size() > ((P2PNodeSettings)this.getNodeSettings()).getEntrySize())
    		if (findThroughputList.size() > entrySize)
    			findThroughputList.remove(0);
    		
    		//double throughput = 1.0/(duration*0.001);
    		double throughput = 0.0;;
    		
    		if (duration > 0)
    			throughput = 1.0/duration; // transaction per millisecond

    		findThroughputList.add(throughput); 
    		
    		log.info(String.format("[completeFind] findRequest=%s, duration=%s, throughput=%s, throughput list=%s", lookupRequest, duration, throughput, findThroughputList));
    	}
    }
    
    @Override
    public synchronized void completeLookup(long duration) {
    	if (actualNode != null) {
    		actualNode.completeLookup(duration);
    	}
    	else {
    		if (lookupRequest > 0)
    			lookupRequest--;
    		
    		// add duration to list
    		// will only keep latest <ENTRY_SIZE> entries
    		//if (lookupDurationList.size() > ((P2PNodeSettings)this.getNodeSettings()).getEntrySize())
    		if (lookupDurationList.size() > entrySize)
    			lookupDurationList.remove(0);
    		
    		lastLookupDuration = duration;
    		lookupDurationList.add(duration);
    		
    		// calculate throughput and add to list
    		// will only keep latest <ENTRY_SIZE> entries
    		//if (lookupThroughputList.size() > ((P2PNodeSettings)this.getNodeSettings()).getEntrySize())
    		if (lookupThroughputList.size() > entrySize)
    			lookupThroughputList.remove(0);
    		
    		//double throughput = 1.0/(duration*0.001);
    		double throughput = 0.0;;
    		
    		if (duration > 0)
    			throughput = 1.0/duration; // transaction per millisecond

    		lookupThroughputList.add(throughput);
    		
    		log.info(String.format("[completeLookup] lookupRequest=%s, duration=%s, throughput=%s, throughput list=%s", lookupRequest, duration, throughput, lookupThroughputList));
    	}
    }
    
    @Override
    public synchronized void completeStore(long duration) {
    	if (actualNode != null) {
    		actualNode.completeStore(duration);
    	}
    	else {
        	if (storeRequest > 0)
    			storeRequest--;
    		
    		// add duration to list
    		// will only keep latest <ENTRY_SIZE> entries
    		//if (storeDurationList.size() > ((P2PNodeSettings)this.getNodeSettings()).getEntrySize())
        	if (storeDurationList.size() > entrySize)
    			storeDurationList.remove(0);
    		
    		lastStoreDuration = duration;
    		storeDurationList.add(duration);
    		
        	// calculate throughput and add to list
    		// will only keep latest <ENTRY_SIZE> entries
    		//if (storeThroughputList.size() > ((P2PNodeSettings)this.getNodeSettings()).getEntrySize())
    		if (storeThroughputList.size() > entrySize)
    			storeThroughputList.remove(0);
    		
    		//double throughput = 1.0/(duration*0.001);
    		double throughput = 0.0;;
    		
    		if (duration > 0)
    			throughput = 1.0/duration; // transaction per millisecond
    		
    		storeThroughputList.add(throughput);
    		
    		log.info(String.format("[completeStore] storeRequest=%s, duration=%s, throughput=%s, throughput list=%s", storeRequest, duration, throughput, storeThroughputList));    		
    	}
    }
    
    public synchronized void resetPeriodicRequest() {
    	periodicFindRequest = 0;
    	periodicLookupRequest = 0;
    	periodicStoreRequest = 0;
    }
    
    public synchronized void resetFindThroughputList() {
		// temporary handling to avoid NaN during average calculation
		// remove all elements except the last one (at the tail)
    	int size = findThroughputList.size();
    	if (size > 1) {
			Double last = findThroughputList.get(size-1);
			findThroughputList = new ArrayList<Double>();
			findThroughputList.add(last);
		}
    }
    
    public synchronized void resetLookupThroughputList() {
		// temporary handling to avoid NaN during average calculation
		// remove all elements except the last one (at the tail)
    	int size = lookupThroughputList.size();
    	if (size > 1) {
			Double last = lookupThroughputList.get(size-1);
			lookupThroughputList = new ArrayList<Double>();
			lookupThroughputList.add(last);
		}
    }
    
    public synchronized void resetStoreThroughputList() {
		// temporary handling to avoid NaN during average calculation
		// remove all elements except the last one (at the tail)
    	int size = storeThroughputList.size();
    	if (size > 1) {
			Double last = storeThroughputList.get(size-1);
			storeThroughputList = new ArrayList<Double>();
			storeThroughputList.add(last);
		}
    }
    
    // this method is being called by a Timer from P2PNodeServer every <ESTIMATE_PERIOD>
    // it registers periodic number of task 
    public void updatePeriodicArrivalRate() {
    	
    	//int entrySize = ((P2PNodeSettings)this.getNodeSettings()).getEntrySize();
    	
    	//System.out.println("[P2PNode.updatePeriodicArrivalRate] Entry Size="+entrySize);
		
    	if (periodicFindArrivalList.size() > entrySize)
			periodicFindArrivalList.remove(0);
		periodicFindArrivalList.add(periodicFindRequest);
		
		if (periodicLookupArrivalList.size() > entrySize)
			periodicLookupArrivalList.remove(0);
		periodicLookupArrivalList.add(periodicLookupRequest);

		if (periodicStoreArrivalList.size() > entrySize)
			periodicStoreArrivalList.remove(0);			
		periodicStoreArrivalList.add(periodicStoreRequest);

    	if (findRequestList.size() > entrySize)
    		findRequestList.remove(0);
    	findRequestList.add(findRequest);
		
    	if (lookupRequestList.size() > entrySize)
    		lookupRequestList.remove(0);
    	lookupRequestList.add(lookupRequest);

    	if (storeRequestList.size() > entrySize)
    		storeRequestList.remove(0);
    	storeRequestList.add(storeRequest);
    	
		resetPeriodicRequest();
    }
    
}
