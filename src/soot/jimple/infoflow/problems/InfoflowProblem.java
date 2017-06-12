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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import heros.FlowFunction;
import heros.FlowFunctions;
import heros.flowfunc.KillAll;
import heros.solver.IPropagationController;
import soot.ArrayType;
import soot.BooleanType;
import soot.Local;
import soot.NullType;
import soot.PrimType;
import soot.RefType;
import soot.SootField;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.CastExpr;
import soot.jimple.DefinitionStmt;
import soot.jimple.FieldRef;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InstanceOfExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.NewArrayExpr;
import soot.jimple.ReturnStmt;
import soot.jimple.StaticFieldRef;
import soot.jimple.Stmt;
import soot.jimple.infoflow.InfoflowManager;
import soot.jimple.infoflow.aliasing.Aliasing;
import soot.jimple.infoflow.aliasing.IAliasingStrategy;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.data.AccessPath.ArrayTaintType;
import soot.jimple.infoflow.data.AccessPathFactory;
import soot.jimple.infoflow.handlers.TaintPropagationHandler.FlowFunctionType;
import soot.jimple.infoflow.problems.rules.PropagationRuleManager;
import soot.jimple.infoflow.solver.functions.SolverCallFlowFunction;
import soot.jimple.infoflow.solver.functions.SolverCallToReturnFlowFunction;
import soot.jimple.infoflow.solver.functions.SolverNormalFlowFunction;
import soot.jimple.infoflow.solver.functions.SolverReturnFlowFunction;
import soot.jimple.infoflow.util.BaseSelector;
import soot.jimple.infoflow.util.ByReferenceBoolean;
import soot.jimple.infoflow.util.TypeUtils;

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
	public IPropagationController<Unit, Abstraction> propagationController() {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	protected FlowFunctions<Unit, Abstraction, SootMethod> createFlowFunctionsFactory() {
		// TODO Auto-generated method stub
		return null;
	}
    
}
