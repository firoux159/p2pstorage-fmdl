package org.gbtc.storage.repository;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import io.ep2p.kademlia.repository.KademliaRepository;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class FileRepository implements KademliaRepository<String, FileDTO> {
	
	//protected final Map<String, FileDTO> data = new HashMap<>();
	protected final Map<String, String> data = new HashMap<>();
	
	private String dataPath = "";
	private int lookupDelay = 0;
	private int storeDelay = 0;	
	
    public FileRepository(String dataPath) {
    	this.dataPath = dataPath;
    }
    
    public FileRepository(String dataPath, int lookupDelay, int storeDelay) {
    	this.dataPath = dataPath;
    	this.lookupDelay = lookupDelay;
    	this.storeDelay = storeDelay;
    }
    
//	public FileRepository(FileDTO fileDTO) {
//    	// create the directory
//    	//BigInteger nodeId = BigInteger.valueOf(fileChunkDTO.getId());
//    	try {
//    		dataDirectory = String.format("peer%s", fileDTO.getId());
//            Path path = Paths.get(dataDirectory);
//            Files.createDirectories(path);
//    	}
//		catch (IOException ioe) {
//			ioe.printStackTrace();
//			// TO-DO
//		}
//    }

	@Override
    public void store(String key, FileDTO value) {
        
		log.debug("[FileRepository.store 1] key="+key+", value="+value.getName()+", ext="+value.getExternalNode());
		
		// check if the value contains a pointer (map) to the actual node
		// in such case, only need to store the map into DHT
		// no actual file
		if (value.getExternalNode() != null) {
			// NOTE: currently this logic is no longer valid because no more mapping node in the latest approach
			// this is mapping value, not content value
			// store it into DHT repository
			//data.putIfAbsent(key, value.getExternalNode());
			log.info("The Object mapping was succesfully stored into DHT");
		}
		else {
			try {
				// store data to DHT repository
				// key is the hashCode() of the filename
				// value = path of the file in the file system
				Path output = Paths.get(this.dataPath, key);
				data.putIfAbsent(key, output.toString());

				// write data to file system
				FileOutputStream fileOut = new FileOutputStream(output.toFile());
				ObjectOutputStream objectOut = new ObjectOutputStream(fileOut);
				objectOut.writeObject(value);
				objectOut.close();
				fileOut.close();
				
				if (storeDelay > 0)
					Thread.sleep(storeDelay);
				
				log.info("The Object was succesfully stored into file with delay="+storeDelay);
			} 
	        catch (Exception ex) {
	            ex.printStackTrace();
	        }
		}
    }

    
//	@Override
//    public void store(String key, FileDTO value) {
//        
//		System.out.println("[FileRepository.store 1] key="+key+", value="+value.getName()+", ext="+value.getExternalNode());
//		
//		// check if the value contains a pointer (map) to the actual node
//		// in such case, only need to store the map into DHT
//		// no actual file
//		if (value.getExternalNode() != null) {
//			// this is mapping value, not content value
//			// store it into DHT repository
//			data.putIfAbsent(key, value.getExternalNode());
//			
//			logger.info("The Object mapping was succesfully stored into DHT");
//		}
//		else {
//			try {
//				// store data to DHT repository
//				// key is the hashCode() of the filename
//				// value = path of the file in the file system
//				Path output = Paths.get(this.dataPath, key);
//				data.putIfAbsent(key, output.toString());
//
//				// write data to file system
//				FileOutputStream fileOut = new FileOutputStream(output.toFile());
//				ObjectOutputStream objectOut = new ObjectOutputStream(fileOut);
//				objectOut.writeObject(value);
//				objectOut.close();
//				fileOut.close();
//				
//				if (storeDelay > 0)
//					Thread.sleep(storeDelay);
//				
//				logger.info("The Object was succesfully stored into file with delay="+storeDelay);
//			} 
//	        catch (Exception ex) {
//	            ex.printStackTrace();
//	        }
//		}
//    }

//    @Override
//    public FileDTO get(String key) {
//        
//    	// value in the map is the path of the file in the file system
//    	Path input = Paths.get(data.get(key));
//    	//return data.get(key);
//		
//    	FileDTO fileDTO = null;
//    	// read data from file system
//    	
//        try {
//        	 
//			//Path input = Paths.get(this.dataPath, key);
//
//        	FileInputStream fileIn = new FileInputStream(input.toFile());
//            ObjectInputStream objectIn = new ObjectInputStream(fileIn);
//            fileDTO = (FileDTO) objectIn.readObject();
//
//			// artificial delay for experiments purpose
//			Thread.sleep(lookupDelay);
//			
//            logger.info("The Object  was succesfully read from a file with delay="+lookupDelay);
//
//        } 
//        catch (Exception ex) {
//            ex.printStackTrace();
//        }
//
//        return fileDTO;
//    }
    

    @Override
    public FileDTO get(String key) {
    	
    	// value in the map is the path of the file in the file system
    	// or the mapping/pointer to actual node
		String value = data.get(key);
		
    	log.debug("[get] key="+key+", value from DHT="+value);
		
    	FileDTO fileDTO = null;
    	
        try {
        	
        	long start = System.currentTimeMillis();
        	
    		if (value.startsWith(this.dataPath)) {
    			
    			// value contains the actual data
    	    	Path input = Paths.get(value);
//    	    	fileDTO = new FileDTO();
//    	    	fileDTO.setName(key);
    	    	// for experiment purpose, skip reading the content
            	FileInputStream fileIn = new FileInputStream(input.toFile());
                ObjectInputStream objectIn = new ObjectInputStream(fileIn);
                fileDTO = (FileDTO) objectIn.readObject();

    			log.debug("[get] value read from physical storage, name="+fileDTO.getName());

    		}
    		else {
    			// NOTE: currently this logic is no longer valid because no more mapping node in the latest approach
    			// value contains mapping to actual node
    	    	fileDTO = new FileDTO();
    	    	fileDTO.setName(key);
    	    	fileDTO.setExternalNode(value);
    		}
    		
    		long end = System.currentTimeMillis();
    		
    		if (fileDTO != null)
    			fileDTO.setReadLatency(end-start);
    		
			if (lookupDelay > 0)
				Thread.sleep(storeDelay);
			
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        
        log.info("The Object was succesfully read.");

        return fileDTO;
    }

    
    @Override
    public void remove(String key) {
        data.remove(key);
    }

    @Override
    public boolean contains(String key) {
        return data.containsKey(key);
    }
    
    public int getDHTSize() {
    	return data.size();
    }
    
}
