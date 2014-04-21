package com.subgraph.orchid.data;

import static org.junit.Assert.*;

import org.junit.Test;

public class IPv4AddressTest {

	@Test
	public void test() {
		IPv4Address address = IPv4Address.createFromString("127.0.0.1");
		byte [] ad = address.getAddressDataBytes();
	}

}
