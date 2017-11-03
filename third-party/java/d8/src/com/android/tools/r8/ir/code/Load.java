// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.code.CfLoad;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class Load extends Instruction {

  public Load(StackValue dest, Value src) {
    super(dest, src);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return true;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return 0;
  }

  @Override
  public int maxInValueRegister() {
    throw new Unreachable();
  }

  @Override
  public int maxOutValueRegister() {
    throw new Unreachable();
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    throw new Unreachable();
  }

  @Override
  public void buildCf(CfBuilder builder) {
    Value value = inValues.get(0);
    builder.add(new CfLoad(value.outType(), value.getNumber()));
  }
}
