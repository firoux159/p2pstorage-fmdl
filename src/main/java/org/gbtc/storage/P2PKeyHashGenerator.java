package org.gbtc.storage;

import io.ep2p.kademlia.exception.UnsupportedBoundingException;
import io.ep2p.kademlia.node.KeyHashGenerator;
import io.ep2p.kademlia.util.BoundedHashUtil;

import java.math.BigInteger;

public class P2PKeyHashGenerator implements KeyHashGenerator<BigInteger, String> {
    
	private int identifierSize;
	
	public P2PKeyHashGenerator(int identifierSize) {
		this.identifierSize = identifierSize;
	}
	
	public BigInteger generateHash(String key) {
        try {
        	// note: the hashcode AND-ed with 0x7fffffff to turn-off the negative sign
        	// so that the resulting hashcode is positive
            return new BoundedHashUtil(identifierSize).hash(key.hashCode() & 0x7fffffff, BigInteger.class);
        } 
        catch (UnsupportedBoundingException e) {
            e.printStackTrace();
        }
        
        return BigInteger.valueOf(key.hashCode());
    }
	
    public BigInteger generateHash(int key) {
        try {
        	return new BoundedHashUtil(identifierSize).hash(key, BigInteger.class);
        } 
        catch (UnsupportedBoundingException e) {
            e.printStackTrace();
        }
        
        return BigInteger.valueOf(key);
    }	
}
