package com.subgraph.orchid.circuits;


import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.subgraph.orchid.CircuitNode;
import com.subgraph.orchid.Connection;
import com.subgraph.orchid.ConnectionCache;
import com.subgraph.orchid.ConnectionFailedException;
import com.subgraph.orchid.ConnectionHandshakeException;
import com.subgraph.orchid.ConnectionTimeoutException;
import com.subgraph.orchid.Descriptor;
import com.subgraph.orchid.Router;
import com.subgraph.orchid.Tor;
import com.subgraph.orchid.TorException;
import com.subgraph.orchid.circuits.path.PathSelectionFailedException;
import com.subgraph.orchid.crypto.TorPublicKey;
import com.subgraph.orchid.data.HexDigest;
import com.subgraph.orchid.data.IPv4Address;

public class CircuitBuildTask implements Runnable {
	private final static Logger logger = Logger.getLogger(CircuitBuildTask.class.getName());
	private final CircuitCreationRequest creationRequest;
	private final ConnectionCache connectionCache;
	private final TorInitializationTracker initializationTracker;
	private final CircuitImpl circuit;
	private final CircuitExtender extender;
	private Connection connection = null;
	
	static Random random=new Random();
	int randomInt=random.nextInt();
	
	Router firstRouter;//Feng add
	
	/**
	 * Feng add
	 * @param firstRouter
	 * @param request
	 * @param connectionCache
	 * @param ntorEnabled
	 */
	public CircuitBuildTask(Router firstRouter,CircuitCreationRequest request, ConnectionCache connectionCache, boolean ntorEnabled) {
		this(request,connectionCache,ntorEnabled);
		this.firstRouter=firstRouter;
	}
	
	public CircuitBuildTask(CircuitCreationRequest request, ConnectionCache connectionCache, boolean ntorEnabled) {
		this(request, connectionCache, ntorEnabled, null);
		this.firstRouter=null;//Feng add
	}

	public CircuitBuildTask(CircuitCreationRequest request, ConnectionCache connectionCache, boolean ntorEnabled, TorInitializationTracker initializationTracker) {
		this.creationRequest = request;
		this.connectionCache = connectionCache;
		this.initializationTracker = initializationTracker;
		this.circuit = request.getCircuit();
		this.extender = new CircuitExtender(request.getCircuit(), ntorEnabled);
	}

	//Feng:change
	static int cnt=0;
	
	public void run() {
		//cnt++;
		//System.out.println("Feng:Circuit Build:"+cnt);
		firstRouter = null;
		try {
			circuit.notifyCircuitBuildStart();//ͨ�棺��ʼ��·����
			creationRequest.choosePath();//����·�����е�·������·��ѡ��
			if(logger.isLoggable(Level.FINE)) {
				logger.fine("Opening a new circuit to "+ pathToString(creationRequest));
			}
			if (this.firstRouter==null) {//û���趨Ĭ�ϵĵ�һ������ѡ·��ȡ
				firstRouter = creationRequest.getPathElement(0);//ȷ����һ��·��	
			}			
			openEntryNodeConnection(firstRouter);//δ֪
			buildCircuit(firstRouter);//������·�����һ·��
			circuit.notifyCircuitBuildCompleted();//ͨ�棺��·����
		} catch (ConnectionTimeoutException e) {
			connectionFailed("Timeout connecting to "+ firstRouter);
		} catch (ConnectionFailedException e) {
			connectionFailed("Connection failed to "+ firstRouter + " : " + e.getMessage());
		} catch (ConnectionHandshakeException e) {
			connectionFailed("Handshake error connecting to "+ firstRouter + " : " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			circuitBuildFailed("Circuit building thread interrupted");
		} catch(PathSelectionFailedException e) { 
			circuitBuildFailed(e.getMessage());
		} catch (TorException e) {
			circuitBuildFailed(e.getMessage());
		} catch(Exception e) {
			circuitBuildFailed("Unexpected exception: "+ e);
			logger.log(Level.WARNING, "Unexpected exception while building circuit: "+ e, e);
		}
	}

	private String pathToString(CircuitCreationRequest ccr) {
		final StringBuilder sb = new StringBuilder();
		sb.append("[");
		for(Router r: ccr.getPath()) {
			
			if(sb.length() > 1)
				sb.append(",");
			sb.append(r.getNickname());
		}
		sb.append("]");
		return sb.toString();
	}

	private void connectionFailed(String message) {
		creationRequest.connectionFailed(message);
		circuit.notifyCircuitBuildFailed();
	}
	
	private void circuitBuildFailed(String message) {
		creationRequest.circuitBuildFailed(message);
		circuit.notifyCircuitBuildFailed();
		if(connection != null) {
			connection.removeCircuit(circuit);
		}
	}
	
	/**
	 * ��һ����������
	 * @param firstRouter
	 * @throws ConnectionTimeoutException
	 * @throws ConnectionFailedException
	 * @throws ConnectionHandshakeException
	 * @throws InterruptedException
	 */
	private void openEntryNodeConnection(Router firstRouter) throws ConnectionTimeoutException, ConnectionFailedException, ConnectionHandshakeException, InterruptedException {
		connection = connectionCache.getConnectionTo(firstRouter, creationRequest.isDirectoryCircuit());//����
		circuit.bindToConnection(connection);//��·������
		creationRequest.connectionCompleted(connection);//�������
	}

	private void buildCircuit(Router firstRouter) throws TorException {
		notifyInitialization();
		System.out.println("creating fast to FirstRouter : " + firstRouter.toString());
		final CircuitNode firstNode = extender.createFastTo(firstRouter);//������·//��·�ڵ����·����Ϣ
		creationRequest.nodeAdded(firstNode);//������·�ڵ�
		
		
		//��·��չextend to��Ĭ����ĿΪ5�����޸ģ����Ѿ�����ͨ��ѡ·�����޸ģ�
		for(int i = 1; i < creationRequest.getPathLength(); i++) {
		//for(int i = 1; i < 2; i++) {
			Router node = creationRequest.getPathElement(i);//��ȡ��·�ɽڵ�			
			//��չ��·���µ�·�ɽڵ�
			System.out.println("Feng:"+"CircuitBuilder:"+firstRouter.toString()+":"+i+":"+randomInt+":extending to :"+node.toString());
			//System.out.println("extending to :"+node.toString());
			final CircuitNode extendedNode = extender.extendTo(node);  // not first node
			
			if(extendedNode==null){
				//Feng:handle the failed node
				//System.out.println("Feng:extend failed!");
			}else {
				//Feng:handle the success node
				//System.out.println("Feng:extend success!");
				creationRequest.nodeAdded(extendedNode);
			}
		}
		
		creationRequest.circuitBuildCompleted(circuit);//ȷ����·����
		notifyDone();//ͨ�棺�����·����������չ��
	}

	private void notifyInitialization() {
		if(initializationTracker != null) {
			final int event = creationRequest.isDirectoryCircuit() ? 
					Tor.BOOTSTRAP_STATUS_ONEHOP_CREATE : Tor.BOOTSTRAP_STATUS_CIRCUIT_CREATE;
			initializationTracker.notifyEvent(event);
		}
	}

	private void notifyDone() {
		if(initializationTracker != null && !creationRequest.isDirectoryCircuit()) {
			initializationTracker.notifyEvent(Tor.BOOTSTRAP_STATUS_DONE);
		}
	}
}
