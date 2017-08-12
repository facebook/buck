// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.synthetic;

import static com.android.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.Argument;
import com.android.tools.r8.ir.code.CatchHandlers;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.ir.conversion.SourceCode;
import com.android.tools.r8.utils.ThrowingConsumer;
import java.util.ArrayList;
import java.util.List;

public abstract class SingleBlockSourceCode implements SourceCode {

  protected final DexType receiver;
  protected final DexProto proto;

  // The next free register, note that we always
  // assign each value a new (next available) register.
  private int nextRegister = 0;

  // Registers for receiver and parameters
  private final int receiverRegister;
  private int[] paramRegisters;
  // Values representing receiver and parameters will be filled in
  // buildPrelude() and should only be accessed via appropriate methods
  private Value receiverValue;
  private Value[] paramValues;

  // Instruction constructors
  private List<ThrowingConsumer<IRBuilder, ApiLevelException>> constructors = new ArrayList<>();

  protected SingleBlockSourceCode(DexType receiver, DexProto proto) {
    assert proto != null;
    this.receiver = receiver;
    this.proto = proto;

    // Initialize register values for receiver and arguments
    this.receiverRegister = receiver != null ? nextRegister(ValueType.OBJECT) : -1;

    DexType[] params = proto.parameters.values;
    int paramCount = params.length;
    this.paramRegisters = new int[paramCount];
    this.paramValues = new Value[paramCount];
    for (int i = 0; i < paramCount; i++) {
      this.paramRegisters[i] = nextRegister(ValueType.fromDexType(params[i]));
    }
  }

  protected final void add(ThrowingConsumer<IRBuilder, ApiLevelException> constructor) {
    constructors.add(constructor);
  }

  protected final int nextRegister(ValueType type) {
    int value = nextRegister;
    nextRegister += type.requiredRegisters();
    return value;
  }

  protected final Value getReceiverValue() {
    assert receiver != null;
    assert receiverValue != null;
    return receiverValue;
  }

  protected final int getReceiverRegister() {
    assert receiver != null;
    assert receiverRegister >= 0;
    return receiverRegister;
  }

  protected final Value getParamValue(int paramIndex) {
    assert paramIndex >= 0;
    assert paramIndex < paramValues.length;
    return paramValues[paramIndex];
  }

  protected final int getParamCount() {
    return paramValues.length;
  }

  protected final int getParamRegister(int paramIndex) {
    assert paramIndex >= 0;
    assert paramIndex < paramRegisters.length;
    return paramRegisters[paramIndex];
  }

  protected abstract void prepareInstructions();

  @Override
  public final int instructionCount() {
    return constructors.size();
  }

  @Override
  public final int instructionIndex(int instructionOffset) {
    return instructionOffset;
  }

  @Override
  public final int instructionOffset(int instructionIndex) {
    return instructionIndex;
  }

  @Override
  public DebugLocalInfo getCurrentLocal(int register) {
    return null;
  }

  @Override
  public final int traceInstruction(int instructionIndex, IRBuilder builder) {
    return (instructionIndex == constructors.size() - 1) ? instructionIndex : -1;
  }

  @Override
  public final void closingCurrentBlockWithFallthrough(
      int fallthroughInstructionIndex, IRBuilder builder) {
  }

  @Override
  public final void setUp() {
    assert constructors.isEmpty();
    prepareInstructions();
    assert !constructors.isEmpty();
  }

  @Override
  public final void clear() {
    constructors = null;
    paramRegisters = null;
    paramValues = null;
    receiverValue = null;
  }

  @Override
  public final void buildPrelude(IRBuilder builder) {
    if (receiver != null) {
      receiverValue = builder.writeRegister(receiverRegister, ValueType.OBJECT, NO_THROW);
      builder.add(new Argument(receiverValue));
      receiverValue.markAsThis();
    }

    // Fill in the Argument instructions in the argument block.
    DexType[] parameters = proto.parameters.values;
    for (int i = 0; i < parameters.length; i++) {
      ValueType valueType = ValueType.fromDexType(parameters[i]);
      Value paramValue = builder.writeRegister(paramRegisters[i], valueType, NO_THROW);
      paramValues[i] = paramValue;
      builder.add(new Argument(paramValue));
    }
  }

  @Override
  public final void buildPostlude(IRBuilder builder) {
    // Intentionally left empty.
  }

  @Override
  public final void buildInstruction(IRBuilder builder, int instructionIndex)
      throws ApiLevelException {
    constructors.get(instructionIndex).accept(builder);
  }

  @Override
  public final void resolveAndBuildSwitch(
      int value, int fallthroughOffset, int payloadOffset, IRBuilder builder) {
    throw new Unreachable("Unexpected call to resolveAndBuildSwitch");
  }

  @Override
  public final void resolveAndBuildNewArrayFilledData(
      int arrayRef, int payloadOffset, IRBuilder builder) {
    throw new Unreachable("Unexpected call to resolveAndBuildNewArrayFilledData");
  }

  @Override
  public final CatchHandlers<Integer> getCurrentCatchHandlers() {
    return null;
  }

  @Override
  public int getMoveExceptionRegister() {
    throw new Unreachable();
  }

  @Override
  public Position getDebugPositionAtOffset(int offset) {
    throw new Unreachable();
  }

  @Override
  public Position getCurrentPosition() {
    return Position.none();
  }

  @Override
  public final boolean verifyCurrentInstructionCanThrow() {
    return true;
  }

  @Override
  public boolean verifyLocalInScope(DebugLocalInfo local) {
    return true;
  }

  @Override
  public final boolean verifyRegister(int register) {
    return true;
  }
}
