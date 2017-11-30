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
import soot.jimple.infoflow.aliasing.Aliasing;
import soot.jimple.infoflow.aliasing.IAliasingStrategy;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.problems.rules.PropagationRuleManager;

public class InfoflowProblem extends AbstractInfoflowProblem {
	
	private final Aliasing aliasing;
	private final IAliasingStrategy aliasingStrategy;
	private final PropagationRuleManager propagationRules;
	
	protected final TaintPropagationResults results;
	
	public InfoflowProblem(InfoflowManager manager,
			IAliasingStrategy aliasingStrategy,
			Abstraction zeroValue) {
		super(manager);
		
		if (zeroValue != null)
			setZeroValue(zeroValue);
		
		this.aliasingStrategy = aliasingStrategy;
		this.aliasing = new Aliasing(aliasingStrategy, manager.getICFG());
		this.results = new TaintPropagationResults(manager);
		
		this.propagationRules = new PropagationRuleManager(manager, aliasing,
				createZeroValue(), results);
	}
	

	@Override
	public boolean autoAddZero() {
		return false;
	}
	
	/**
	 * Gets the results of the data flow analysis
	 */
    public TaintPropagationResults getResults(){
   		return this.results;
	}


	@Override
	protected FlowFunctions<Unit, Abstraction, SootMethod> createFlowFunctionsFactory() {
		// TODO Auto-generated method stub
		return null;
	}
    
}
