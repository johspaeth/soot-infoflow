/*******************************************************************************
 * Copyright (c) 2012 Secure Software Engineering Group at EC SPRIDE.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors: Christian Fritz, Steven Arzt, Siegfried Rasthofer, Eric
 * Bodden, and others.
 ******************************************************************************/
package soot.jimple.infoflow.solver.heros;

import heros.EdgeFunction;
import heros.FlowFunction;
import heros.edgefunc.EdgeIdentity;
import heros.solver.CountingThreadPoolExecutor;
import heros.solver.IFDSSolver;
import heros.solver.Pair;
import heros.solver.PathEdge;
import heros.solver.PathTrackingIFDSSolver;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.problems.AbstractInfoflowProblem;
import soot.jimple.infoflow.solver.IFollowReturnsPastSeedsHandler;
import soot.jimple.infoflow.solver.IInfoflowSolver;
import soot.jimple.infoflow.solver.IMemoryManager;
import soot.jimple.infoflow.solver.functions.SolverCallFlowFunction;
import soot.jimple.infoflow.solver.functions.SolverCallToReturnFlowFunction;
import soot.jimple.infoflow.solver.functions.SolverNormalFlowFunction;
import soot.jimple.infoflow.solver.functions.SolverReturnFlowFunction;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;

import com.google.common.collect.Table;
/**
 * We are subclassing the JimpleIFDSSolver because we need the same executor for both the forward and the backward analysis
 * Also we need to be able to insert edges containing new taint information
 * 
 */
public class InfoflowSolver extends PathTrackingIFDSSolver<Unit, Abstraction, SootMethod, BiDiInterproceduralCFG<Unit, SootMethod>>
		implements IInfoflowSolver {
	
	private IFollowReturnsPastSeedsHandler followReturnsPastSeedsHandler = null;
	
	public InfoflowSolver(AbstractInfoflowProblem problem, CountingThreadPoolExecutor executor) {
		super(problem);
		problem.setSolver(this);		
	}
	
	@Override
	protected CountingThreadPoolExecutor getExecutor() {
		return null;
	}

	public boolean processEdge(PathEdge<Unit, Abstraction> edge){
		// We are generating a fact out of thin air here. If we have an
		// edge <d1,n,d2>, there need not necessarily be a jump function
		// to <n,d2>.
		if (!jumpFn.forwardLookup(edge.factAtSource(), edge.getTarget()).containsKey(edge.factAtTarget())) {
			propagate(edge.factAtSource(), edge.getTarget(), edge.factAtTarget(),
					EdgeIdentity.<IFDSSolver.BinaryDomain>v(), null, false);
			return true;
		}
		return false;
	}
	
	@Override
	public void injectContext(IInfoflowSolver otherSolver, SootMethod callee, Abstraction d3,
			Unit callSite, Abstraction d2, Abstraction d1) {
	}
	

	@Override
	protected Set<Abstraction> computeNormalFlowFunction
			(FlowFunction<Abstraction> flowFunction, Abstraction d1, Abstraction d2) {
		if (flowFunction instanceof SolverNormalFlowFunction)
			return ((SolverNormalFlowFunction) flowFunction).computeTargets(d1, d2);
		else
			return flowFunction.computeTargets(d2);
	}

	@Override
	protected Set<Abstraction> computeCallToReturnFlowFunction
			(FlowFunction<Abstraction> flowFunction, Abstraction d1, Abstraction d2) {
		if (flowFunction instanceof SolverCallToReturnFlowFunction)
			return ((SolverCallToReturnFlowFunction) flowFunction).computeTargets(d1, d2);
		else
			return flowFunction.computeTargets(d2);		
	}

	@Override
	protected Set<Abstraction> computeCallFlowFunction
			(FlowFunction<Abstraction> flowFunction, Abstraction d1, Abstraction d2) {
		if (flowFunction instanceof SolverCallFlowFunction)
			return ((SolverCallFlowFunction) flowFunction).computeTargets(d1, d2);
		else
			return flowFunction.computeTargets(d2);		
	}

	@Override
	protected void propagate(Abstraction sourceVal, Unit target, Abstraction targetVal, EdgeFunction<BinaryDomain> f,
			/* deliberately exposed to clients */ Unit relatedCallSite,
			/* deliberately exposed to clients */ boolean isUnbalancedReturn) {	
		// Check whether we already have an abstraction that entails the new one.
		// In such a case, we can simply ignore the new abstraction.
		boolean noProp = false;
		/*
		for (Abstraction abs : new HashSet<Abstraction>(jumpFn.forwardLookup(sourceVal, target).keySet()))
			if (abs != targetVal) {
				if (abs.entails(targetVal)) {
					noProp = true;
					break;
				} 
				if (targetVal.entails(abs)) {
					jumpFn.removeFunction(sourceVal, target, abs);
				}
			}
		*/
		if (!noProp)
			super.propagate(sourceVal, target, targetVal, f, relatedCallSite, isUnbalancedReturn);
	}

	/**
	 * Cleans up some unused memory. Results will still be available afterwards,
	 * but no intermediate computation values.
	 */
	public void cleanup() {
		this.jumpFn.clear();
		this.incoming.clear();
		this.endSummary.clear();
		this.val.clear();
		this.cache.clear();
	}

	@Override
	public Set<Pair<Unit, Abstraction>> endSummary(SootMethod m, Abstraction d3) {
		Set<Pair<Unit, Abstraction>> res = null;
		
		for (Unit sP : icfg.getStartPointsOf(m)) {
			Set<Table.Cell<Unit,Abstraction,EdgeFunction<IFDSSolver.BinaryDomain>>> endSum =
					super.endSummary(sP, d3);
			if (endSum == null || endSum.isEmpty())
				continue;
			if (res == null)
				res = new HashSet<>();
			
			for (Table.Cell<Unit,Abstraction,EdgeFunction<IFDSSolver.BinaryDomain>> cell : endSum)
				res.add(new Pair<>(cell.getRowKey(), cell.getColumnKey()));
		}
		return res;
	}
	
	@Override
	protected void processExit(PathEdge<Unit, Abstraction> edge) {
		super.processExit(edge);
		
	}
	
	@Override
	public void setFollowReturnsPastSeedsHandler(IFollowReturnsPastSeedsHandler handler) {
		this.followReturnsPastSeedsHandler = handler;
	}

	@Override
	public IMemoryManager<Abstraction> getMemoryManager() {
		return null;
	}
	
}
