// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.utils.CfgPrinter;
import java.util.List;

public class Goto extends JumpInstruction {

  public Goto() {
    super(null);
    super.setPosition(Position.none());
  }

  public Goto(BasicBlock block) {
    this();
    setBlock(block);
  }

  @Override
  public void setPosition(Position position) {
    // In general goto's do not signify program points only transitions, so we avoid
    // associating them with positional information.
  }

  public BasicBlock getTarget() {
    assert getBlock().exit() == this;
    List<BasicBlock> successors = getBlock().getSuccessors();
    assert successors.size() >= 1;
    return successors.get(successors.size() - 1);
  }

  public void setTarget(BasicBlock nextBlock) {
    assert getBlock().exit() == this;
    List<BasicBlock> successors = getBlock().getSuccessors();
    assert successors.size() >= 1;
    BasicBlock target = successors.get(successors.size() - 1);
    target.getPredecessors().remove(getBlock());
    successors.set(successors.size() - 1, nextBlock);
    nextBlock.getPredecessors().add(getBlock());
  }

  @Override
  public void buildDex(DexBuilder builder) {
    builder.addGoto(this);
  }

  @Override
  public int maxInValueRegister() {
    assert false : "Goto has no register arguments.";
    return 0;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "Goto defines no values.";
    return 0;
  }

  @Override
  public String toString() {
    if (getBlock() != null && !getBlock().getSuccessors().isEmpty()) {
      return super.toString() + "block " + getTarget().getNumber();
    }
    return super.toString() + "block <unknown>";
  }

  @Override
  public void print(CfgPrinter printer) {
    super.print(printer);
    printer.append(" B").append(getTarget().getNumber());
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.asGoto().getTarget() == getTarget();
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isGoto();
    assert false : "Not supported";
    return 0;
  }

  @Override
  public boolean isGoto() {
    return true;
  }

  @Override
  public Goto asGoto() {
    return this;
  }
}
