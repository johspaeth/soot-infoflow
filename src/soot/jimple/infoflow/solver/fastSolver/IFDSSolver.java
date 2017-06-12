/*******************************************************************************
 * Copyright (c) 2012 Eric Bodden.
 * Copyright (c) 2013 Tata Consultancy Services & Ecole Polytechnique de Montreal
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     Eric Bodden - initial API and implementation
 *     Marc-Andre Laverdiere-Papineau - Fixed race condition
 *     Steven Arzt - Created FastSolver implementation
 ******************************************************************************/
package soot.jimple.infoflow.solver.fastSolver;


import heros.DontSynchronize;
import heros.FlowFunction;
import heros.FlowFunctionCache;
import heros.FlowFunctions;
import heros.IFDSTabulationProblem;
import heros.SynchronizedBy;
import heros.ZeroedFlowFunctions;
import heros.solver.CountingThreadPoolExecutor;
import heros.solver.Pair;
import heros.solver.PathEdge;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.collect.ConcurrentHashSet;
import soot.jimple.infoflow.collect.MyConcurrentHashMap;
import soot.jimple.infoflow.solver.IMemoryManager;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;

import com.google.common.cache.CacheBuilder;


/**
 * A solver for an {@link IFDSTabulationProblem}. This solver is not based on the IDESolver
 * implementation in Heros for performance reasons.
 * 
 * @param <N> The type of nodes in the interprocedural control-flow graph. Typically {@link Unit}.
 * @param <D> The type of data-flow facts to be computed by the tabulation problem.
 * @param <M> The type of objects used to represent methods. Typically {@link SootMethod}.
 * @param <I> The type of inter-procedural control-flow graph being used.
 * @see IFDSTabulationProblem
 */
public class IFDSSolver<N,D extends FastSolverLinkedNode<D, N>,M,I extends BiDiInterproceduralCFG<N, M>> {
	
	public static CacheBuilder<Object, Object> DEFAULT_CACHE_BUILDER = CacheBuilder.newBuilder().concurrencyLevel
			(Runtime.getRuntime().availableProcessors()).initialCapacity(10000).softValues();
	
    protected static final Logger logger = LoggerFactory.getLogger(IFDSSolver.class);

    //enable with -Dorg.slf4j.simpleLogger.defaultLogLevel=trace
    public static final boolean DEBUG = logger.isDebugEnabled();

	protected CountingThreadPoolExecutor executor;
	
	@DontSynchronize("only used by single thread")
	protected int numThreads;
	
	@SynchronizedBy("thread safe data structure, consistent locking when used")
	protected final JumpFunctions<N,D> jumpFn;
	
	@SynchronizedBy("thread safe data structure, only modified internally")
	protected final I icfg;
	
	//stores summaries that were queried before they were computed
	//see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on 'incoming'")
	protected final MyConcurrentHashMap<Pair<M,D>,Set<Pair<N,D>>> endSummary =
			new MyConcurrentHashMap<Pair<M,D>, Set<Pair<N,D>>>();
	
	//edges going along calls
	//see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on field")
	protected final MyConcurrentHashMap<Pair<M,D>,MyConcurrentHashMap<N,Map<D, D>>> incoming =
			new MyConcurrentHashMap<Pair<M,D>,MyConcurrentHashMap<N,Map<D, D>>>();
	
	@DontSynchronize("stateless")
	protected final FlowFunctions<N, D, M> flowFunctions;
	
	@DontSynchronize("only used by single thread")
	protected final Map<N,Set<D>> initialSeeds;
	
	@DontSynchronize("benign races")
	public long propagationCount;
	
	@DontSynchronize("stateless")
	protected final D zeroValue;
	
	@DontSynchronize("readOnly")
	protected final FlowFunctionCache<N,D,M> ffCache; 
	
	@DontSynchronize("readOnly")
	protected final boolean followReturnsPastSeeds;
	
	@DontSynchronize("readOnly")
	protected boolean setJumpPredecessors = false;
	
	@DontSynchronize("readOnly")
	private boolean enableMergePointChecking = false;
	
	@DontSynchronize("readOnly")
	protected IMemoryManager<D> memoryManager = null;
	
	/**
	 * Creates a solver for the given problem, which caches flow functions and edge functions.
	 * The solver must then be started by calling {@link #solve()}.
	 */
	public IFDSSolver(IFDSTabulationProblem<N,D,M,I> tabulationProblem) {
		this(tabulationProblem, DEFAULT_CACHE_BUILDER);
	}

	/**
	 * Creates a solver for the given problem, constructing caches with the
	 * given {@link CacheBuilder}. The solver must then be started by calling
	 * {@link #solve()}.
	 * @param tabulationProblem The tabulation problem to solve
	 * @param flowFunctionCacheBuilder A valid {@link CacheBuilder} or
	 * <code>null</code> if no caching is to be used for flow functions.
	 */
	public IFDSSolver(IFDSTabulationProblem<N,D,M,I> tabulationProblem,
			@SuppressWarnings("rawtypes") CacheBuilder flowFunctionCacheBuilder) {
		if(logger.isDebugEnabled())
			flowFunctionCacheBuilder = flowFunctionCacheBuilder.recordStats();
		this.zeroValue = tabulationProblem.zeroValue();
		this.icfg = tabulationProblem.interproceduralCFG();		
		FlowFunctions<N, D, M> flowFunctions = tabulationProblem.autoAddZero() ?
				new ZeroedFlowFunctions<N,D,M>(tabulationProblem.flowFunctions(), zeroValue) : tabulationProblem.flowFunctions(); 
		if(flowFunctionCacheBuilder!=null) {
			ffCache = new FlowFunctionCache<N,D,M>(flowFunctions, flowFunctionCacheBuilder);
			flowFunctions = ffCache;
		} else {
			ffCache = null;
		}
		this.flowFunctions = flowFunctions;
		this.initialSeeds = tabulationProblem.initialSeeds();
		this.jumpFn = new JumpFunctions<N,D>();
		this.followReturnsPastSeeds = tabulationProblem.followReturnsPastSeeds();
		this.numThreads = Math.max(1,tabulationProblem.numThreads());
		this.executor = getExecutor();
	}
	
	/**
	 * Runs the solver on the configured problem. This can take some time.
	 */
	public void solve() {		
		submitInitialSeeds();
		awaitCompletionComputeValuesAndShutdown();
	}

	/**
	 * Schedules the processing of initial seeds, initiating the analysis.
	 * Clients should only call this methods if performing synchronization on
	 * their own. Normally, {@link #solve()} should be called instead.
	 */
	protected void submitInitialSeeds() {
		for(Entry<N, Set<D>> seed: initialSeeds.entrySet()) {
			N startPoint = seed.getKey();
			for(D val: seed.getValue())
				propagate(zeroValue, startPoint, val, null, false);
			jumpFn.addFunction(new PathEdge<N, D>(zeroValue, startPoint, zeroValue));
		}
	}

	/**
	 * Awaits the completion of the exploded super graph. When complete, computes result values,
	 * shuts down the executor and returns.
	 */
	protected void awaitCompletionComputeValuesAndShutdown() {
		{
			//run executor and await termination of tasks
			runExecutorAndAwaitCompletion();
		}
		if(logger.isDebugEnabled())
			printStats();

		//ask executor to shut down;
		//this will cause new submissions to the executor to be rejected,
		//but at this point all tasks should have completed anyway
		executor.shutdown();
		
		// Wait for the executor to be really gone
		while (!executor.isTerminated()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Runs execution, re-throwing exceptions that might be thrown during its execution.
	 */
	private void runExecutorAndAwaitCompletion() {
		try {
			executor.awaitCompletion();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		Throwable exception = executor.getException();
		if(exception!=null) {
			throw new RuntimeException("There were exceptions during IFDS analysis. Exiting.",exception);
		}
	}

    /**
     * Dispatch the processing of a given edge. It may be executed in a different thread.
     * @param edge the edge to process
     */
    protected void scheduleEdgeProcessing(PathEdge<N,D> edge){
    	// If the executor has been killed, there is little point
    	// in submitting new tasks
    	if (executor.isTerminating())
    		return;
    	
    	executor.execute(new PathEdgeProcessingTask(edge));
    	propagationCount++;
    }
	
	/**
	 * Lines 13-20 of the algorithm; processing a call site in the caller's context.
	 * 
	 * For each possible callee, registers incoming call edges.
	 * Also propagates call-to-return flows and summarized callee flows within the caller. 
	 * 
	 * @param edge an edge whose target node resembles a method call
	 */
	private void processCall(PathEdge<N,D> edge) {
	}
	
	/**
	 * Computes the call flow function for the given call-site abstraction
	 * @param callFlowFunction The call flow function to compute
	 * @param d1 The abstraction at the current method's start node.
	 * @param d2 The abstraction at the call site
	 * @return The set of caller-side abstractions at the callee's start node
	 */
	protected Set<D> computeCallFlowFunction
			(FlowFunction<D> callFlowFunction, D d1, D d2) {
		return callFlowFunction.computeTargets(d2);
	}

	/**
	 * Computes the call-to-return flow function for the given call-site
	 * abstraction
	 * @param callToReturnFlowFunction The call-to-return flow function to
	 * compute
	 * @param d1 The abstraction at the current method's start node.
	 * @param d2 The abstraction at the call site
	 * @return The set of caller-side abstractions at the return site
	 */
	protected Set<D> computeCallToReturnFlowFunction
			(FlowFunction<D> callToReturnFlowFunction, D d1, D d2) {
		return callToReturnFlowFunction.computeTargets(d2);
	}
	
	/**
	 * Lines 21-32 of the algorithm.
	 * 
	 * Stores callee-side summaries.
	 * Also, at the side of the caller, propagates intra-procedural flows to return sites
	 * using those newly computed summaries.
	 * 
	 * @param edge an edge whose target node resembles a method exits
	 */
	protected void processExit(PathEdge<N,D> edge) {
	}
	
	/**
	 * Computes the return flow function for the given set of caller-side
	 * abstractions.
	 * @param retFunction The return flow function to compute
	 * @param d1 The abstraction at the beginning of the callee
	 * @param d2 The abstraction at the exit node in the callee
	 * @param callSite The call site
	 * @param callerSideDs The abstractions at the call site
	 * @return The set of caller-side abstractions at the return site
	 */
	protected Set<D> computeReturnFlowFunction
			(FlowFunction<D> retFunction, D d1, D d2, N callSite, Collection<D> callerSideDs) {
		return retFunction.computeTargets(d2);
	}

	/**
	 * Lines 33-37 of the algorithm.
	 * Simply propagate normal, intra-procedural flows.
	 * @param edge
	 */
	private void processNormalFlow(PathEdge<N,D> edge) {
		final D d1 = edge.factAtSource();
		final N n = edge.getTarget(); 
		final D d2 = edge.factAtTarget();
		
	}
	
	/**
	 * Computes the normal flow function for the given set of start and end
	 * abstractions.
	 * @param flowFunction The normal flow function to compute
	 * @param d1 The abstraction at the method's start node
	 * @param d2 The abstraction at the current node
	 * @return The set of abstractions at the successor node
	 */
	protected Set<D> computeNormalFlowFunction
			(FlowFunction<D> flowFunction, D d1, D d2) {
		return flowFunction.computeTargets(d2);
	}
	
	/**
	 * Propagates the flow further down the exploded super graph. 
	 * @param sourceVal the source value of the propagated summary edge
	 * @param target the target statement
	 * @param targetVal the target value at the target statement
	 * @param relatedCallSite for call and return flows the related call statement, <code>null</code> otherwise
	 *        (this value is not used within this implementation but may be useful for subclasses of {@link IFDSSolver}) 
	 * @param isUnbalancedReturn <code>true</code> if this edge is propagating an unbalanced return
	 *        (this value is not used within this implementation but may be useful for subclasses of {@link IFDSSolver}) 
	 */
	protected void propagate(D sourceVal, N target, D targetVal,
		/* deliberately exposed to clients */ N relatedCallSite,
		/* deliberately exposed to clients */ boolean isUnbalancedReturn) {
		propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn, false);
	}
	
	/**
	 * Propagates the flow further down the exploded super graph. 
	 * @param sourceVal the source value of the propagated summary edge
	 * @param target the target statement
	 * @param targetVal the target value at the target statement
	 * @param relatedCallSite for call and return flows the related call statement, <code>null</code> otherwise
	 *        (this value is not used within this implementation but may be useful for subclasses of {@link IFDSSolver}) 
	 * @param isUnbalancedReturn <code>true</code> if this edge is propagating an unbalanced return
	 *        (this value is not used within this implementation but may be useful for subclasses of {@link IFDSSolver})
	 * @param forceRegister True if the jump function must always be registered with jumpFn .
	 * 		  This can happen when externally injecting edges that don't come out of this
	 * 		  solver.
	 */
	protected void propagate(D sourceVal, N target, D targetVal,
			/* deliberately exposed to clients */ N relatedCallSite,
			/* deliberately exposed to clients */ boolean isUnbalancedReturn,
			boolean forceRegister) {
		// Let the memory manager run
		if (memoryManager != null) {
			sourceVal = memoryManager.handleMemoryObject(sourceVal);
			targetVal = memoryManager.handleMemoryObject(targetVal);
			if (sourceVal == null || targetVal == null)
				return;
		}
		
		final PathEdge<N,D> edge = new PathEdge<N,D>(sourceVal, target, targetVal);
		final D existingVal = (forceRegister || !enableMergePointChecking || isMergePoint(target)) ?
				jumpFn.addFunction(edge) : null;
		if (existingVal != null) {
			if (existingVal != targetVal) {
				existingVal.addNeighbor(targetVal);
			}
		}
		else {
			scheduleEdgeProcessing(edge);
		}
	}
	
	/**
	 * Gets whether the given unit is a merge point in the ICFG
	 * @param target The unit to check
	 * @return True if the given unit is a merge point in the ICFG, otherwise
	 * false
	 */
	private boolean isMergePoint(N target) {
		// Check whether there is more than one possibility to reach this unit
		List<N> preds = icfg.getPredsOf(target);
		int size = preds.size();
		if (size > 1)
			if (!icfg.getEndPointsOf(icfg.getMethodOf(target)).contains(target))
				return true;
		
		// Special case: If this is the first unit in the method, there is an
		// implicit second way (through method call)
		if (size == 1) {
			if (icfg.getStartPointsOf(icfg.getMethodOf(target)).contains(target))
				if (!icfg.getEndPointsOf(icfg.getMethodOf(target)).contains(target))
					return true;
		}
		
		return false;
	}

	protected Set<Pair<N, D>> endSummary(M m, D d3) {
		Set<Pair<N, D>> map = endSummary.get(new Pair<M, D>(m, d3));
		return map;
	}

	private boolean addEndSummary(M m, D d1, N eP, D d2) {
		if (d1 == zeroValue)
			return true;
		
		Set<Pair<N, D>> summaries = endSummary.putIfAbsentElseGet
				(new Pair<M, D>(m, d1), new ConcurrentHashSet<Pair<N, D>>());
		return summaries.add(new Pair<N, D>(eP, d2));
	}
	
	protected Map<N, Map<D, D>> incoming(D d1, M m) {
		Map<N, Map<D, D>> map = incoming.get(new Pair<M, D>(m, d1));
		return map;
	}
	
	protected boolean addIncoming(M m, D d3, N n, D d1, D d2) {
		MyConcurrentHashMap<N, Map<D, D>> summaries = incoming.putIfAbsentElseGet
				(new Pair<M, D>(m, d3), new MyConcurrentHashMap<N, Map<D, D>>());
		Map<D, D> set = summaries.putIfAbsentElseGet(n, new ConcurrentHashMap<D, D>());
		return set.put(d1, d2) == null;
	}
	
	/**
	 * Factory method for this solver's thread-pool executor.
	 */
	protected CountingThreadPoolExecutor getExecutor() {
		return new SetPoolExecutor(1, this.numThreads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
	}
	
	/**
	 * Returns a String used to identify the output of this solver in debug mode.
	 * Subclasses can overwrite this string to distinguish the output from different solvers.
	 */
	protected String getDebugName() {
		return "FAST IFDS SOLVER";
	}

	public void printStats() {
		if(logger.isDebugEnabled()) {
			if(ffCache!=null)
				ffCache.printStats();
		} else {
			logger.info("No statistics were collected, as DEBUG is disabled.");
		}
	}
	
	private class PathEdgeProcessingTask implements Runnable {
		
		private final PathEdge<N,D> edge;

		public PathEdgeProcessingTask(PathEdge<N,D> edge) {
			this.edge = edge;
		}

		public void run() {
			if(icfg.isCallStmt(edge.getTarget())) {
				processCall(edge);
			} else {
				//note that some statements, such as "throw" may be
				//both an exit statement and a "normal" statement
				if(icfg.isExitStmt(edge.getTarget()))
					processExit(edge);
				if(!icfg.getSuccsOf(edge.getTarget()).isEmpty())
					processNormalFlow(edge);
			}
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((edge == null) ? 0 : edge.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			PathEdgeProcessingTask other = (PathEdgeProcessingTask) obj;
			if (edge == null) {
				if (other.edge != null)
					return false;
			} else if (!edge.equals(other.edge))
				return false;
			return true;
		}
		
	}
	
	/**
	 * Sets whether abstractions on method returns shall be connected to the
	 * respective call abstractions to shortcut paths.
	 * @param setJumpPredecessors True if return abstractions shall be connected
	 * to call abstractions as predecessors, otherwise false.
	 */
	public void setJumpPredecessors(boolean setJumpPredecessors) {
		this.setJumpPredecessors = setJumpPredecessors;
	}
	
	/**
	 * Sets whether only abstractions at merge points shall be recorded to jumpFn.
	 * @param enableMergePointChecking True if only abstractions at merge points
	 * shall be recorded to jumpFn, otherwise false.
	 */
	public void setEnableMergePointChecking(boolean enableMergePointChecking) {
		this.enableMergePointChecking = enableMergePointChecking;
	}
	
	/**
	 * Sets the memory manager that shall be used to manage the abstractions
	 * @param memoryManager The memory manager that shall be used to manage the
	 * abstractions
	 */
	public void setMemoryManager(IMemoryManager<D> memoryManager) {
		this.memoryManager = memoryManager;
	}
	
	/**
	 * Gets the memory manager used by this solver to reduce memory consumption
	 * @return The memory manager registered with this solver
	 */	
	public IMemoryManager<D> getMemoryManager() {
		return this.memoryManager;
	}

}
