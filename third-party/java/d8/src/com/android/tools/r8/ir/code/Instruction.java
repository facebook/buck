// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.conversion.CfBuilder.StackHelper;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.utils.CfgPrinter;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.StringUtils.BraceType;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class Instruction {

  protected Value outValue = null;
  protected final List<Value> inValues = new ArrayList<>();
  private BasicBlock block = null;
  private int number = -1;
  private Set<Value> debugValues = null;
  private Position position = null;

  protected Instruction(Value outValue) {
    setOutValue(outValue);
  }

  protected Instruction(Value outValue, Value inValue) {
    addInValue(inValue);
    setOutValue(outValue);
  }

  protected Instruction(Value outValue, List<? extends Value> inValues) {
    if (inValues != null) {
      for (Value v : inValues) {
        addInValue(v);
      }
    }
    setOutValue(outValue);
  }

  public final Position getPosition() {
    assert position != null;
    return position;
  }

  public void setPosition(Position position) {
    assert this.position == null;
    this.position = position;
  }

  public final void forceSetPosition(Position position) {
    this.position = position;
  }

  public List<Value> inValues() {
    return inValues;
  }

  protected void addInValue(Value value) {
    if (value != null) {
      inValues.add(value);
      value.addUser(this);
    }
  }

  public boolean hasInValueWithLocalInfo() {
    for (Value inValue : inValues()) {
      if (inValue.hasLocalInfo()) {
        return true;
      }
    }
    return false;
  }

  public Value outValue() {
    return outValue;
  }

  public void setOutValue(Value value) {
    assert outValue == null || !outValue.hasUsersInfo() || !outValue.isUsed();
    outValue = value;
    if (outValue != null) {
      outValue.definition = this;
    }
  }

  public Value swapOutValue(Value newOutValue) {
    Value oldOutValue = outValue;
    outValue = null;
    setOutValue(newOutValue);
    if (oldOutValue != null) {
      oldOutValue.definition = null;
    }
    return oldOutValue;
  }

  public void addDebugValue(Value value) {
    assert value.hasLocalInfo();
    if (debugValues == null) {
      debugValues = new HashSet<>();
    }
    if (debugValues.add(value)) {
      value.addDebugUser(this);
    }
  }

  public static void clearUserInfo(Instruction instruction) {
    if (instruction.outValue != null) {
      instruction.outValue.clearUsersInfo();
    }
    instruction.inValues.forEach(Value::clearUsersInfo);
    if (instruction.debugValues != null) {
      instruction.debugValues.forEach(Value::clearUsersInfo);
      instruction.debugValues = null;
    }
  }

  public final ValueType outType() {
    return outValue.outType();
  }

  public void buildDex(DexBuilder builder) {
    throw new Unreachable("Unexpected instruction when converting to DEX: " + getInstructionName());
  }

  public void buildCf(CfBuilder builder) {
    throw new Unimplemented("No support for building CF instructions for: " + getInstructionName());
  }

  public void replaceValue(Value oldValue, Value newValue) {
    for (int i = 0; i < inValues.size(); i++) {
      if (oldValue == inValues.get(i)) {
        inValues.set(i, newValue);
        newValue.addUser(this);
      }
    }
  }

  public void replaceDebugValue(Value oldValue, Value newValue) {
    if (debugValues.remove(oldValue)) {
      if (newValue.hasLocalInfo()) {
        // TODO(zerny): Insert a write if replacing a phi with different debug-local info.
        addDebugValue(newValue);
      }
      // TODO(zerny): Else: Insert a write if replacing a phi with associated debug-local info.
    }
  }

  public void moveDebugValues(Instruction target) {
    if (debugValues == null) {
      return;
    }
    for (Value value : debugValues) {
      value.replaceDebugUser(this, target);
    }
    debugValues.clear();
  }

  public void moveDebugValue(Value value, Instruction target) {
    assert debugValues.contains(value);
    value.replaceDebugUser(this, target);
    debugValues.remove(value);
  }

  public void removeDebugValue(Value value) {
    assert value.hasLocalInfo();
    if (debugValues != null) {
      assert debugValues.contains(value);
      if (debugValues.remove(value)) {
        value.removeDebugUser(this);
      }
      return;
    }
    assert false;
  }

  public void clearDebugValues() {
    if (debugValues != null) {
      for (Value debugValue : debugValues) {
        debugValue.removeDebugUser(this);
      }
      debugValues.clear();
    }
  }

  /**
   * Returns the basic block containing this instruction.
   */
  public BasicBlock getBlock() {
    assert block != null;
    return block;
  }

  /**
   * Set the basic block of this instruction. See IRBuilder.
   */
  public void setBlock(BasicBlock block) {
    assert block != null;
    this.block = block;
  }

  /**
   * Clear the basic block of this instruction. Use when removing an instruction from a block.
   */
  public void clearBlock() {
    assert block != null;
    block = null;
  }

  public String getInstructionName() {
    return getClass().getSimpleName();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(getInstructionName());
    for (int i = builder.length(); i < 20; i++) {
      builder.append(" ");
    }
    builder.append(" ");
    if (outValue != null) {
      builder.append(outValue);
      builder.append(" <- ");
    }
    if (!inValues.isEmpty()) {
      StringUtils.append(builder, inValues, ", ", BraceType.NONE);
    }
    return builder.toString();
  }

  public void print(CfgPrinter printer) {
    int uses = 0;
    String value;
    if (outValue == null) {
      value = printer.makeUnusedValue();
    } else {
      if (outValue.hasUsersInfo()) {
        uses = outValue.uniqueUsers().size() + outValue.uniquePhiUsers().size();
      }
      value = "v" + outValue.getNumber();
    }
    printer
        .print(0)           // bci
        .sp().append(uses)  // use
        .sp().append(value) // tid
        .sp().append(getClass().getSimpleName());
    for (Value in : inValues) {
      printer.append(" v").append(in.getNumber());
    }
  }

  public void printLIR(CfgPrinter printer) {
    // TODO(ager): Improve the instruction printing. Use different name for values so that the
    // HIR and LIR values are not confused in the c1 visualizer.
    printer.print(number).sp().append(toString());
  }

  public int getNumber() {
    return number;
  }

  public void setNumber(int number) {
    assert number != -1;
    this.number = number;
  }

  /**
   * Compare equality of two class-equivalent instructions modulo their values and positions.
   *
   * <p>It is a precondition to this method that this.getClass() == other.getClass().
   */
  public abstract boolean identicalNonValueNonPositionParts(Instruction other);

  public boolean identicalNonValueParts(Instruction other) {
    assert getClass() == other.getClass();
    return position.equals(other.position) && identicalNonValueNonPositionParts(other);
  }

  public abstract int compareNonValueParts(Instruction other);

  private boolean identicalAfterRegisterAllocation(
      Value a, int aInstr, Value b, int bInstr, RegisterAllocator allocator) {
    if (a.needsRegister() != b.needsRegister()) {
      return false;
    }
    if (a.needsRegister()) {
      if (allocator.getRegisterForValue(a, aInstr) != allocator.getRegisterForValue(b, bInstr)) {
        return false;
      }
    } else {
      ConstNumber aNum = a.getConstInstruction().asConstNumber();
      ConstNumber bNum = b.getConstInstruction().asConstNumber();
      if (!aNum.identicalNonValueNonPositionParts(bNum)) {
        return false;
      }
    }
    if (a.outType() != b.outType()) {
      return false;
    }
    return true;
  }

  public boolean identicalAfterRegisterAllocation(Instruction other, RegisterAllocator allocator) {
    if (other.getClass() != getClass()) {
      return false;
    }
    if (!identicalNonValueParts(other)) {
      return false;
    }
    if (isInvokeDirect() && !asInvokeDirect().sameConstructorReceiverValue(other.asInvoke())) {
      return false;
    }
    if (outValue != null) {
      if (other.outValue == null) {
        return false;
      }
      if (!identicalAfterRegisterAllocation(
          outValue, getNumber(), other.outValue, other.getNumber(), allocator)) {
        return false;
      }
    } else if (other.outValue != null) {
      return false;
    }
    // Check that all input values have the same type and allocated registers.
    if (inValues.size() != other.inValues.size()) {
      return false;
    }
    for (int j = 0; j < inValues.size(); j++) {
      Value in0 = inValues.get(j);
      Value in1 = other.inValues.get(j);
      if (!identicalAfterRegisterAllocation(in0, getNumber(), in1, other.getNumber(), allocator)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if this instruction may throw an exception.
   */
  public boolean instructionTypeCanThrow() {
    return false;
  }

  public boolean instructionInstanceCanThrow() {
    return instructionTypeCanThrow();
  }

  /** Returns true is this instruction can be treated as dead code if its outputs are not used. */
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    return !instructionInstanceCanThrow();
  }

  /**
   * Returns true if this instruction need this value in a register.
   */
  public boolean needsValueInRegister(Value value) {
    return true;
  }

  /**
   * Returns true if the out value of this instruction is a constant.
   *
   * @return whether the out value of this instruction is a constant.
   */
  public boolean isOutConstant() {
    return false;
  }

  /**
   * Returns the ConstInstruction defining the constant out value if the out value is constant.
   *
   * @return ConstInstruction or null.
   */
  public ConstInstruction getOutConstantConstInstruction() {
    return null;
  }

  public abstract int maxInValueRegister();

  public abstract int maxOutValueRegister();

  public DebugLocalInfo getLocalInfo() {
    return outValue == null ? null : outValue.getLocalInfo();
  }

  public Set<Value> getDebugValues() {
    return debugValues != null ? debugValues : ImmutableSet.of();
  }

  public boolean isArrayGet() {
    return false;
  }

  public ArrayGet asArrayGet() {
    return null;
  }

  public boolean isArrayLength() {
    return false;
  }

  public ArrayLength asArrayLength() {
    return null;
  }

  public boolean isArrayPut() {
    return false;
  }

  public ArrayPut asArrayPut() {
    return null;
  }

  public boolean isArgument() {
    return false;
  }

  public Argument asArgument() {
    return null;
  }

  public boolean isArithmeticBinop() {
    return false;
  }

  public ArithmeticBinop asArithmeticBinop() {
    return null;
  }

  public boolean isBinop() {
    return false;
  }

  public Binop asBinop() {
    return null;
  }

  public boolean isUnop() {
    return false;
  }

  public Unop asUnop() {
    return null;
  }

  public boolean isCheckCast() {
    return false;
  }

  public CheckCast asCheckCast() {
    return null;
  }

  public boolean isConstNumber() {
    return false;
  }

  public ConstNumber asConstNumber() {
    return null;
  }

  public boolean isConstInstruction() {
    return false;
  }

  public ConstInstruction asConstInstruction() {
    return null;
  }

  public boolean isConstClass() {
    return false;
  }

  public ConstClass asConstClass() {
    return null;
  }

  public boolean isConstMethodHandle() {
    return false;
  }

  public ConstMethodHandle asConstMethodHandle() {
    return null;
  }

  public boolean isConstMethodType() {
    return false;
  }

  public ConstMethodType asConstMethodType() {
    return null;
  }

  public boolean isConstString() {
    return false;
  }

  public ConstString asConstString() {
    return null;
  }

  public boolean isCmp() {
    return false;
  }

  public Cmp asCmp() {
    return null;
  }

  public boolean isJumpInstruction() {
    return false;
  }

  public JumpInstruction asJumpInstruction() {
    return null;
  }

  public boolean isGoto() {
    return false;
  }

  public Goto asGoto() {
    return null;
  }

  public boolean isIf() {
    return false;
  }

  public If asIf() {
    return null;
  }

  public boolean isSwitch() {
    return false;
  }

  public Switch asSwitch() {
    return null;
  }

  public boolean isInstanceGet() {
    return false;
  }

  public InstanceGet asInstanceGet() {
    return null;
  }

  public boolean isInstanceOf() {
    return false;
  }

  public InstanceOf asInstanceOf() {
    return null;
  }

  public boolean isInstancePut() {
    return false;
  }

  public InstancePut asInstancePut() {
    return null;
  }

  public boolean isInvoke() {
    return false;
  }

  public Invoke asInvoke() {
    return null;
  }

  public boolean isMonitor() {
    return false;
  }

  public Monitor asMonitor() {
    return null;
  }

  public boolean isMove() {
    return false;
  }

  public Move asMove() {
    return null;
  }

  public boolean isNewArrayEmpty() {
    return false;
  }

  public NewArrayEmpty asNewArrayEmpty() {
    return null;
  }

  public boolean isNewArrayFilledData() {
    return false;
  }

  public NewArrayFilledData asNewArrayFilledData() {
    return null;
  }

  public boolean isNeg() {
    return false;
  }

  public Neg asNeg() {
    return null;
  }

  public boolean isNewInstance() {
    return false;
  }

  public NewInstance asNewInstance() {
    return null;
  }

  public boolean isNot() {
    return false;
  }

  public Not asNot() {
    return null;
  }

  public boolean isNumberConversion() {
    return false;
  }

  public NumberConversion asNumberConversion() {
    return null;
  }

  public boolean isReturn() {
    return false;
  }

  public Return asReturn() {
    return null;
  }

  public boolean isThrow() {
    return false;
  }

  public Throw asThrow() {
    return null;
  }

  public boolean isStaticGet() {
    return false;
  }

  public StaticGet asStaticGet() {
    return null;
  }

  public boolean isStaticPut() {
    return false;
  }

  public StaticPut asStaticPut() {
    return null;
  }

  public boolean isAdd() {
    return false;
  }

  public Add asAdd() {
    return null;
  }

  public boolean isSub() {
    return false;
  }

  public Sub asSub() {
    return null;
  }

  public boolean isMul() {
    return false;
  }

  public Mul asMul() {
    return null;
  }

  public boolean isDiv() {
    return false;
  }

  public Div asDiv() {
    return null;
  }

  public boolean isRem() {
    return false;
  }

  public Rem asRem() {
    return null;
  }

  public boolean isLogicalBinop() {
    return false;
  }

  public LogicalBinop asLogicalBinop() {
    return null;
  }

  public boolean isShl() {
    return false;
  }

  public Shl asShl() {
    return null;
  }

  public boolean isShr() {
    return false;
  }

  public Shr asShr() {
    return null;
  }

  public boolean isUshr() {
    return false;
  }

  public Ushr asUshr() {
    return null;
  }

  public boolean isAnd() {
    return false;
  }

  public And asAnd() {
    return null;
  }

  public boolean isOr() {
    return false;
  }

  public Or asOr() {
    return null;
  }

  public boolean isXor() {
    return false;
  }

  public Xor asXor() {
    return null;
  }

  public boolean isMoveException() {
    return false;
  }

  public MoveException asMoveException() {
    return null;
  }

  public boolean isDebugInstruction() {
    return isDebugPosition()
        || isDebugLocalsChange()
        || isDebugLocalWrite()
        || isDebugLocalUninitialized();
  }

  public boolean isDebugPosition() {
    return false;
  }

  public DebugPosition asDebugPosition() {
    return null;
  }

  public boolean isDebugLocalsChange() {
    return false;
  }

  public DebugLocalsChange asDebugLocalsChange() {
    return null;
  }

  public boolean isDebugLocalUninitialized() {
    return false;
  }

  public DebugLocalUninitialized asDebugLocalUninitialized() {
    return null;
  }

  public boolean isDebugLocalWrite() {
    return false;
  }

  public DebugLocalWrite asDebugLocalWrite() {
    return null;
  }

  public boolean isInvokeMethod() {
    return false;
  }

  public InvokeMethod asInvokeMethod() {
    return null;
  }

  public boolean isInvokeMethodWithReceiver() {
    return false;
  }

  public InvokeMethodWithReceiver asInvokeMethodWithReceiver() {
    return null;
  }

  public boolean isInvokeNewArray() {
    return false;
  }

  public InvokeNewArray asInvokeNewArray() {
    return null;
  }

  public boolean isInvokeCustom() {
    return false;
  }

  public InvokeCustom asInvokeCustom() {
    return null;
  }

  public boolean isInvokeDirect() {
    return false;
  }

  public InvokeDirect asInvokeDirect() {
    return null;
  }

  public boolean isInvokeInterface() {
    return false;
  }

  public InvokeInterface asInvokeInterface() {
    return null;
  }

  public boolean isInvokeStatic() {
    return false;
  }

  public InvokeStatic asInvokeStatic() {
    return null;
  }

  public boolean isInvokeSuper() {
    return false;
  }

  public InvokeSuper asInvokeSuper() {
    return null;
  }

  public boolean isInvokeVirtual() {
    return false;
  }

  public InvokeVirtual asInvokeVirtual() {
    return null;
  }

  public boolean isInvokePolymorphic() {
    return false;
  }

  public InvokePolymorphic asInvokePolymorphic() {
    return null;
  }

  public boolean isDebugLocalRead() {
    return false;
  }

  public DebugLocalRead asDebugLocalRead() {
    return null;
  }

  public boolean canBeFolded() {
    return false;
  }

  public ConstInstruction fold(IRCode code) {
    throw new Unreachable("Unsupported folding for " + this);
  }

  // Returns the inlining constraint for this instruction.
  public abstract Constraint inliningConstraint(AppInfoWithSubtyping info, DexType holder);

  public void insertLoadAndStores(InstructionListIterator it, StackHelper stack) {
    throw new Unimplemented("Implment load/store insertion for: " + getInstructionName());
  }
}
