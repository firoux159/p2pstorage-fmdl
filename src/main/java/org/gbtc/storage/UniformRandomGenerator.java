package org.gbtc.storage;

import java.math.BigInteger;
import java.util.Random;

public final class UniformRandomGenerator {

	private final Random random;
	private final int bit;

	public UniformRandomGenerator(int bit) {
		this.bit = bit;
		this.random = new Random();
	}
	
	public UniformRandomGenerator(int bit, Random random) {
		this.bit = bit;
		this.random = random;
	}
	
	public UniformRandomGenerator(int bit, long seed) {
		this.bit = bit;
		this.random = new Random(seed);
	}
	
	public final BigInteger generate() {
		return new BigInteger(this.bit, this.random);
	}

}
