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
			circuit.notifyCircuitBuildStart();//通告：开始链路建立
			creationRequest.choosePath();//对链路请求中的路径进行路径选择
			if(logger.isLoggable(Level.FINE)) {
				logger.fine("Opening a new circuit to "+ pathToString(creationRequest));
			}
			if (this.firstRouter==null) {//没有设定默认的第一跳，则选路获取
				firstRouter = creationRequest.getPathElement(0);//确定第一跳路由	
			}			
			openEntryNodeConnection(firstRouter);//未知
			buildCircuit(firstRouter);//建立链路，与第一路由
			circuit.notifyCircuitBuildCompleted();//通告：链路建立
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
	 * 第一跳网络连接
	 * @param firstRouter
	 * @throws ConnectionTimeoutException
	 * @throws ConnectionFailedException
	 * @throws ConnectionHandshakeException
	 * @throws InterruptedException
	 */
	private void openEntryNodeConnection(Router firstRouter) throws ConnectionTimeoutException, ConnectionFailedException, ConnectionHandshakeException, InterruptedException {
		connection = connectionCache.getConnectionTo(firstRouter, creationRequest.isDirectoryCircuit());//连接
		circuit.bindToConnection(connection);//链路绑定连接
		creationRequest.connectionCompleted(connection);//连接完成
	}

	private void buildCircuit(Router firstRouter) throws TorException {
		notifyInitialization();
		System.out.println("creating fast to FirstRouter : " + firstRouter.toString());
		final CircuitNode firstNode = extender.createFastTo(firstRouter);//建立链路//链路节点包含路由信息
		creationRequest.nodeAdded(firstNode);//插入链路节点
		
		
		//链路扩展extend to：默认数目为5（需修改）（已经可以通过选路函数修改）
		for(int i = 1; i < creationRequest.getPathLength(); i++) {
		//for(int i = 1; i < 2; i++) {
			Router node = creationRequest.getPathElement(i);//获取新路由节点			
			//扩展链路到新的路由节点
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
		
		creationRequest.circuitBuildCompleted(circuit);//确认链路建立
		notifyDone();//通告：完成链路建立（含扩展）
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
