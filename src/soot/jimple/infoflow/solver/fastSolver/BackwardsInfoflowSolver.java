package soot.jimple.infoflow.solver.fastSolver;

import heros.FlowFunction;
import heros.solver.CountingThreadPoolExecutor;
import heros.solver.Pair;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.problems.AbstractInfoflowProblem;
import soot.jimple.infoflow.solver.IInfoflowSolver;

public class BackwardsInfoflowSolver extends InfoflowSolver {
	public BackwardsInfoflowSolver(AbstractInfoflowProblem problem, CountingThreadPoolExecutor executor) {
		super(problem, executor);
	}

	@Override
	public void injectContext(IInfoflowSolver otherSolver, SootMethod callee,
			Abstraction d3, Unit callSite, Abstraction d2, Abstraction d1) {
		if(!this.addIncoming(callee, d3, callSite, d1, d2)) {
			return;
		}
	}
}
