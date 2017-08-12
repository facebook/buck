// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.code.ReturnVoid;
import com.android.tools.r8.code.SwitchPayload;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.ValueNumberGenerator;
import com.android.tools.r8.ir.conversion.DexSourceCode;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringUtils;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// DexCode corresponds to code item in dalvik/dex-format.html
public class DexCode extends Code {

  public final int registerSize;
  public final int incomingRegisterSize;
  public final int outgoingRegisterSize;
  public final Try[] tries;
  public final TryHandler[] handlers;
  public final Instruction[] instructions;

  public final DexString highestSortingString;
  private DexDebugInfo debugInfo;

  public DexCode(
      int registerSize,
      int insSize,
      int outsSize,
      Instruction[] instructions,
      Try[] tries,
      TryHandler[] handlers,
      DexDebugInfo debugInfo,
      DexString highestSortingString) {
    this.incomingRegisterSize = insSize;
    this.registerSize = registerSize;
    this.outgoingRegisterSize = outsSize;
    this.instructions = instructions;
    this.tries = tries;
    this.handlers = handlers;
    this.debugInfo = debugInfo;
    this.highestSortingString = highestSortingString;
    hashCode();  // Cache the hash code eagerly.
  }

  @Override
  public boolean isDexCode() {
    return true;
  }

  @Override
  public int estimatedSizeForInlining() {
    return instructions.length;
  }

  @Override
  public DexCode asDexCode() {
    return this;
  }

  public DexDebugInfo getDebugInfo() {
    return debugInfo;
  }

  public void setDebugInfo(DexDebugInfo debugInfo) {
    this.debugInfo = debugInfo;
  }

  public boolean hasDebugPositions() {
    if (debugInfo != null) {
      for (DexDebugEvent event : debugInfo.events) {
        if (event instanceof DexDebugEvent.Default) {
          return true;
        }
      }
    }
    return false;
  }

  public DexDebugInfo debugInfoWithAdditionalFirstParameter(DexString name) {
    if (debugInfo == null) {
      return null;
    }
    DexString[] parameters = debugInfo.parameters;
    DexString[] newParameters = new DexString[parameters.length + 1];
    newParameters[0] = name;
    System.arraycopy(parameters, 0, newParameters, 1, parameters.length);
    return new DexDebugInfo(debugInfo.startLine, newParameters, debugInfo.events);
  }

  public int codeSizeInBytes() {
    Instruction last = instructions[instructions.length - 1];
    return last.getOffset() + last.getSize();
  }

  @Override
  public int computeHashCode() {
    return incomingRegisterSize * 2
        + registerSize * 3
        + outgoingRegisterSize * 5
        + Arrays.hashCode(instructions) * 7
        + ((debugInfo == null) ? 0 : debugInfo.hashCode()) * 11
        + Arrays.hashCode(tries) * 13
        + Arrays.hashCode(handlers) * 17;
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexCode) {
      DexCode o = (DexCode) other;
      if (incomingRegisterSize != o.incomingRegisterSize) {
        return false;
      }
      if (registerSize != o.registerSize) {
        return false;
      }
      if (outgoingRegisterSize != o.outgoingRegisterSize) {
        return false;
      }
      if (debugInfo == null) {
        if (o.debugInfo != null) {
          return false;
        }
      } else {
        if (!debugInfo.equals(o.debugInfo)) {
          return false;
        }
      }
      if (!Arrays.equals(tries, o.tries)) {
        return false;
      }
      if (!Arrays.equals(handlers, o.handlers)) {
        return false;
      }
      // Save the most expensive operation to last.
      return Arrays.equals(instructions, o.instructions);
    }
    return false;
  }

  public boolean isEmptyVoidMethod() {
    return instructions.length == 1 && instructions[0] instanceof ReturnVoid;
  }

  @Override
  public IRCode buildIR(DexEncodedMethod encodedMethod, InternalOptions options)
      throws ApiLevelException {
    DexSourceCode source = new DexSourceCode(this, encodedMethod);
    IRBuilder builder = new IRBuilder(encodedMethod, source, options);
    return builder.build();
  }

  public IRCode buildIR(
      DexEncodedMethod encodedMethod,
      InternalOptions options, ValueNumberGenerator valueNumberGenerator)
      throws ApiLevelException {
    DexSourceCode source = new DexSourceCode(this, encodedMethod);
    IRBuilder builder = new IRBuilder(encodedMethod, source, options, valueNumberGenerator);
    return builder.build();
  }

  @Override
  public void registerReachableDefinitions(UseRegistry registry) {
    for (Instruction insn : instructions) {
      insn.registerUse(registry);
    }
  }

  @Override
  public String toString() {
    return toString(null, null);
  }

  @Override
  public String toString(DexEncodedMethod method, ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    if (method != null) {
      builder.append(method.toSourceString()).append("\n");
    }
    builder.append("registers: ").append(registerSize);
    builder.append(", inputs: ").append(incomingRegisterSize);
    builder.append(", outputs: ").append(outgoingRegisterSize).append("\n");
    builder.append("------------------------------------------------------------\n");
    builder.append("inst#  offset  instruction         arguments\n");
    builder.append("------------------------------------------------------------\n");

    // Collect payload users.
    Map<Integer, Instruction> payloadUsers = new HashMap<>();
    for (Instruction dex : instructions) {
      if (dex.hasPayload()) {
        payloadUsers.put(dex.getOffset() + dex.getPayloadOffset(), dex);
      }
    }

    DexDebugEntry debugInfo = null;
    Iterator<DexDebugEntry> debugInfoIterator = Collections.emptyIterator();
    if (getDebugInfo() != null && method != null) {
      debugInfoIterator = new DexDebugEntryBuilder(method, new DexItemFactory()).build().iterator();
      debugInfo = debugInfoIterator.hasNext() ? debugInfoIterator.next() : null;
    }
    int instructionNumber = 0;
    for (Instruction insn : instructions) {
      while (debugInfo != null && debugInfo.address == insn.getOffset()) {
        builder.append("         ").append(debugInfo.toString(false)).append("\n");
        debugInfo = debugInfoIterator.hasNext() ? debugInfoIterator.next() : null;
      }
      StringUtils.appendLeftPadded(builder, Integer.toString(instructionNumber++), 5);
      builder.append(": ");
      if (insn.isSwitchPayload()) {
        Instruction payloadUser = payloadUsers.get(insn.getOffset());
        builder.append(insn.toString(naming, payloadUser));
      } else {
        builder.append(insn.toString(naming));
      }
      builder.append('\n');
    }
    if (debugInfoIterator.hasNext()) {
      throw new Unreachable("Could not print all debug information.");
    }
    if (tries.length > 0) {
      builder.append("Tries (numbers are offsets)\n");
      for (Try atry : tries) {
        builder.append("  ");
        builder.append(atry.toString());
        builder.append('\n');
      }
      if (handlers != null) {
        builder.append("Handlers (numbers are offsets)\n");
        for (int handlerIndex = 0; handlerIndex < handlers.length; handlerIndex++) {
          TryHandler handler = handlers[handlerIndex];
          builder.append("  ").append(handlerIndex).append(": ");
          builder.append(handler.toString());
          builder.append('\n');
        }
      }
    }
    return builder.toString();
  }

  public String toSmaliString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    // Find labeled targets.
    Map<Integer, Instruction> payloadUsers = new HashMap<>();
    Set<Integer> labledTargets = new HashSet<>();
    // Collect payload users and labeled targets for non-payload instructions.
    for (Instruction dex : instructions) {
      int[] targets = dex.getTargets();
      if (targets != Instruction.NO_TARGETS && targets != Instruction.EXIT_TARGET) {
        assert targets.length <= 2;
        // For if instructions the second target is the fallthrough, for which no label is needed.
        labledTargets.add(dex.getOffset() + targets[0]);
      } else if (dex.hasPayload()) {
        labledTargets.add(dex.getOffset() + dex.getPayloadOffset());
        payloadUsers.put(dex.getOffset() + dex.getPayloadOffset(), dex);
      }
    }
    // Collect labeled targets for payload instructions.
    for (Instruction dex : instructions) {
      if (dex.isSwitchPayload()) {
        Instruction payloadUser = payloadUsers.get(dex.getOffset());
        if (dex instanceof SwitchPayload) {
          SwitchPayload payload = (SwitchPayload) dex;
          for (int target : payload.switchTargetOffsets()) {
            labledTargets.add(payloadUser.getOffset() + target);
          }
        }
      }
    }
    // Generate smali for all instructions.
    for (Instruction dex : instructions) {
      if (labledTargets.contains(dex.getOffset())) {
        builder.append("  :label_");
        builder.append(dex.getOffset());
        builder.append("\n");
      }
      if (dex.isSwitchPayload()) {
        Instruction payloadUser = payloadUsers.get(dex.getOffset());
        builder.append(dex.toSmaliString(payloadUser)).append('\n');
      } else {
        builder.append(dex.toSmaliString(naming)).append('\n');
      }
    }
    if (tries.length > 0) {
      builder.append("Tries (numbers are offsets)\n");
      for (Try atry : tries) {
        builder.append("  ");
        builder.append(atry.toString());
        builder.append('\n');
      }
      if (handlers != null) {
        builder.append("Handlers (numbers are offsets)\n");
        for (TryHandler handler : handlers) {
          builder.append(handler.toString());
          builder.append('\n');
        }
      }
    }
    return builder.toString();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    for (Instruction insn : instructions) {
      insn.collectIndexedItems(indexedItems);
    }
    if (debugInfo != null) {
      debugInfo.collectIndexedItems(indexedItems);
    }
    if (handlers != null) {
      for (TryHandler handler : handlers) {
        handler.collectIndexedItems(indexedItems);
      }
    }
  }

  public boolean usesExceptionHandling() {
    return tries.length != 0;
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    if (mixedItems.add(this)) {
      if (debugInfo != null) {
        debugInfo.collectMixedSectionItems(mixedItems);
      }
    }
  }

  public static class Try extends DexItem {

    public static final int NO_INDEX = -1;

    public final int handlerOffset;
    public /* offset */ int startAddress;
    public /* offset */ int instructionCount;
    public int handlerIndex;

    public Try(int startAddress, int instructionCount, int handlerOffset) {
      this.startAddress = startAddress;
      this.instructionCount = instructionCount;
      this.handlerOffset = handlerOffset;
      this.handlerIndex = NO_INDEX;
    }

    public void setHandlerIndex(Int2IntMap map) {
      handlerIndex = map.get(handlerOffset);
    }

    @Override
    public int hashCode() {
      return startAddress * 2 + instructionCount * 3 + handlerIndex * 5;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other instanceof Try) {
        Try o = (Try) other;
        if (startAddress != o.startAddress) {
          return false;
        }
        if (instructionCount != o.instructionCount) {
          return false;
        }
        return handlerIndex == o.handlerIndex;
      }
      return false;
    }

    @Override
    public String toString() {
      return "["
          + StringUtils.hexString(startAddress, 2)
          + " .. "
          + StringUtils.hexString(startAddress + instructionCount - 1, 2)
          + "] -> "
          + handlerIndex;
    }

    @Override
    void collectIndexedItems(IndexedItemCollection indexedItems) {
      // Intentionally left empty.
    }

    @Override
    void collectMixedSectionItems(MixedSectionCollection mixedItems) {
      // Should never be visited.
      assert false;
    }

  }

  public static class TryHandler extends DexItem {

    public static final int NO_HANDLER = -1;

    public final TypeAddrPair[] pairs;
    public final /* offset */ int catchAllAddr;

    public TryHandler(TypeAddrPair[] pairs, int catchAllAddr) {
      this.pairs = pairs;
      this.catchAllAddr = catchAllAddr;
    }

    @Override
    public int hashCode() {
      return catchAllAddr + Arrays.hashCode(pairs) * 7;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other instanceof TryHandler) {
        TryHandler o = (TryHandler) other;
        if (catchAllAddr != o.catchAllAddr) {
          return false;
        }
        return Arrays.equals(pairs, o.pairs);
      }
      return false;
    }

    @Override
    public void collectIndexedItems(IndexedItemCollection indexedItems) {
      collectAll(indexedItems, pairs);
    }

    @Override
    void collectMixedSectionItems(MixedSectionCollection mixedItems) {
      // Should never be visited.
      assert false;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("[\n");
      for (TypeAddrPair pair : pairs) {
        builder.append("       ");
        builder.append(pair.type);
        builder.append(" -> ");
        builder.append(StringUtils.hexString(pair.addr, 2));
        builder.append("\n");
      }
      if (catchAllAddr != NO_HANDLER) {
        builder.append("       default -> ");
        builder.append(StringUtils.hexString(catchAllAddr, 2));
        builder.append("\n");
      }
      builder.append("     ]");
      return builder.toString();
    }

    public static class TypeAddrPair extends DexItem {

      public final DexType type;
      public final /* offset */ int addr;

      public TypeAddrPair(DexType type, int addr) {
        this.type = type;
        this.addr = addr;
      }

      @Override
      public void collectIndexedItems(IndexedItemCollection indexedItems) {
        type.collectIndexedItems(indexedItems);
      }

      @Override
      void collectMixedSectionItems(MixedSectionCollection mixedItems) {
        // Should never be visited.
        assert false;
      }

      @Override
      public int hashCode() {
        return type.hashCode() * 7 + addr;
      }

      @Override
      public boolean equals(Object other) {
        if (this == other) {
          return true;
        }
        if (other instanceof TypeAddrPair) {
          TypeAddrPair o = (TypeAddrPair) other;
          return type.equals(o.type) && addr == o.addr;
        }
        return false;
      }
    }
  }
}
