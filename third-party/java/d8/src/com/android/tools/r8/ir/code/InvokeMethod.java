// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.CfBuilder.StackHelper;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.ir.optimize.Inliner.InlineAction;
import com.android.tools.r8.ir.optimize.InliningOracle;
import java.util.List;

public abstract class InvokeMethod extends Invoke {

  private DexMethod method;

  public InvokeMethod(DexMethod target, Value result, List<Value> arguments) {
    super(result, arguments);
    this.method = target;
  }

  @Override
  public DexType getReturnType() {
    return method.proto.returnType;
  }

  public DexMethod getInvokedMethod() {
    return method;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return method == other.asInvokeMethod().getInvokedMethod();
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return getInvokedMethod().slowCompareTo(other.asInvokeMethod().getInvokedMethod());
  }

  @Override
  public String toString() {
    return super.toString() + "; method: " + method.toSourceString();
  }

  @Override
  public boolean isInvokeMethod() {
    return true;
  }

  @Override
  public InvokeMethod asInvokeMethod() {
    return this;
  }

  abstract DexEncodedMethod lookupTarget(AppInfo appInfo);

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    if (method.holder.isArrayType()) {
      return Constraint.ALWAYS;
    }
    DexEncodedMethod target = lookupTarget(info);
    if (target != null) {
      DexType methodHolder = target.method.holder;
      DexClass methodClass = info.definitionFor(methodHolder);
      if ((methodClass != null)) {
        Constraint methodConstrain = Constraint
            .deriveConstraint(holder, methodHolder, target.accessFlags, info);
        // We also have to take the constraint of the enclosing class into account.
        Constraint classConstraint = Constraint
            .deriveConstraint(holder, methodHolder, methodClass.accessFlags, info);
        return Constraint.min(methodConstrain, classConstraint);
      }
    }
    return Constraint.NEVER;
  }

  public abstract InlineAction computeInlining(InliningOracle decider);

  @Override
  public void insertLoadAndStores(InstructionListIterator it, StackHelper stack) {
    stack.loadInValues(this, it);
    if (method.proto.returnType.isVoidType()) {
      return;
    }
    if (outValue == null) {
      stack.popOutValue(this, it);
    } else {
      stack.storeOutValue(this, it);
    }
  }
}
