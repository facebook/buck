// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringUtils;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry;

public class DebugLocalsChange extends Instruction {

  private final Int2ReferenceMap<DebugLocalInfo> ending;
  private final Int2ReferenceMap<DebugLocalInfo> starting;

  public DebugLocalsChange(
      Int2ReferenceMap<DebugLocalInfo> ending, Int2ReferenceMap<DebugLocalInfo> starting) {
    super(null);
    assert !ending.isEmpty() || !starting.isEmpty();
    this.ending = ending;
    this.starting = starting;
    super.setPosition(Position.none());
  }

  @Override
  public void setPosition(Position position) {
    throw new Unreachable();
  }

  public Int2ReferenceMap<DebugLocalInfo> getEnding() {
    return ending;
  }

  public Int2ReferenceMap<DebugLocalInfo> getStarting() {
    return starting;
  }

  @Override
  public boolean isDebugLocalsChange() {
    return true;
  }

  @Override
  public DebugLocalsChange asDebugLocalsChange() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    builder.addNop(this);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    assert other.isDebugLocalsChange();
    DebugLocalsChange o = (DebugLocalsChange) other;
    return DebugLocalInfo.localsInfoMapsEqual(ending, o.ending)
        && DebugLocalInfo.localsInfoMapsEqual(starting, o.starting);
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isDebugLocalsChange();
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
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    return false;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder(super.toString());
    builder.append("ending: ");
    StringUtils.append(builder, ending.int2ReferenceEntrySet());
    builder.append(", starting: ");
    StringUtils.append(builder, starting.int2ReferenceEntrySet());
    return builder.toString();
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder) {
    return Constraint.ALWAYS;
  }

  public void apply(Int2ReferenceMap<DebugLocalInfo> locals) {
    for (Entry<DebugLocalInfo> end : getEnding().int2ReferenceEntrySet()) {
      assert locals.get(end.getIntKey()) == end.getValue();
      locals.remove(end.getIntKey());
    }
    for (Entry<DebugLocalInfo> start : getStarting().int2ReferenceEntrySet()) {
      assert !locals.containsKey(start.getIntKey());
      locals.put(start.getIntKey(), start.getValue());
    }
  }
}
