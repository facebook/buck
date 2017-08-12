// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.code.FillArrayData;
import com.android.tools.r8.code.FillArrayDataPayload;
import com.android.tools.r8.code.FilledNewArray;
import com.android.tools.r8.code.FilledNewArrayRange;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.code.InvokeDirect;
import com.android.tools.r8.code.InvokeDirectRange;
import com.android.tools.r8.code.InvokeInterface;
import com.android.tools.r8.code.InvokeInterfaceRange;
import com.android.tools.r8.code.InvokePolymorphic;
import com.android.tools.r8.code.InvokePolymorphicRange;
import com.android.tools.r8.code.InvokeStatic;
import com.android.tools.r8.code.InvokeStaticRange;
import com.android.tools.r8.code.InvokeSuper;
import com.android.tools.r8.code.InvokeSuperRange;
import com.android.tools.r8.code.InvokeVirtual;
import com.android.tools.r8.code.InvokeVirtualRange;
import com.android.tools.r8.code.MoveResult;
import com.android.tools.r8.code.MoveResultObject;
import com.android.tools.r8.code.MoveResultWide;
import com.android.tools.r8.code.SwitchPayload;
import com.android.tools.r8.code.Throw;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexCode;
import com.android.tools.r8.graph.DexCode.Try;
import com.android.tools.r8.graph.DexCode.TryHandler;
import com.android.tools.r8.graph.DexCode.TryHandler.TypeAddrPair;
import com.android.tools.r8.graph.DexDebugEntry;
import com.android.tools.r8.graph.DexDebugInfo;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.ir.code.CatchHandlers;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DexSourceCode implements SourceCode {

  private final DexCode code;
  private final MethodAccessFlags accessFlags;
  private final DexProto proto;

  // Mapping from instruction offset to instruction index in the DexCode instruction array.
  private final Map<Integer, Integer> offsetToInstructionIndex = new HashMap<>();

  private final SwitchPayloadResolver switchPayloadResolver = new SwitchPayloadResolver();
  private final ArrayFilledDataPayloadResolver arrayFilledDataPayloadResolver =
      new ArrayFilledDataPayloadResolver();

  private Try currentTryRange = null;
  private CatchHandlers<Integer> currentCatchHandlers = null;
  private Instruction currentDexInstruction = null;

  private Position currentPosition = null;
  private Map<Position, Position> canonicalPositions = null;

  private final List<ValueType> argumentTypes;

  private List<DexDebugEntry> debugEntries = null;

  public DexSourceCode(DexCode code, DexEncodedMethod method) {
    this.code = code;
    this.proto = method.method.proto;
    this.accessFlags = method.accessFlags;
    argumentTypes = computeArgumentTypes();
    DexDebugInfo info = code.getDebugInfo();
    if (info != null) {
      debugEntries = info.computeEntries();
      canonicalPositions = new HashMap<>(debugEntries.size());
    }
  }

  @Override
  public boolean verifyRegister(int register) {
    return register < code.registerSize;
  }

  @Override
  public int instructionCount() {
    return code.instructions.length;
  }

  @Override
  public DebugLocalInfo getCurrentLocal(int register) {
    // TODO(zerny): Support locals in the dex front-end. b/36378142
    return null;
  }

  @Override
  public void setUp() {
    // Collect all payloads in the instruction stream.
    for (int index = 0; index < code.instructions.length; index++) {
      Instruction insn = code.instructions[index];
      offsetToInstructionIndex.put(insn.getOffset(), index);
      if (insn.isPayload()) {
        if (insn.isSwitchPayload()) {
          switchPayloadResolver.resolve((SwitchPayload) insn);
        } else {
          arrayFilledDataPayloadResolver.resolve((FillArrayDataPayload) insn);
        }
      }
    }
  }

  @Override
  public void buildPrelude(IRBuilder builder) {
    currentPosition = Position.none();
    if (code.incomingRegisterSize == 0) {
      return;
    }
    // Fill in the Argument instructions (incomingRegisterSize last registers) in the argument
    // block.
    int register = code.registerSize - code.incomingRegisterSize;
    if (!accessFlags.isStatic()) {
      builder.addThisArgument(register);
      ++register;
    }
    for (ValueType type : argumentTypes) {
      builder.addNonThisArgument(register, type);
      register += type.requiredRegisters();
    }
  }

  @Override
  public void buildPostlude(IRBuilder builder) {
    // Intentionally left empty. (Needed in the Java bytecode frontend for synchronization support.)
  }

  @Override
  public void closingCurrentBlockWithFallthrough(
      int fallthroughInstructionIndex, IRBuilder builder) {
    // Intentionally empty.
  }

  @Override
  public void buildInstruction(IRBuilder builder, int instructionIndex) throws ApiLevelException {
    updateCurrentCatchHandlers(instructionIndex);
    updateDebugPosition(instructionIndex, builder);
    currentDexInstruction = code.instructions[instructionIndex];
    currentDexInstruction.buildIR(builder);
  }

  @Override
  public CatchHandlers<Integer> getCurrentCatchHandlers() {
    return currentCatchHandlers;
  }

  @Override
  public int getMoveExceptionRegister() {
    // No register, move-exception is manually entered during construction.
    return -1;
  }

  @Override
  public Position getDebugPositionAtOffset(int offset) {
    throw new Unreachable();
  }

  @Override
  public Position getCurrentPosition() {
    return currentPosition;
  }

  @Override
  public boolean verifyCurrentInstructionCanThrow() {
    return currentDexInstruction.canThrow();
  }

  @Override
  public boolean verifyLocalInScope(DebugLocalInfo local) {
    return true;
  }

  private void updateCurrentCatchHandlers(int instructionIndex) {
    Try tryRange = getTryForOffset(instructionOffset(instructionIndex));
    if (tryRange == currentTryRange) {
      return;
    }
    currentTryRange = tryRange;
    if (tryRange == null) {
      currentCatchHandlers = null;
    } else {
      currentCatchHandlers = new CatchHandlers<>(
          getTryHandlerGuards(tryRange),
          getTryHandlerOffsets(tryRange));
    }
  }

  private void updateDebugPosition(int instructionIndex, IRBuilder builder) {
    if (debugEntries == null || debugEntries.isEmpty()) {
      return;
    }
    DexDebugEntry current = null;
    int offset = instructionOffset(instructionIndex);
    for (DexDebugEntry entry : debugEntries) {
      if (entry.address > offset) {
        break;
      }
      current = entry;
    }
    if (current == null) {
      currentPosition = Position.none();
    } else {
      currentPosition = getCanonicalPosition(current);
      if (current.address == offset) {
        builder.addDebugPosition(currentPosition);
      }
    }
  }

  private Position getCanonicalPosition(DexDebugEntry entry) {
    return canonicalPositions.computeIfAbsent(new Position(entry.line, entry.sourceFile), p -> p);
  }

  @Override
  public void clear() {
    switchPayloadResolver.clear();
    arrayFilledDataPayloadResolver.clear();
  }

  @Override
  public int instructionIndex(int instructionOffset) {
    return offsetToInstructionIndex.get(instructionOffset);
  }

  @Override
  public int instructionOffset(int instructionIndex) {
    return code.instructions[instructionIndex].getOffset();
  }

  @Override
  public void resolveAndBuildSwitch(int value, int fallthroughOffset, int payloadOffset,
      IRBuilder builder) {
    builder.addSwitch(value, switchPayloadResolver.getKeys(payloadOffset), fallthroughOffset,
        switchPayloadResolver.absoluteTargets(payloadOffset));
  }

  @Override
  public void resolveAndBuildNewArrayFilledData(int arrayRef, int payloadOffset,
      IRBuilder builder) {
    builder.addNewArrayFilledData(arrayRef,
        arrayFilledDataPayloadResolver.getElementWidth(payloadOffset),
        arrayFilledDataPayloadResolver.getSize(payloadOffset),
        arrayFilledDataPayloadResolver.getData(payloadOffset));
  }

  private List<ValueType> computeArgumentTypes() {
    List<ValueType> types = new ArrayList<>(proto.parameters.size());
    String shorty = proto.shorty.toString();
    for (int i = 1; i < proto.shorty.size; i++) {
      ValueType valueType = ValueType.fromTypeDescriptorChar(shorty.charAt(i));
      types.add(valueType);
    }
    return types;
  }

  private boolean isInvoke(Instruction dex) {
    return dex instanceof InvokeDirect
        || dex instanceof InvokeDirectRange
        || dex instanceof InvokeVirtual
        || dex instanceof InvokeVirtualRange
        || dex instanceof InvokeInterface
        || dex instanceof InvokeInterfaceRange
        || dex instanceof InvokeStatic
        || dex instanceof InvokeStaticRange
        || dex instanceof InvokeSuper
        || dex instanceof InvokeSuperRange
        || dex instanceof InvokePolymorphic
        || dex instanceof InvokePolymorphicRange
        || dex instanceof FilledNewArray
        || dex instanceof FilledNewArrayRange;
  }

  private boolean isMoveResult(Instruction dex) {
    return dex instanceof MoveResult
        || dex instanceof MoveResultObject
        || dex instanceof MoveResultWide;
  }

  @Override
  public int traceInstruction(int index, IRBuilder builder) {
    Instruction dex = code.instructions[index];
    int offset = dex.getOffset();
    assert !dex.isPayload();
    int[] targets = dex.getTargets();
    if (targets != Instruction.NO_TARGETS) {
      // Check that we don't ever have instructions that can throw and have targets.
      assert !dex.canThrow();
      for (int relativeOffset : targets) {
        builder.ensureNormalSuccessorBlock(offset, offset + relativeOffset);
      }
      return index;
    }
    if (dex.canThrow()) {
      // If the instruction can throw and is in a try block, add edges to its catch successors.
      Try tryRange = getTryForOffset(offset);
      if (tryRange != null) {
        // Ensure the block starts at the start of the try-range (don't enqueue, not a target).
        int tryRangeStartAddress = tryRange.startAddress;
        if (isMoveResult(code.instructions[offsetToInstructionIndex.get(tryRangeStartAddress)])) {
          // If a handler range starts at a move result instruction it is safe to start it at
          // the following instruction since the move-result cannot throw an exception. Doing so
          // makes sure that we do not split an invoke and its move result instruction across
          // two blocks.
          ++tryRangeStartAddress;
        }
        builder.ensureBlockWithoutEnqueuing(tryRangeStartAddress);
        // Edge to exceptional successors.
        for (Integer handlerOffset : getUniqueTryHandlerOffsets(tryRange)) {
          builder.ensureExceptionalSuccessorBlock(offset, handlerOffset);
        }
        // If the following instruction is a move-result include it in this (the invokes) block.
        if (index + 1 < code.instructions.length && isMoveResult(code.instructions[index + 1])) {
          assert isInvoke(dex);
          ++index;
          dex = code.instructions[index];
        }
        // Edge to normal successor if any (fallthrough).
        if (!(dex instanceof Throw)) {
          builder.ensureNormalSuccessorBlock(offset, dex.getOffset() + dex.getSize());
        }
        return index;
      }
      // Close the block if the instruction is a throw, otherwise the block remains open.
      return dex instanceof Throw ? index : -1;
    }
    if (dex.isSwitch()) {
      // TODO(zerny): Remove this from block computation.
      switchPayloadResolver.addPayloadUser(dex);

      for (int target : switchPayloadResolver.absoluteTargets(dex)) {
        builder.ensureNormalSuccessorBlock(offset, target);
      }
      builder.ensureNormalSuccessorBlock(offset, offset + dex.getSize());
      return index;
    }
    // TODO(zerny): Remove this from block computation.
    if (dex.hasPayload()) {
      arrayFilledDataPayloadResolver.addPayloadUser((FillArrayData) dex);
    }
    // This instruction does not close the block.
    return -1;
  }

  private boolean inTryRange(Try tryItem, int offset) {
    return tryItem.startAddress <= offset
        && offset < tryItem.startAddress + tryItem.instructionCount;
  }

  private Try getTryForOffset(int offset) {
    for (Try tryRange : code.tries) {
      if (inTryRange(tryRange, offset)) {
        return tryRange;
      }
    }
    return null;
  }

  private Set<Integer> getUniqueTryHandlerOffsets(Try tryRange) {
    return new HashSet<>(getTryHandlerOffsets(tryRange));
  }

  private List<Integer> getTryHandlerOffsets(Try tryRange) {
    List<Integer> handlerOffsets = new ArrayList<>();
    TryHandler handler = code.handlers[tryRange.handlerIndex];
    for (TypeAddrPair pair : handler.pairs) {
      handlerOffsets.add(pair.addr);
    }
    if (handler.catchAllAddr != TryHandler.NO_HANDLER) {
      handlerOffsets.add(handler.catchAllAddr);
    }
    return handlerOffsets;
  }

  private List<DexType> getTryHandlerGuards(Try tryRange) {
    List<DexType> handlerGuards = new ArrayList<>();
    TryHandler handler = code.handlers[tryRange.handlerIndex];
    for (TypeAddrPair pair : handler.pairs) {
      handlerGuards.add(pair.type);
    }
    if (handler.catchAllAddr != TryHandler.NO_HANDLER) {
      handlerGuards.add(DexItemFactory.catchAllType);
    }
    return handlerGuards;
  }
}
