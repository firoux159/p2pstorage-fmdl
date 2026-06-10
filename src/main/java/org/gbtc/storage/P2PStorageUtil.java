package org.gbtc.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import com.google.gson.*;

public class P2PStorageUtil {
	
	public static String readNodeAddress() {
		String homeDir = System.getProperty("user.home");
		String cometbftDir = homeDir + "/.cometbft";
		String keyConfig = cometbftDir + "/config/priv_validator_key.json";
		Path path = Paths.get(keyConfig);
		
		String address = "1234567";
		
	    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
		    // create Gson instance
		    Gson gson = new Gson();
		    
		    // convert JSON file to map
		    Map<String, Object> map = gson.fromJson(reader, Map.class);
		    address = (String) map.get("address");
	    } catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	    
	    System.out.println("[readNodeId] Value read from file: "+address);
	    
	    return address;
	}	

}
