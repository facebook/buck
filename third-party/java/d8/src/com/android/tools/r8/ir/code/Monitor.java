// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import static com.android.tools.r8.dex.Constants.U8BIT_MAX;

import com.android.tools.r8.code.MonitorEnter;
import com.android.tools.r8.code.MonitorExit;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;

public class Monitor extends Instruction {

  public enum Type {
    ENTER, EXIT
  }

  private final Type type;

  public Monitor(Type type, Value object) {
    super(null, object);
    this.type = type;
  }

  private Value object() {
    return inValues.get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    // If the monitor object is an argument, we use the argument register for all the monitor
    // enters and exits in order to not confuse the Art verifier lock verification code.
    // This is best effort. If the argument happens to be in a very high register we cannot
    // do it and the lock verification can hit a case where it gets confused. Not much we
    // can do about that, but this should avoid it in the most common cases.
    int object = builder.argumentOrAllocateRegister(object(), getNumber());
    if (object > maxInValueRegister()) {
      object = builder.allocatedRegister(object(), getNumber());
    }
    if (type == Type.ENTER) {
      builder.add(this, new MonitorEnter(object));
    } else {
      builder.add(this, new MonitorExit(object));
    }
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asMonitor().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asMonitor().type.ordinal();
  }

  @Override
  public int maxInValueRegister() {
    return U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "Monitor defines no values.";
    return 0;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean isMonitor() {
    return true;
  }

  @Override
  public Monitor asMonitor() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    // Conservative choice.
    return Constraint.NEVER;
  }

  @Override
  public String getInstructionName() {
    switch (type) {
      case ENTER:
        return "MonitorEnter";
      case EXIT:
        return "MonitorExit";
      default:
        throw new Unreachable("Unknown monitor type:" + type);
    }
  }
}
