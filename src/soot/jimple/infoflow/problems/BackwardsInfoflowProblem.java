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
package soot.jimple.infoflow.problems;

import heros.FlowFunctions;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.InfoflowManager;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.solver.IInfoflowSolver;
import soot.jimple.infoflow.taintWrappers.ITaintPropagationWrapper;

/**
 * class which contains the flow functions for the backwards solver. This is
 * required for on-demand alias analysis.
 */
public class BackwardsInfoflowProblem extends AbstractInfoflowProblem {
	private IInfoflowSolver fSolver;
	
	public void setTaintWrapper(ITaintPropagationWrapper wrapper) {
		taintWrapper = wrapper;
	}
	
	public BackwardsInfoflowProblem(InfoflowManager manager) {
		super(manager);
	}

	public void setForwardSolver(IInfoflowSolver forwardSolver) {
		fSolver = forwardSolver;
	}

	@Override
	protected FlowFunctions<Unit, Abstraction, SootMethod> createFlowFunctionsFactory() {
		// TODO Auto-generated method stub
		return null;
	}
	
}
