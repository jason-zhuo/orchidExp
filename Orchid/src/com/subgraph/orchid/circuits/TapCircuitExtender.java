package com.subgraph.orchid.circuits;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.logging.Logger;

import com.subgraph.orchid.CircuitNode;
import com.subgraph.orchid.RelayCell;
import com.subgraph.orchid.Router;
import com.subgraph.orchid.crypto.TorMessageDigest;
import com.subgraph.orchid.crypto.TorPublicKey;
import com.subgraph.orchid.crypto.TorTapKeyAgreement;
import com.subgraph.orchid.data.HexDigest;
import com.subgraph.orchid.data.IPv4Address;

public class TapCircuitExtender {
	private final static Logger logger = Logger
			.getLogger(TapCircuitExtender.class.getName());
	// zhuo add file write:
	File Scanresult = new File("destroy_result");
	private final CircuitExtender extender;
	private final TorTapKeyAgreement kex;
	private final Router router; // / 这个不是第一个入口路由 而是我们要拓展到的目标节点

	public TapCircuitExtender(CircuitExtender extender, Router router) {
		this.extender = extender;
		this.router = router;
		this.kex = new TorTapKeyAgreement(router.getOnionKey());
	}

	public void sycronizdedSender(RelayCell cell)
	{
		String  msg = "Extending to " + router.getNickname()+" "+router.getAddress();
		synchronized (CircuitIO.writeFilelock){			
			WriteToFile(msg);
			extender.sendRelayCell(cell);
			while(!CircuitIO.isReceived)
			{
				try {
					CircuitIO.isReceived=false;
					CircuitIO.writeFilelock.wait(1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			WriteToFile("\n");
			
		}
		
	}
	public void waitUntilReceived() {
		synchronized (CircuitIO.recievelock) {
			while(!CircuitIO.isReceived) {
				try {
					CircuitIO.recievelock.wait();
				} catch (InterruptedException e) {
				}
			}
			CircuitIO.isReceived= false;
		}
	}

	public CircuitNode extendTo() {
		logger.fine("Extending to " + router.getNickname() + " with TAP");
		final RelayCell cell = createRelayExtendCell();
		
		//sycronizdedSender(cell);//zhuo : 这里需要线程同步
		
		extender.sendRelayCell(cell);
		final RelayCell response = extender.receiveRelayResponse(
				RelayCell.RELAY_EXTENDED, router);
		if (response == null) {
			return null;
		}
		return processExtendResponse(response);
	}

	// zhuo add:
	public void WriteToFile(String msg) {
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(Scanresult,true));
			bw.write(msg);
			bw.flush();
		} catch (IOException e) {
			System.out.println("response file open error!");
		}
	}

	/**
	 * extend response handler
	 * 
	 * @param response
	 * @return
	 */
	private CircuitNode processExtendResponse(RelayCell response) {
		final byte[] handshakeResponse = new byte[TorTapKeyAgreement.DH_LEN
				+ TorMessageDigest.TOR_DIGEST_SIZE];
		response.getByteArray(handshakeResponse);

		final byte[] keyMaterial = new byte[CircuitNodeCryptoState.KEY_MATERIAL_SIZE];
		final byte[] verifyDigest = new byte[TorMessageDigest.TOR_DIGEST_SIZE];
		if (!kex.deriveKeysFromHandshakeResponse(handshakeResponse,
				keyMaterial, verifyDigest)) {
			return null;
		}
		return extender.createNewNode(router, keyMaterial, verifyDigest);
	}

	/**
	 * key method:for relay cell construction
	 * 
	 * @return
	 */
	private RelayCell createRelayExtendCell() {
		final RelayCell cell = extender.createRelayCell(RelayCell.RELAY_EXTEND);
		IPv4Address adr = router.getAddress();
		TorPublicKey key = router.getOnionKey();
		HexDigest indentity = router.getIdentityHash();
		byte[] by = kex.createOnionSkin();

		IPv4Address address = IPv4Address.createFromString("127.0.0.1");
		cell.putByteArray(address.getAddressDataBytes());
		cell.putShort(1);

		// cell.putByteArray(adr.getAddressDataBytes());
		// cell.putShort(router.getOnionPort());

		cell.putByteArray(by);
		cell.putByteArray(indentity.getRawBytes());
		return cell;
	}
}
