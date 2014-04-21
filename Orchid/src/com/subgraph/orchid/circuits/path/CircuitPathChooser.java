package com.subgraph.orchid.circuits.path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.subgraph.orchid.Directory;
import com.subgraph.orchid.Router;
import com.subgraph.orchid.TorConfig;
import com.subgraph.orchid.circuits.guards.EntryGuards;
import com.subgraph.orchid.circuits.path.CircuitNodeChooser.WeightRule;
import com.subgraph.orchid.data.IPv4Address;
import com.subgraph.orchid.data.exitpolicy.ExitTarget;

public class CircuitPathChooser {
	
	// zhuo added: save the router list to be extend later
		public static Set<Router> ExtendRouterList=new HashSet<Router>();
	public static CircuitPathChooser create(TorConfig config, Directory directory) {
		return new CircuitPathChooser(config, directory, new CircuitNodeChooser(config, directory));
	}

	private final Directory directory;
	private final CircuitNodeChooser nodeChooser;

	private EntryGuards entryGuards;
	private boolean useEntryGuards;

	CircuitPathChooser(TorConfig config, Directory directory, CircuitNodeChooser nodeChooser) {
		this.directory = directory;
		this.nodeChooser = nodeChooser;
		this.entryGuards = null;
		this.useEntryGuards = false;
	}

	public void enableEntryGuards(EntryGuards entryGuards) {
		this.entryGuards = entryGuards;
		this.useEntryGuards = true;
	}

	public List<Router> chooseDirectoryPath() throws InterruptedException {
		//guard选路模式
		if(useEntryGuards && entryGuards.isUsingBridges()) {
			final Set<Router> empty = Collections.emptySet();
			final Router bridge = entryGuards.chooseRandomGuard(empty);//守护线程：随机选取节点
			if(bridge == null) {
				throw new IllegalStateException("Failed to choose bridge for directory request");
			}
			return Arrays.asList(bridge);
		}

		//dir选路模式
		final Router dir = nodeChooser.chooseDirectory();//从dir选取节点
		return Arrays.asList(dir);
	}

	public List<Router> chooseInternalPath() throws InterruptedException, PathSelectionFailedException {
		final Set<Router> excluded = Collections.emptySet();
		final Router finalRouter = chooseMiddleNode(excluded);//final:exit:choose as middle
		return choosePathWithFinal(finalRouter);
	}

	public List<Router> choosePathWithExit(Router exitRouter) throws InterruptedException, PathSelectionFailedException {
		return choosePathWithFinal(exitRouter);
	}

	/**
	 * build the list which contains routers to be extended
	 * @author zzl
	 */
	public void BuildExtendtedRouterList(Set<Router> excluded) throws PathSelectionFailedException
	{
		final List<Router> fengMiddleRouters=chooseMiddleNodes(excluded);
		for (int i = 0; i < fengMiddleRouters.size(); i++) {
			final Router fengMiddleRouter = fengMiddleRouters.get(i);
			if(fengMiddleRouter == null) {
				throw new PathSelectionFailedException("BuildExtendtedRouterList: Failed to select suitable middle node");
			}
			excludeChosenRouterAndRelated(fengMiddleRouter, excluded);	
			ExtendRouterList.add(fengMiddleRouter); 
		}
		
	}
	
	/**
	 * Get the router by nickname
	 * @author zzl 
	 * @return the Router
	 */
	 public Router GetRouterByName(String name)
	 {
		 Router target= null;
		 Iterator<Router> iter = ExtendRouterList.iterator();
		 while(iter.hasNext())
		 {
			 target =iter.next();
			 if(target.getNickname().equalsIgnoreCase(name))
			 {
				 return target;
			 }
		 }
		 return null;
	 }	/** added
	 * @author zzl 
	 * @return the RouterList to be Extended
	 */
	public Set<Router> GetExtendedRouterlist (){
		return ExtendRouterList;
	}
	
	//Feng:change
	//zhuo revised
	public List<Router> choosePathWithFinal(Router finalRouter) throws InterruptedException, PathSelectionFailedException {
		final Set<Router> excluded = new HashSet<Router>();
		excludeChosenRouterAndRelated(finalRouter, excluded);//exclude final	
		
		final Router middleRouter = chooseMiddleNode(excluded);
		if(middleRouter == null) {
			throw new PathSelectionFailedException("Failed to select suitable middle node");
		}
		excludeChosenRouterAndRelated(middleRouter, excluded);//exclude chosen one	
		
		final Router entryRouter = chooseEntryNode(excluded);
		if(entryRouter == null) {
			throw new PathSelectionFailedException("Failed to select suitable entry node");
		}
		ExtendRouterList.add(entryRouter);	
		BuildExtendtedRouterList(excluded);
		return Arrays.asList(entryRouter, middleRouter, finalRouter);
	}

	/**
	 * 选择入口节点：（根据排除节点列表选择）
	 * method 1：使用守护进程 (默认使用)
	 * method 2：随机选择节点
	 * @param excludedRouters
	 * @return
	 * @throws InterruptedException
	 */

	public Router chooseEntryNode(final Set<Router> excludedRouters) throws InterruptedException {
		//method 1:use entry guards
		if(useEntryGuards) {
			return entryGuards.chooseRandomGuard(excludedRouters);
		}

		//method 2:choose random
		return nodeChooser.chooseRandomNode(WeightRule.WEIGHT_FOR_GUARD, new RouterFilter() {
			public boolean filter(Router router) {
				return router.isPossibleGuard() && !excludedRouters.contains(router);
			}
		});
	}


	/**
	 * 
	 * @param excludedRouters
	 * @return
	 */
	List<Router> chooseMiddleNodes(final Set<Router> excludedRouters){
		return nodeChooser.chooseCandidates(WeightRule.WEIGHT_FOR_MID, new RouterFilter() {
			public boolean filter(Router router) {
				return router.isFast() && !excludedRouters.contains(router);
			}
		});
	}
	
	
	Router chooseMiddleNode(final Set<Router> excludedRouters) {
		return nodeChooser.chooseRandomNode(WeightRule.WEIGHT_FOR_MID, new RouterFilter() {
			public boolean filter(Router router) {
				return router.isFast() && !excludedRouters.contains(router);
			}
		});
	}

	public Router chooseExitNodeForTargets(List<ExitTarget> targets) {
		final List<Router> routers = filterForExitTargets(
				getUsableExitRouters(), targets);
		return nodeChooser.chooseExitNode(routers);
	}

	private List<Router> getUsableExitRouters() {
		final List<Router> result = new ArrayList<Router>();
		for(Router r: nodeChooser.getUsableRouters(true)) {


			if(r.isExit() && !r.isBadExit()) {
				result.add(r);
			}
		}
		return result;
	}

	private void excludeChosenRouterAndRelated(Router router, Set<Router> excludedRouters) {
		excludedRouters.add(router);
		for(Router r: directory.getAllRouters()) {

			if(areInSameSlash16(router, r)) {
				excludedRouters.add(r);
			}
		}

		for(String s: router.getFamilyMembers()) {
			Router r = directory.getRouterByName(s);
			if(r != null) {
				// Is mutual?
				if(isFamilyMember(r.getFamilyMembers(), router)) {
					excludedRouters.add(r);
				}
			}
		}
	}

	private boolean isFamilyMember(Collection<String> familyMemberNames, Router r) {
		for(String s: familyMemberNames) {
			Router member = directory.getRouterByName(s);
			if(member != null && member.equals(r)) {
				return true;
			}
		}
		return false;
	}

	// Are routers r1 and r2 in the same /16 network
	private boolean areInSameSlash16(Router r1, Router r2) {
		final IPv4Address a1 = r1.getAddress();
		final IPv4Address a2 = r2.getAddress();
		final int mask = 0xFFFF0000;
		return (a1.getAddressData() & mask) == (a2.getAddressData() & mask);
	}

	private List<Router> filterForExitTargets(List<Router> routers, List<ExitTarget> exitTargets) {
		int bestSupport = 0;
		if(exitTargets.isEmpty()) {
			return routers;
		}

		final int[] nSupport = new int[routers.size()];

		for(int i = 0; i < routers.size(); i++) {
			final Router r = routers.get(i);
			nSupport[i] = countTargetSupport(r, exitTargets);
			if(nSupport[i] > bestSupport) {
				bestSupport = nSupport[i];
			}
		}

		if(bestSupport == 0) {
			return routers;
		}

		final List<Router> results = new ArrayList<Router>();
		for(int i = 0; i < routers.size(); i++) {
			if(nSupport[i] == bestSupport) {
				results.add(routers.get(i));
			}
		}
		return results;
	}

	private int countTargetSupport(Router router, List<ExitTarget> targets) {
		int count = 0;
		for(ExitTarget t: targets) {
			if(routerSupportsTarget(router, t)) {
				count += 1;
			}
		}
		return count;
	}

	private boolean routerSupportsTarget(Router router, ExitTarget target) {
		if(target.isAddressTarget()) {
			return router.exitPolicyAccepts(target.getAddress(), target.getPort());
		} else {
			return router.exitPolicyAccepts(target.getPort());
		}
	}
}
