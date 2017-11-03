// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.DexDebugEvent.StartLocal;
import com.android.tools.r8.graph.DexEncodedMethod.DebugPositionRange;
import com.android.tools.r8.ir.code.Argument;
import com.android.tools.r8.ir.code.DebugLocalsChange;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.utils.InternalOptions;
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMaps;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Builder for constructing a list of debug events suitable for DexDebugInfo.
 *
 * This builder is intended to be very pedantic and ensure a well-formed structure of the resulting
 * event stream.
 */
public class DexDebugEventBuilder {

  private static final int NO_PC_INFO = -1;
  private static final int NO_LINE_INFO = -1;

  private final DexEncodedMethod method;
  private final DexItemFactory factory;
  private final InternalOptions options;

  // In order list of non-this argument locals.
  private ArrayList<DebugLocalInfo> arguments;

  // Mapping from register to the last known local in that register (See DBG_RESTART_LOCAL).
  private Int2ReferenceMap<DebugLocalInfo> lastKnownLocals;

  // Mapping from register to local for currently open/visible locals.
  private Int2ReferenceMap<DebugLocalInfo> pendingLocals = null;

  // Conservative pending-state of locals to avoid some equality checks on locals.
  // pendingLocalChanges == true ==> localsEqual(emittedLocals, pendingLocals).
  private boolean pendingLocalChanges = false;

  // State of pc, line, file and locals in the emitted event stream.
  private int emittedPc = NO_PC_INFO;
  private Position emittedPosition = Position.none();
  private Int2ReferenceMap<DebugLocalInfo> emittedLocals;

  // Emitted events.
  private final List<DexDebugEvent> events = new ArrayList<>();

  // Initial known line for the method.
  private int startLine = NO_LINE_INFO;

  // True if running in debug-mode with input code that contains line information, otherwise false.
  private boolean hasDebugPositions;

  private DexEncodedMethod.DebugPositionRangeList.Builder debugPositionListBuilder =
      new DexEncodedMethod.DebugPositionRangeList.Builder();

  public DexDebugEventBuilder(IRCode code, InternalOptions options) {
    this.method = code.method;
    this.factory = options.itemFactory;
    this.options = options;
    hasDebugPositions = code.hasDebugPositions;
  }

  /** Add events at pc for instruction. */
  public void add(int pc, int postPc, Instruction instruction) {
    boolean isBlockEntry = instruction.getBlock().entry() == instruction;
    boolean isBlockExit = instruction.getBlock().exit() == instruction;

    // Initialize locals state on block entry.
    if (isBlockEntry) {
      updateBlockEntry(instruction);
    }
    assert pendingLocals != null;

    Position position = instruction.getPosition();
    boolean pcAdvancing = pc != postPc;

    // In release mode we can only check that all throwing instructions have positions.
    // See IRCode's isConsistentGraph and computeAllThrowingInstructionsHavePositions.

    // In debug mode check that all non-nop instructions have positions.
    assert startLine == NO_LINE_INFO || !hasDebugPositions || !pcAdvancing || position.isSome()
        : "PC-advancing instruction " + instruction + " expected to have an associated position.";

    // In any mode check that nop instructions have no position info.
    assert pcAdvancing || position.isNone()
        : "Nop instruction " + instruction + " must never have an associated position.";

    if (instruction.isArgument()) {
      startArgument(instruction.asArgument());
    } else if (instruction.isDebugLocalsChange()) {
      updateLocals(instruction.asDebugLocalsChange());
    }

    if (!position.isNone() && !position.equals(emittedPosition)) {
      if (options.debug || instruction.instructionInstanceCanThrow()) {
        emitDebugPosition(pc, position);
      }
    }

    if (!isBlockExit && emittedPc != pc && pcAdvancing) {
      // For non-exit / pc-advancing instructions emit any pending changes.
      emitLocalChanges(pc);
    }

    if (isBlockExit) {
      // If this is the end of the block clear out the pending state.
      pendingLocals = null;
      pendingLocalChanges = false;
    }
  }

  /** Build the resulting DexDebugInfo object. */
  public DexDebugInfo build() {
    assert pendingLocals == null;
    assert !pendingLocalChanges;
    if (startLine == NO_LINE_INFO) {
      return null;
    }
    DexString[] params = new DexString[method.method.getArity()];
    if (arguments != null) {
      assert params.length == arguments.size();
      for (int i = 0; i < arguments.size(); i++) {
        DebugLocalInfo local = arguments.get(i);
        params[i] = (local == null || local.signature != null) ? null : local.name;
      }
    }
    return new DexDebugInfo(startLine, params, events.toArray(new DexDebugEvent[events.size()]));
  }

  public List<DebugPositionRange> buildPositionRanges() {
    return debugPositionListBuilder.build();
  }

  private void updateBlockEntry(Instruction instruction) {
    assert pendingLocals == null;
    assert !pendingLocalChanges;
    Int2ReferenceMap<DebugLocalInfo> locals = instruction.getBlock().getLocalsAtEntry();
    if (locals == null) {
      pendingLocals = Int2ReferenceMaps.emptyMap();
    } else {
      pendingLocals = new Int2ReferenceOpenHashMap<>(locals);
      pendingLocalChanges = true;
    }
    if (emittedLocals == null) {
      initialize(locals);
    }
  }

  private void initialize(Int2ReferenceMap<DebugLocalInfo> locals) {
    assert arguments == null;
    assert emittedLocals == null;
    assert lastKnownLocals == null;
    assert startLine == NO_LINE_INFO;
    if (locals == null) {
      emittedLocals = Int2ReferenceMaps.emptyMap();
      lastKnownLocals = Int2ReferenceMaps.emptyMap();
      return;
    }
    // Implicitly open all unparameterized arguments.
    emittedLocals = new Int2ReferenceOpenHashMap<>();
    for (Entry<DebugLocalInfo> entry : locals.int2ReferenceEntrySet()) {
      if (entry.getValue().signature == null) {
        emittedLocals.put(entry.getIntKey(), entry.getValue());
      }
    }
    lastKnownLocals = new Int2ReferenceOpenHashMap<>(emittedLocals);
  }

  private void startArgument(Argument argument) {
    if (arguments == null) {
      arguments = new ArrayList<>(method.method.getArity());
    }
    if (!argument.outValue().isThis()) {
      arguments.add(argument.getLocalInfo());
    }
  }

  private void updateLocals(DebugLocalsChange change) {
    pendingLocalChanges = true;
    change.apply(pendingLocals);
  }

  private boolean localsChanged() {
    if (!pendingLocalChanges) {
      return false;
    }
    pendingLocalChanges = !localsEqual(emittedLocals, pendingLocals);
    return pendingLocalChanges;
  }

  private void emitDebugPosition(int pc, Position position) {
    assert !position.equals(emittedPosition);
    if (startLine == NO_LINE_INFO) {
      if (position.synthetic) {
        // Ignore synthetic positions prior to any actual position.
        return;
      }
      assert emittedPosition.isNone();
      startLine = position.line;
      emittedPosition = position;
    }
    debugPositionListBuilder.add(position.line, position.line);
    emitAdvancementEvents(emittedPc, emittedPosition, pc, position, events, factory);
    emittedPc = pc;
    emittedPosition = position;
    if (localsChanged()) {
      emitLocalChangeEvents(emittedLocals, pendingLocals, lastKnownLocals, events, factory);
      assert localsEqual(emittedLocals, pendingLocals);
    }
    pendingLocalChanges = false;
  }

  private void emitLocalChanges(int pc) {
    // If pc advanced since the locals changed and locals indeed have changed, emit the changes.
    if (localsChanged()) {
      emitAdvancementEvents(emittedPc, emittedPosition, pc, emittedPosition, events, factory);
      emittedPc = pc;
      emitLocalChangeEvents(emittedLocals, pendingLocals, lastKnownLocals, events, factory);
      pendingLocalChanges = false;
      assert localsEqual(emittedLocals, pendingLocals);
    }
  }

  private static void emitAdvancementEvents(
      int previousPc,
      Position previousPosition,
      int nextPc,
      Position nextPosition,
      List<DexDebugEvent> events,
      DexItemFactory factory) {
    assert previousPc != nextPc;
    int pcDelta = previousPc == NO_PC_INFO ? nextPc : nextPc - previousPc;
    assert !previousPosition.isNone() || nextPosition.isNone();
    assert nextPosition.isNone() || nextPosition.line >= 0;
    int lineDelta = nextPosition.isNone() ? 0 : nextPosition.line - previousPosition.line;
    assert pcDelta >= 0;
    if (nextPosition.file != previousPosition.file) {
      events.add(factory.createSetFile(nextPosition.file));
    }
    if (lineDelta < Constants.DBG_LINE_BASE
        || lineDelta - Constants.DBG_LINE_BASE >= Constants.DBG_LINE_RANGE) {
      events.add(factory.createAdvanceLine(lineDelta));
      // TODO(herhut): To be super clever, encode only the part that is above limit.
      lineDelta = 0;
    }
    if (pcDelta >= Constants.DBG_ADDRESS_RANGE) {
      events.add(factory.createAdvancePC(pcDelta));
      pcDelta = 0;
    }
    // TODO(herhut): Maybe only write this one if needed (would differ from DEX).
    int specialOpcode =
        0x0a + (lineDelta - Constants.DBG_LINE_BASE) + Constants.DBG_LINE_RANGE * pcDelta;
    assert specialOpcode >= 0x0a;
    assert specialOpcode <= 0xff;
    events.add(factory.createDefault(specialOpcode));
  }

  public static void emitLocalChangeEvents(
      Int2ReferenceMap<DebugLocalInfo> previousLocals,
      Int2ReferenceMap<DebugLocalInfo> nextLocals,
      Int2ReferenceMap<DebugLocalInfo> lastKnownLocals,
      List<DexDebugEvent> events,
      DexItemFactory factory) {
    Int2ReferenceSortedMap<DebugLocalInfo> ending = new Int2ReferenceAVLTreeMap<>();
    Int2ReferenceSortedMap<DebugLocalInfo> starting = new Int2ReferenceAVLTreeMap<>();
    for (Entry<DebugLocalInfo> entry : previousLocals.int2ReferenceEntrySet()) {
      int register = entry.getIntKey();
      DebugLocalInfo local = entry.getValue();
      if (nextLocals.get(register) != local) {
        ending.put(register, local);
      }
    }
    for (Entry<DebugLocalInfo> entry : nextLocals.int2ReferenceEntrySet()) {
      int register = entry.getIntKey();
      DebugLocalInfo local = entry.getValue();
      if (previousLocals.get(register) != local) {
        starting.put(register, local);
      }
    }
    assert !ending.isEmpty() || !starting.isEmpty();
    for (Entry<DebugLocalInfo> end : ending.int2ReferenceEntrySet()) {
      int register = end.getIntKey();
      if (!starting.containsKey(register)) {
        previousLocals.remove(register);
        events.add(factory.createEndLocal(register));
      }
    }
    for (Entry<DebugLocalInfo> start : starting.int2ReferenceEntrySet()) {
      int register = start.getIntKey();
      DebugLocalInfo local = start.getValue();
      previousLocals.put(register, local);
      if (lastKnownLocals.get(register) == local) {
        events.add(factory.createRestartLocal(register));
      } else {
        events.add(new StartLocal(register, local));
        lastKnownLocals.put(register, local);
      }
    }
  }

  private static boolean localsEqual(
      Int2ReferenceMap<DebugLocalInfo> locals1, Int2ReferenceMap<DebugLocalInfo> locals2) {
    if (locals1 == locals2) {
      return true;
    }
    if (locals1.size() != locals2.size()) {
      return false;
    }
    for (Int2ReferenceMap.Entry<DebugLocalInfo> entry : locals1.int2ReferenceEntrySet()) {
      if (locals2.get(entry.getIntKey()) != entry.getValue()) {
        return false;
      }
    }
    return true;
  }
}
