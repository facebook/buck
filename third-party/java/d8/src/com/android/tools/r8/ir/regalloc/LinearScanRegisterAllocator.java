// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.code.MoveType;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.ir.code.Add;
import com.android.tools.r8.ir.code.And;
import com.android.tools.r8.ir.code.ArithmeticBinop;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.DebugLocalsChange;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.Move;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.code.Or;
import com.android.tools.r8.ir.code.Phi;
import com.android.tools.r8.ir.code.Sub;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.code.Xor;
import com.android.tools.r8.ir.regalloc.RegisterPositions.Type;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ListUtils;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Linear scan register allocator.
 *
 * <p>The implementation is inspired by:
 *
 * <ul>
 *   <li>"Linear Scan Register Allocation in the Context of SSA Form and Register Constraints"
 *   (ftp://ftp.ssw.uni-linz.ac.at/pub/Papers/Moe02.PDF)</li>
 *   <li>"Linear Scan Register Allocation on SSA Form"
 *   (http://www.christianwimmer.at/Publications/Wimmer10a/Wimmer10a.pdf)</li>
 *   <li>"Linear Scan Register Allocation for the Java HotSpot Client Compiler"
 *   (http://www.christianwimmer.at/Publications/Wimmer04a/Wimmer04a.pdf)</li>
 * </ul>
 */
public class LinearScanRegisterAllocator implements RegisterAllocator {
  public static final int NO_REGISTER = Integer.MIN_VALUE;
  public static final int REGISTER_CANDIDATE_NOT_FOUND = -1;
  public static final int MIN_CONSTANT_FREE_FOR_POSITIONS = 5;

  private enum ArgumentReuseMode {
    ALLOW_ARGUMENT_REUSE,
    DISALLOW_ARGUMENT_REUSE
  }

  private static class LocalRange implements Comparable<LocalRange> {
    final Value value;
    final DebugLocalInfo local;
    final int register;
    final int start;
    final int end;

    LocalRange(Value value, int register, int start, int end) {
      assert value.hasLocalInfo();
      this.value = value;
      this.local = value.getLocalInfo();
      this.register = register;
      this.start = start;
      this.end = end;
    }

    @Override
    public int compareTo(LocalRange o) {
      return (start != o.start)
          ? Integer.compare(start, o.start)
          : Integer.compare(end, o.end);
    }

    @Override
    public String toString() {
      return local + " @ r" + register + ": " + new LiveRange(start, end);
    }
  }

  // When numbering instructions we number instructions only with even numbers. This allows us to
  // use odd instruction numbers for the insertion of moves during spilling.
  public static final int INSTRUCTION_NUMBER_DELTA = 2;
  // The max register number that will fit in any dex instruction encoding.
  private static final int MAX_SMALL_REGISTER = Constants.U4BIT_MAX;
  // We put one sentinel in front of the argument chain and one after the argument chain.
  private static final int NUMBER_OF_SENTINEL_REGISTERS = 2;

  // The code for which to allocate registers.
  private final IRCode code;
  // Number of registers used for arguments.
  private final int numberOfArgumentRegisters;
  // Compiler options.
  private final InternalOptions options;

  // Mapping from basic blocks to the set of values live at entry to that basic block.
  private Map<BasicBlock, Set<Value>> liveAtEntrySets = new IdentityHashMap<>();
  // The sentinel value starting the chain of linked argument values.
  private Value preArgumentSentinelValue = null;

  // The set of registers that are free for allocation.
  private TreeSet<Integer> freeRegisters = new TreeSet<>();
  // The max register number used.
  private int maxRegisterNumber = 0;
  // The next available register number not yet included in the set of used registers.
  private int nextUnusedRegisterNumber = 0;

  // List of all top-level live intervals for all SSA values.
  private List<LiveIntervals> liveIntervals = new ArrayList<>();
  // List of active intervals.
  private List<LiveIntervals> active = new LinkedList<>();
  // List of intervals where the current instruction falls into one of their live range holes.
  protected List<LiveIntervals> inactive = new LinkedList<>();
  // List of intervals that no register has been allocated to sorted by first live range.
  protected PriorityQueue<LiveIntervals> unhandled = new PriorityQueue<>();

  // The first register used for parallel moves. After register allocation the parallel move
  // temporary registers are [firstParallelMoveTemporary, maxRegisterNumber].
  private int firstParallelMoveTemporary = NO_REGISTER;
  // Mapping from register number to the number of unused register numbers below that register
  // number. Used for compacting the register numbers if some spill registers are not used
  // because their values can be rematerialized.
  private int[] unusedRegisters = null;

  // Whether or not the code has a move exception instruction. Used to pin the move exception
  // register.
  private boolean hasDedicatedMoveExceptionRegister = false;

  public LinearScanRegisterAllocator(IRCode code, InternalOptions options) {
    this.code = code;
    this.options = options;
    int argumentRegisters = 0;
    for (Instruction instruction : code.blocks.getFirst().getInstructions()) {
      if (instruction.isArgument()) {
        argumentRegisters += instruction.outValue().requiredRegisters();
      }
    }
    numberOfArgumentRegisters = argumentRegisters;
  }

  /**
   * Perform register allocation for the IRCode.
   */
  @Override
  public void allocateRegisters(boolean debug) {
    // There are no linked values prior to register allocation.
    assert noLinkedValues();
    assert code.isConsistentSSA();
    computeNeedsRegister();
    insertArgumentMoves();
    BasicBlock[] blocks = computeLivenessInformation();
    // First attempt to allocate register allowing argument reuse. This will fail if spilling
    // is required or if we end up using more than 16 registers.
    boolean noSpilling =
        performAllocationWithoutMoveInsertion(ArgumentReuseMode.ALLOW_ARGUMENT_REUSE);
    if (!noSpilling || (highestUsedRegister() > MAX_SMALL_REGISTER)) {
      // Redo allocation disallowing argument reuse. This always succeeds.
      clearRegisterAssignments();
      performAllocation(ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE);
    } else {
      // Insert spill and phi moves after allocating with argument reuse. If the moves causes
      // the method to use more than 16 registers we redo allocation disallowing argument
      // reuse. This very rarely happens in practice (12 methods on GMSCore v4 hits that case).
      insertMoves();
      if (highestUsedRegister() > MAX_SMALL_REGISTER) {
        // Redo allocation disallowing argument reuse. This always succeeds.
        clearRegisterAssignments();
        removeSpillAndPhiMoves();
        performAllocation(ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE);
      }
    }

    assert code.isConsistentGraph();
    if (Log.ENABLED) {
      Log.debug(this.getClass(), toString());
    }
    computeUnusedRegisters();
    if (debug) {
      computeDebugInfo(blocks);
    }
    clearUserInfo();
    clearState();
  }

  private static Integer nextInRange(int start, int end, List<Integer> points) {
    while (!points.isEmpty() && points.get(0) < start) {
      points.remove(0);
    }
    if (points.isEmpty()) {
      return null;
    }
    Integer next = points.get(0);
    assert start <= next;
    if (next < end) {
      points.remove(0);
      return next;
    }
    return null;
  }

  private void computeDebugInfo(BasicBlock[] blocks) {
    // Collect live-ranges for all SSA values with local information.
    List<LocalRange> ranges = new ArrayList<>();
    for (LiveIntervals interval : liveIntervals) {
      Value value = interval.getValue();
      if (!value.hasLocalInfo()) {
        continue;
      }
      List<Integer> starts = ListUtils.map(value.getDebugLocalStarts(), Instruction::getNumber);
      List<Integer> ends = ListUtils.map(value.getDebugLocalEnds(), Instruction::getNumber);
      List<LiveRange> liveRanges = new ArrayList<>();
      liveRanges.addAll(interval.getRanges());
      for (LiveIntervals child : interval.getSplitChildren()) {
        assert child.getValue() == value;
        assert child.getSplitChildren() == null || child.getSplitChildren().isEmpty();
        liveRanges.addAll(child.getRanges());
      }
      liveRanges.sort((r1, r2) -> Integer.compare(r1.start, r2.start));
      starts.sort(Integer::compare);
      ends.sort(Integer::compare);

      for (LiveRange liveRange : liveRanges) {
        int start = liveRange.start;
        int end = liveRange.end;
        Integer nextEnd;
        while ((nextEnd = nextInRange(start, end, ends)) != null) {
          // If an argument value has been split, we have disallowed argument reuse and therefore,
          // the argument value is also in the argument register throughout the method. For debug
          // information, we always use the argument register whenever a local corresponds to an
          // argument value. That avoids ending and restarting locals whenever we move arguments
          // to lower register.
          int register = getArgumentOrAllocateRegisterForValue(value, start);
          ranges.add(new LocalRange(value, register, start, nextEnd));
          Integer nextStart = nextInRange(nextEnd, end, starts);
          if (nextStart == null) {
            start = -1;
            break;
          }
          start = nextStart;
        }
        if (start >= 0) {
          ranges.add(new LocalRange(value, getArgumentOrAllocateRegisterForValue(value, start),
              start, end));
        }
      }
    }
    if (ranges.isEmpty()) {
      return;
    }
    ranges.sort(LocalRange::compareTo);

    // For each debug position compute the set of live locals.
    boolean localsChanged = false;
    Int2ReferenceMap<DebugLocalInfo> currentLocals = new Int2ReferenceOpenHashMap<>();

    LinkedList<LocalRange> openRanges = new LinkedList<>();
    Iterator<LocalRange> rangeIterator = ranges.iterator();
    LocalRange nextStartingRange = rangeIterator.next();

    // Open initial (argument) ranges.
    while (nextStartingRange != null && nextStartingRange.start == 0) {
      currentLocals.put(nextStartingRange.register, nextStartingRange.local);
      openRanges.add(nextStartingRange);
      nextStartingRange = rangeIterator.hasNext() ? rangeIterator.next() : null;
    }

    for (BasicBlock block : blocks) {
      ListIterator<Instruction> instructionIterator = block.listIterator();
      // Close ranges up-to but excluding the first instruction. Ends are exclusive but the values
      // are live upon entering the first instruction.
      int entryIndex = block.entry().getNumber();
      if (block.entry().isMoveException()) {
        // Close locals at a move exception since they close as part of the exceptional transfer.
        entryIndex++;
      }
      {
        ListIterator<LocalRange> it = openRanges.listIterator(0);
        while (it.hasNext()) {
          LocalRange openRange = it.next();
          if (openRange.end < entryIndex) {
            it.remove();
            assert currentLocals.get(openRange.register) == openRange.local;
            currentLocals.remove(openRange.register);
          }
        }
      }
      // Open ranges up-to but excluding the first instruction. Starts are inclusive but entry is
      // prior to the first instruction.
      while (nextStartingRange != null && nextStartingRange.start < entryIndex) {
        // If the range is live at this index open it. Again the end is inclusive here because the
        // instruction is live at block entry if it is live at entry to the first instruction.
        if (entryIndex <= nextStartingRange.end) {
          openRanges.add(nextStartingRange);
          assert !currentLocals.containsKey(nextStartingRange.register);
          currentLocals.put(nextStartingRange.register, nextStartingRange.local);
        }
        nextStartingRange = rangeIterator.hasNext() ? rangeIterator.next() : null;
      }
      if (block.entry().isMoveException()) {
        fixupLocalsLiveAtMoveException(block, instructionIterator, openRanges, currentLocals);
      } else {
        block.setLocalsAtEntry(new Int2ReferenceOpenHashMap<>(currentLocals));
      }
      while (instructionIterator.hasNext()) {
        Instruction instruction = instructionIterator.next();
        if (instruction.isDebugLocalRead()) {
          // Remove debug local reads now that local liveness is computed.
          assert !instruction.getDebugValues().isEmpty();
          instruction.clearDebugValues();
          instructionIterator.remove();
        }
        if (isSpillInstruction(instruction)) {
          continue;
        }
        int index = instruction.getNumber();
        ListIterator<LocalRange> it = openRanges.listIterator(0);
        Int2ReferenceMap<DebugLocalInfo> ending = new Int2ReferenceOpenHashMap<>();
        Int2ReferenceMap<DebugLocalInfo> starting = new Int2ReferenceOpenHashMap<>();
        while (it.hasNext()) {
          LocalRange openRange = it.next();
          // Any local change is inserted after the instruction so end is inclusive.
          if (openRange.end <= index) {
            it.remove();
            assert currentLocals.get(openRange.register) == openRange.local;
            currentLocals.remove(openRange.register);
            localsChanged = true;
            ending.put(openRange.register, openRange.local);
          }
        }
        while (nextStartingRange != null && nextStartingRange.start <= index) {
          // If the range is live at this index open it.
          if (index < nextStartingRange.end) {
            openRanges.add(nextStartingRange);
            assert !currentLocals.containsKey(nextStartingRange.register);
            currentLocals.put(nextStartingRange.register, nextStartingRange.local);
            starting.put(nextStartingRange.register, nextStartingRange.local);
            localsChanged = true;
          }
          nextStartingRange = rangeIterator.hasNext() ? rangeIterator.next() : null;
        }
        if (localsChanged && instruction.getBlock().exit() != instruction) {
          DebugLocalsChange change = createLocalsChange(ending, starting);
          if (change != null) {
            instructionIterator.add(change);
          }
        }
        localsChanged = false;
      }
    }
  }

  private void fixupLocalsLiveAtMoveException(
      BasicBlock block,
      ListIterator<Instruction> instructionIterator,
      List<LocalRange> openRanges,
      Int2ReferenceMap<DebugLocalInfo> finalLocals) {
    Int2ReferenceMap<DebugLocalInfo> initialLocals = new Int2ReferenceOpenHashMap<>();
    int exceptionalIndex = block.getPredecessors().get(0).exceptionalExit().getNumber();
    for (LocalRange open : openRanges) {
      int exceptionalRegister = getArgumentOrAllocateRegisterForValue(open.value, exceptionalIndex);
      initialLocals.put(exceptionalRegister, open.local);
    }
    block.setLocalsAtEntry(new Int2ReferenceOpenHashMap<>(initialLocals));
    Instruction entry = instructionIterator.next();
    assert block.entry() == entry;
    assert block.entry().isMoveException();
    // Skip past spill instructions.
    while (instructionIterator.hasNext()) {
      if (!isSpillInstruction(instructionIterator.next())) {
        instructionIterator.previous();
        break;
      }
    }
    // Compute the final change in locals and insert it after the last move.
    Int2ReferenceMap<DebugLocalInfo> ending = new Int2ReferenceOpenHashMap<>();
    Int2ReferenceMap<DebugLocalInfo> starting = new Int2ReferenceOpenHashMap<>();
    for (Entry<DebugLocalInfo> initialLocal : initialLocals.int2ReferenceEntrySet()) {
      if (finalLocals.get(initialLocal.getIntKey()) != initialLocal.getValue()) {
        ending.put(initialLocal.getIntKey(), initialLocal.getValue());
      }
    }
    for (Entry<DebugLocalInfo> finalLocal : finalLocals.int2ReferenceEntrySet()) {
      if (initialLocals.get(finalLocal.getIntKey()) != finalLocal.getValue()) {
        starting.put(finalLocal.getIntKey(), finalLocal.getValue());
      }
    }
    DebugLocalsChange change = createLocalsChange(ending, starting);
    if (change != null) {
      instructionIterator.add(change);
    }
  }

  private DebugLocalsChange createLocalsChange(
      Int2ReferenceMap<DebugLocalInfo> ending, Int2ReferenceMap<DebugLocalInfo> starting) {
    if (ending.isEmpty() && starting.isEmpty()) {
      return null;
    }
    if (ending.isEmpty() || starting.isEmpty()) {
      return new DebugLocalsChange(ending, starting);
    }
    IntSet unneeded = new IntArraySet(Math.min(ending.size(), starting.size()));
    for (Entry<DebugLocalInfo> entry : ending.int2ReferenceEntrySet()) {
      if (starting.get(entry.getIntKey()) == entry.getValue()) {
        unneeded.add(entry.getIntKey());
      }
    }
    if (unneeded.size() == ending.size() && unneeded.size() == starting.size()) {
      return null;
    }
    IntIterator iterator = unneeded.iterator();
    while (iterator.hasNext()) {
      int key = iterator.nextInt();
      ending.remove(key);
      starting.remove(key);
    }
    return new DebugLocalsChange(ending, starting);
  }

  private void clearState() {
    liveAtEntrySets = null;
    liveIntervals = null;
    active = null;
    inactive = null;
    unhandled = null;
    freeRegisters = null;
  }

  // Compute a table that for each register numbers contains the number of previous register
  // numbers that were unused. This table is then used to slide down the actual registers
  // used to fill the gaps.
  private void computeUnusedRegisters() {
    if (registersUsed() == 0) {
      return;
    }
    // Compute the set of registers that is used based on all live intervals.
    Set<Integer> usedRegisters = new HashSet<>();
    for (LiveIntervals intervals : liveIntervals) {
      addRegisterIfUsed(usedRegisters, intervals);
      for (LiveIntervals childIntervals : intervals.getSplitChildren()) {
        addRegisterIfUsed(usedRegisters, childIntervals);
      }
    }
    // Additionally, we have used temporary registers for parallel move scheduling, those
    // are used as well.
    for (int i = firstParallelMoveTemporary; i < maxRegisterNumber + 1; i++) {
      usedRegisters.add(realRegisterNumberFromAllocated(i));
    }
    // Compute the table based on the set of used registers.
    int unused = 0;
    int[] computed = new int[registersUsed()];
    for (int i = 0; i < registersUsed(); i++) {
      if (!usedRegisters.contains(i)) {
        unused++;
      }
      computed[i] = unused;
    }
    unusedRegisters = computed;
  }

  private void addRegisterIfUsed(Set<Integer> used, LiveIntervals intervals) {
    boolean unused = intervals.isSpilledAndRematerializable(this);
    if (!unused) {
      used.add(realRegisterNumberFromAllocated(intervals.getRegister()));
      if (intervals.getType() == MoveType.WIDE) {
        used.add(realRegisterNumberFromAllocated(intervals.getRegister() + 1));
      }
    }
  }

  @Override
  public boolean argumentValueUsesHighRegister(Value value, int instructionNumber) {
    return isHighRegister(
        getRegisterForValue(value, instructionNumber) + value.requiredRegisters() - 1);
  }

  public int highestUsedRegister() {
    return registersUsed() - 1;
  }

  @Override
  public int registersUsed() {
    int numberOfRegister = maxRegisterNumber + 1 - NUMBER_OF_SENTINEL_REGISTERS;
    if (unusedRegisters != null) {
      return numberOfRegister - unusedRegisters[unusedRegisters.length - 1];
    }
    return numberOfRegister;
  }

  @Override
  public int getRegisterForValue(Value value, int instructionNumber) {
    if (value.isFixedRegisterValue()) {
      return realRegisterNumberFromAllocated(value.asFixedRegisterValue().getRegister());
    }
    LiveIntervals intervals = value.getLiveIntervals();
    if (intervals.hasSplits()) {
      intervals = intervals.getSplitCovering(instructionNumber);
    }
    return getRegisterForIntervals(intervals);
  }

  @Override
  public int getArgumentOrAllocateRegisterForValue(Value value, int instructionNumber) {
    if (value.isArgument()) {
      return getRegisterForIntervals(value.getLiveIntervals());
    }
    return getRegisterForValue(value, instructionNumber);
  }

  private BasicBlock[] computeLivenessInformation() {
    BasicBlock[] blocks = code.numberInstructions();
    computeLiveAtEntrySets();
    computeLiveRanges();
    return blocks;
  }

  private boolean performAllocationWithoutMoveInsertion(ArgumentReuseMode mode) {
    pinArgumentRegisters();
    return performLinearScan(mode);
  }

  private boolean performAllocation(ArgumentReuseMode mode) {
    boolean result = performAllocationWithoutMoveInsertion(mode);
    insertMoves();
    if (mode == ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE) {
      // Now that we know the max register number we can compute whether it is safe to use
      // argument registers in place. If it is, we redo move insertion to get rid of the moves
      // caused by splitting of the argument registers.
      if (unsplitArguments()) {
        removeSpillAndPhiMoves();
        insertMoves();
      }
    }
    return result;
  }

  // When argument register reuse is disallowed, we split argument values to make sure that
  // we can get the argument into low enough registers at uses that require low numbers. After
  // register allocation we can check if it is safe to just use the argument register itself
  // for all uses and thereby avoid moving argument values around.
  private boolean unsplitArguments() {
    boolean argumentRegisterUnsplit = false;
    Value current = preArgumentSentinelValue;
    while (current != null) {
      LiveIntervals intervals = current.getLiveIntervals();
      assert intervals.getRegisterLimit() == Constants.U16BIT_MAX;
      boolean canUseArgumentRegister = true;
      for (LiveIntervals child : intervals.getSplitChildren()) {
        if (child.getRegisterLimit() < highestUsedRegister()) {
          canUseArgumentRegister = false;
          break;
        }
      }
      if (canUseArgumentRegister) {
        argumentRegisterUnsplit = true;
        for (LiveIntervals child : intervals.getSplitChildren()) {
          child.clearRegisterAssignment();
          child.setRegister(intervals.getRegister());
          child.setSpilled(false);
        }
      }
      current = current.getNextConsecutive();
    }
    return argumentRegisterUnsplit;
  }

  private void removeSpillAndPhiMoves() {
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        Instruction instruction = it.next();
        if (isSpillInstruction(instruction)) {
          it.remove();
        }
      }
    }
  }

  private static boolean isSpillInstruction(Instruction instruction) {
    Value outValue = instruction.outValue();
    if (outValue != null && outValue.isFixedRegisterValue()) {
      // Only move and const number instructions are inserted for spill and phi moves. The
      // const number instructions are for values that can be rematerialized instead of
      // spilled.
      assert instruction.getNumber() == -1;
      assert instruction.isMove() || instruction.isConstNumber();
      assert !instruction.isDebugInstruction();
      return true;
    }
    return false;
  }

  private void clearRegisterAssignments() {
    freeRegisters.clear();
    maxRegisterNumber = 0;
    nextUnusedRegisterNumber = 0;
    active.clear();
    inactive.clear();
    unhandled.clear();
    for (LiveIntervals intervals : liveIntervals) {
      intervals.clearRegisterAssignment();
    }
  }

  /**
   * Get the register allocated to a given set of live intervals.
   */
  private int getRegisterForIntervals(LiveIntervals intervals) {
    int intervalsRegister = intervals.getRegister();
    return realRegisterNumberFromAllocated(intervalsRegister);
  }

  int unadjustedRealRegisterFromAllocated(int allocated) {
    assert allocated != NO_REGISTER;
    assert allocated >= 0;
    int register;
    if (allocated < numberOfArgumentRegisters + NUMBER_OF_SENTINEL_REGISTERS) {
      // For the |numberOfArguments| first registers map to the correct argument register.
      // Due to the sentinel register in front of the arguments, the register number returned is
      // the argument number starting from one.
      register = maxRegisterNumber - (numberOfArgumentRegisters - allocated)
          - NUMBER_OF_SENTINEL_REGISTERS;
    } else {
      // For everything else use the lower numbers.
      register = allocated - numberOfArgumentRegisters - NUMBER_OF_SENTINEL_REGISTERS;
    }
    return register;
  }

  int realRegisterNumberFromAllocated(int allocated) {
    int register = unadjustedRealRegisterFromAllocated(allocated);
    // Adjust for spill registers that turn out to be unused because the value can be
    // rematerialized instead of spilled.
    if (unusedRegisters != null) {
      return register - unusedRegisters[register];
    }
    return register;
  }

  private boolean isHighRegister(int register) {
    return register > Constants.U4BIT_MAX;
  }

  private boolean performLinearScan(ArgumentReuseMode mode) {
    unhandled.addAll(liveIntervals);
    LiveIntervals argumentInterval = preArgumentSentinelValue.getLiveIntervals();
    while (argumentInterval != null) {
      assert argumentInterval.getRegister() != NO_REGISTER;
      unhandled.remove(argumentInterval);
      if (mode == ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
        // All the argument intervals are active in the beginning and have preallocated registers.
        active.add(argumentInterval);
      } else {
        // Treat the argument interval as spilled which will require a load to a different
        // register for all register-constrained usages.
        if (argumentInterval.getUses().size() > 1) {
          LiveIntervalsUse use = argumentInterval.firstUseWithConstraint();
          if (use != null) {
            LiveIntervals split;
            if (argumentInterval.numberOfUsesWithConstraint() == 1) {
              // If there is only one register-constrained use, split before
              // that one use.
              split = argumentInterval.splitBefore(use.getPosition());
            } else {
              // If there are multiple register-constrained users, split right after the definition
              // to make it more likely that arguments get in usable registers from the start.
              split = argumentInterval
                  .splitBefore(argumentInterval.getValue().definition.getNumber() + 1);
            }
            unhandled.add(split);
          }
        }
      }
      argumentInterval = argumentInterval.getNextConsecutive();
    }

    // We have to be careful when it comes to the register allocated for a move exception
    // instruction. For move exception instructions there is no place to put spill or
    // restore moves. The move exception instruction has to be the first instruction in a
    // catch handler.
    //
    // When we allow argument reuse we do not allow any splitting, therefore we cannot get into
    // trouble with move exception registers. When argument reuse is disallowed we block a fixed
    // register to be used only by move exception instructions.
    if (mode != ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
      // Force all move exception ranges to start out with the exception in a fixed register. Split
      // their live ranges which will force another register if used.
      int moveExceptionRegister = NO_REGISTER;
      List<LiveIntervals> moveExceptionIntervals = new ArrayList<>();
      boolean overlappingMoveExceptionIntervals = false;
      for (BasicBlock block : code.blocks) {
        for (Instruction instruction : block.getInstructions()) {
          if (instruction.isMoveException()) {
            hasDedicatedMoveExceptionRegister = true;
            Value exceptionValue = instruction.outValue();
            LiveIntervals intervals = exceptionValue.getLiveIntervals();
            unhandled.remove(intervals);
            if (moveExceptionRegister == NO_REGISTER) {
              moveExceptionRegister = getFreeConsecutiveRegisters(1);
            }
            intervals.setRegister(moveExceptionRegister);
            if (!overlappingMoveExceptionIntervals) {
              for (LiveIntervals other : moveExceptionIntervals) {
                overlappingMoveExceptionIntervals |= other.overlaps(intervals);
              }
            }
            moveExceptionIntervals.add(intervals);
          }
        }
      }
      if (overlappingMoveExceptionIntervals) {
        for (LiveIntervals intervals : moveExceptionIntervals) {
          if (intervals.getUses().size() > 1) {
            LiveIntervalsUse firstUse = intervals.getUses().pollFirst();
            LiveIntervalsUse secondUse = intervals.getUses().pollFirst();
            intervals.getUses().add(firstUse);
            intervals.getUses().add(secondUse);
            LiveIntervals split = intervals.splitBefore(secondUse.getPosition());
            unhandled.add(split);
          }
        }
      }
    }

    // Go through each unhandled live interval and find a register for it.
    while (!unhandled.isEmpty()) {
      LiveIntervals unhandledInterval = unhandled.poll();

      setHintToPromote2AddrInstruction(unhandledInterval);

      // If this interval value is the src of an argument move. Fix the registers for the
      // consecutive arguments now and add hints to the move sources. This looks forward
      // and propagate hints backwards to avoid many moves in connection with ranged invokes.
      allocateArgumentIntervalsWithSrc(unhandledInterval);
      if (unhandledInterval.getRegister() != NO_REGISTER) {
        // The value itself could be in the chain that has now gotten registers allocated.
        continue;
      }

      int start = unhandledInterval.getStart();
      // Check for active intervals that expired or became inactive.
      Iterator<LiveIntervals> activeIterator = active.iterator();
      while (activeIterator.hasNext()) {
        LiveIntervals activeIntervals = activeIterator.next();
        if (start >= activeIntervals.getEnd()) {
          activeIterator.remove();
          freeRegistersForIntervals(activeIntervals);
        } else if (!activeIntervals.overlapsPosition(start)) {
          activeIterator.remove();
          assert activeIntervals.getRegister() != NO_REGISTER;
          inactive.add(activeIntervals);
          freeRegistersForIntervals(activeIntervals);
        }
      }

      // Check for inactive intervals that expired or became reactivated.
      Iterator<LiveIntervals> inactiveIterator = inactive.iterator();
      while (inactiveIterator.hasNext()) {
        LiveIntervals inactiveIntervals = inactiveIterator.next();
        if (start >= inactiveIntervals.getEnd()) {
          inactiveIterator.remove();
        } else if (inactiveIntervals.overlapsPosition(start)) {
          inactiveIterator.remove();
          assert inactiveIntervals.getRegister() != NO_REGISTER;
          active.add(inactiveIntervals);
          takeRegistersForIntervals(inactiveIntervals);
        }
      }

      // Perform the actual allocation.
      if (unhandledInterval.isLinked() && !unhandledInterval.isArgumentInterval()) {
        allocateLinkedIntervals(unhandledInterval);
      } else {
        if (!allocateSingleInterval(unhandledInterval, mode)) {
          return false;
        }
      }
    }
    return true;
  }

  /*
   * This method tries to promote arithmetic binary instruction to use the 2Addr form.
   * To achieve this goal the output interval of the binary instruction is set with an hint
   * that is the left interval or the right interval if possible when intervals do not overlap.
   */
  private void setHintToPromote2AddrInstruction(LiveIntervals unhandledInterval) {
    if (unhandledInterval.getHint() == null &&
        unhandledInterval.getValue().definition instanceof ArithmeticBinop) {
      ArithmeticBinop binOp = unhandledInterval.getValue().definition.asArithmeticBinop();
      Value left = binOp.leftValue();
      assert left != null;
      if (left.getLiveIntervals() != null &&
          !left.getLiveIntervals().overlaps(unhandledInterval)) {
        unhandledInterval.setHint(left.getLiveIntervals());
      } else {
        Value right = binOp.rightValue();
        assert right != null;
        if (binOp.isCommutative() && right.getLiveIntervals() != null &&
            !right.getLiveIntervals().overlaps(unhandledInterval)) {
          unhandledInterval.setHint(right.getLiveIntervals());
        }
      }
    }
  }

  /**
   * Perform look-ahead and allocate registers for linked argument chains that have the argument
   * interval as an argument move source.
   *
   * <p>The end result of calling this method is that the argument intervals have registers
   * allocated and have been moved from unhandled to inactive. The move sources have their hints
   * updated. The rest of the register allocation state is unchanged.
   */
  private void allocateArgumentIntervalsWithSrc(LiveIntervals srcInterval) {
    Value value = srcInterval.getValue();
    for (Instruction instruction : value.uniqueUsers()) {
      // If there is a move user that is an argument move, we allocate the consecutive
      // registers for the argument intervals and propagate the selected registers back as
      // hints to the sources.
      if (instruction.isMove() && instruction.asMove().dest().isLinked()) {
        Move move = instruction.asMove();
        Value dest = move.dest();
        LiveIntervals destIntervals = dest.getLiveIntervals();
        if (destIntervals.getRegister() == NO_REGISTER) {
          // Save the current register allocation state so we can restore it at the end.
          TreeSet<Integer> savedFreeRegisters = new TreeSet<>(freeRegisters);
          int savedUnusedRegisterNumber = nextUnusedRegisterNumber;
          List<LiveIntervals> savedActive = new LinkedList<>(active);
          List<LiveIntervals> savedInactive = new LinkedList<>(inactive);

          // Add all the active intervals to the inactive set. When allocating linked intervals we
          // check all inactive intervals and exclude the registers for overlapping inactive
          // intervals.
          for (LiveIntervals active : active) {
            // TODO(ager): We could allow the use of all the currently active registers for the
            // ranged invoke (by adding the registers for all the active intervals to freeRegisters
            // here). That could lead to lower register pressure. However, it would also often mean
            // that we cannot allocate the right argument register to the current unhandled
            // interval. Size measurements on GMSCore indicate that blocking the current active
            // registers works the best for code size.
            if (active.isArgumentInterval()) {
              // Allow the ranged invoke to use argument registers if free. This improves register
              // allocation for brigde methods that forwards all of their arguments after check-cast
              // checks on their types.
              freeRegistersForIntervals(active);
            }
            inactive.add(active);
          }

          // Allocate the argument intervals.
          unhandled.remove(destIntervals);
          allocateLinkedIntervals(destIntervals);
          // Restore the register allocation state.
          freeRegisters = savedFreeRegisters;
          for (int i = savedUnusedRegisterNumber; i < nextUnusedRegisterNumber; i++) {
            freeRegisters.add(i);
          }
          active = savedActive;
          inactive = savedInactive;
          // Move all the argument intervals to the inactive set.
          LiveIntervals current = destIntervals.getStartOfConsecutive();
          while (current != null) {
            assert !inactive.contains(current);
            assert !active.contains(current);
            assert !unhandled.contains(current);
            inactive.add(current);
            current = current.getNextConsecutive();
          }
        }
      }
    }
  }

  private void allocateLinkedIntervals(LiveIntervals unhandledInterval) {
    // Exclude the registers that overlap the start of one of the live ranges we are
    // going to assign registers to now.
    LiveIntervals current = unhandledInterval.getStartOfConsecutive();
    Set<Integer> excludedRegisters = new HashSet<>();
    while (current != null) {
      for (LiveIntervals inactiveIntervals : inactive) {
        if (inactiveIntervals.overlaps(current)) {
          excludeRegistersForInterval(inactiveIntervals, excludedRegisters);
        }
      }
      current = current.getNextConsecutive();
    }
    // Select registers.
    current = unhandledInterval.getStartOfConsecutive();
    int numberOfRegister = current.numberOfConsecutiveRegisters();
    int firstRegister = getFreeConsecutiveRegisters(numberOfRegister);
    for (int i = 0; i < numberOfRegister; i++) {
      assert current != null;
      current.setRegister(firstRegister + i);
      if (current.getType() == MoveType.WIDE) {
        assert i < numberOfRegister;
        i++;
      }
      // Propagate hints to the move sources.
      Value value = current.getValue();
      if (!value.isPhi() && value.definition.isMove()) {
        Move move = value.definition.asMove();
        LiveIntervals intervals = move.src().getLiveIntervals();
        intervals.setHint(current);
      }
      if (current != unhandledInterval) {
        // Only the start of unhandledInterval has been reached at this point. All other live
        // intervals in the chain have been assigned registers but their start has not yet been
        // reached. Therefore, they belong in the inactive set.
        unhandled.remove(current);
        assert current.getRegister() != NO_REGISTER;
        inactive.add(current);
        freeRegistersForIntervals(current);
      }
      current = current.getNextConsecutive();
    }
    assert current == null;
    assert unhandledInterval.getRegister() != NO_REGISTER;
    active.add(unhandledInterval);
    // Include the registers for inactive ranges that we had to exclude for this allocation.
    freeRegisters.addAll(excludedRegisters);
  }

  // Update the information about used registers when |register| has been selected for use.
  private void updateRegisterState(int register, boolean needsRegisterPair) {
    int maxRegister = register + (needsRegisterPair ? 1 : 0);
    if (maxRegister >= nextUnusedRegisterNumber) {
      nextUnusedRegisterNumber = maxRegister + 1;
    }
    maxRegisterNumber = Math.max(maxRegisterNumber, maxRegister);
  }

  private int getSpillRegister(LiveIntervals intervals) {
    int registerNumber = nextUnusedRegisterNumber++;
    maxRegisterNumber = registerNumber;
    if (intervals.getType() == MoveType.WIDE) {
      nextUnusedRegisterNumber++;
      maxRegisterNumber++;
    }
    return registerNumber;
  }

  private int toInstructionPosition(int position) {
    return position % 2 == 0 ? position : position + 1;
  }

  private int toGapPosition(int position) {
    return position % 2 == 1 ? position : position - 1;
  }

  // The dalvik jit had a bug where the long operations add, sub, or, xor and and would write
  // the first part of the result long before reading the second part of the input longs.
  //
  // Therefore, on dalvik, we cannot generate code with overlapping long registers such as:
  //
  // add-long v3, v0, v2
  //
  // Dalvik would add v0 and v2 and write that to v3. It would then read v1 and v3 and produce
  // the wrong result.
  private boolean needsOverlappingLongRegisterWorkaround(LiveIntervals intervals) {
    if (intervals.requiredRegisters() == 1) {
      // Not the live range for a wide value.
      return false;
    }
    if (intervals.getValue().isPhi()) {
      // If this writes a new register pair it will be via a move and not a long operation.
      return false;
    }
    if (intervals.getSplitParent() != intervals) {
      // This is a split of the parent interval and therefore if this leads to a write of a
      // register pair it will be via a move and not a long operation.
      return false;
    }
    Instruction definition = intervals.getValue().definition;
    if (definition.isArithmeticBinop() &&
        definition.asArithmeticBinop().getNumericType() == NumericType.LONG) {
      return definition instanceof Add || definition instanceof Sub;
    }
    if (definition.isLogicalBinop() &&
        definition.asLogicalBinop().getNumericType() == NumericType.LONG) {
      return definition instanceof Or || definition instanceof Xor || definition instanceof And;
    }
    return false;
  }

  private boolean hasOverlappingLongRegisters(LiveIntervals unhandledInterval, int register) {
    assert needsOverlappingLongRegisterWorkaround(unhandledInterval);
    Value left = unhandledInterval.getValue().definition.asBinop().leftValue();
    Value right = unhandledInterval.getValue().definition.asBinop().rightValue();
    int leftReg =
        left.getLiveIntervals().getSplitCovering(unhandledInterval.getStart()).getRegister();
    int rightReg =
        right.getLiveIntervals().getSplitCovering(unhandledInterval.getStart()).getRegister();
    assert leftReg != NO_REGISTER && rightReg != NO_REGISTER;
    // The dalvik bug is actually only for overlap with the second operand, For now we
    // make sure that there is no overlap with either operand.
    if ((leftReg + 1) == register || (rightReg + 1) == register) {
      return true;
    }
    return false;
  }

  private boolean allocateSingleInterval(LiveIntervals unhandledInterval, ArgumentReuseMode mode) {
    int registerConstraint = unhandledInterval.getRegisterLimit();
    assert registerConstraint <= Constants.U16BIT_MAX;

    assert unhandledInterval.requiredRegisters() <= 2;
    boolean needsRegisterPair = unhandledInterval.requiredRegisters() == 2;

    // Just use the argument register if an argument split has no register constraint. That will
    // avoid move generation for the argument.
    if (registerConstraint == Constants.U16BIT_MAX && unhandledInterval.isArgumentInterval()) {
      int argumentRegister = unhandledInterval.getSplitParent().getRegister();
      assignRegisterToUnhandledInterval(unhandledInterval, needsRegisterPair, argumentRegister);
      return true;
    }

    if (registerConstraint < Constants.U16BIT_MAX) {
      // We always have argument sentinels that will not actually occupy registers. Therefore, we
      // allow the use of NUMBER_OF_SENTINEL_REGISTERS more than the limit.
      registerConstraint += NUMBER_OF_SENTINEL_REGISTERS;
      if (mode == ArgumentReuseMode.DISALLOW_ARGUMENT_REUSE) {
        // We know that none of the argument registers will be reused. Therefore, we allow the
        // use of number of arguments more registers. (We will never use number of arguments +
        // number of sentinel registers of them.)
        registerConstraint += numberOfArgumentRegisters;
      }
    }

    // Set all free positions for possible registers to max integer.
    RegisterPositions freePositions = new RegisterPositions(registerConstraint + 1);

    if (mode == ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
      // The sentinel registers cannot be used and we block them.
      freePositions.set(0, 0);
      if (options.debug && !code.method.accessFlags.isStatic()) {
        // If we are generating debug information, we pin the this value register since the
        // debugger expects to always be able to find it in the input register.
        assert numberOfArgumentRegisters > 0;
        assert preArgumentSentinelValue.getNextConsecutive().requiredRegisters() == 1;
        freePositions.set(1, 0);
      }
      int lastSentinelRegister = numberOfArgumentRegisters + NUMBER_OF_SENTINEL_REGISTERS - 1;
      if (lastSentinelRegister <= registerConstraint) {
        freePositions.set(lastSentinelRegister, 0);
      }
    } else {
      // Argument reuse is not allowed and we block all the argument registers so that
      // arguments are never free.
      for (int i = 0; i < numberOfArgumentRegisters + NUMBER_OF_SENTINEL_REGISTERS; i++) {
        if (i <= registerConstraint) {
          freePositions.set(i, 0);
        }
      }
    }

    // If there is a move exception instruction we block register 0 as the move exception
    // register. If we cannot find a free valid register for the move exception value we have no
    // place to put a spill move (because the move exception instruction has to be the
    // first instruction in the handler block).
    if (hasDedicatedMoveExceptionRegister) {
      int moveExceptionRegister = numberOfArgumentRegisters + NUMBER_OF_SENTINEL_REGISTERS;
      if (moveExceptionRegister <= registerConstraint) {
        freePositions.set(moveExceptionRegister, 0);
      }
    }

    // All the active intervals are not free at this point.
    for (LiveIntervals intervals : active) {
      int activeRegister = intervals.getRegister();
      if (activeRegister <= registerConstraint) {
        for (int i = 0; i < intervals.requiredRegisters(); i++) {
          if (activeRegister + i <= registerConstraint) {
            freePositions.set(activeRegister + i, 0);
          }
        }
      }
    }

    // The register for inactive intervals that overlap with this interval are free until
    // the next overlap.
    for (LiveIntervals intervals : inactive) {
      int inactiveRegister = intervals.getRegister();
      if (inactiveRegister <= registerConstraint && unhandledInterval.overlaps(intervals)) {
        int nextOverlap = unhandledInterval.nextOverlap(intervals);
        for (int i = 0; i < intervals.requiredRegisters(); i++) {
          int register = inactiveRegister + i;
          if (register <= registerConstraint) {
            int unhandledStart = toInstructionPosition(unhandledInterval.getStart());
            if (nextOverlap == unhandledStart) {
              // Don't use the register for an inactive interval that is only free until the next
              // instruction. We can get into this situation when unhandledInterval starts at a
              // gap position.
              freePositions.set(register, 0);
            } else {
              if (nextOverlap < freePositions.get(register)) {
                freePositions.set(register, nextOverlap, intervals);
              }
            }
          }
        }
      }
    }

    // Attempt to use register hints.
    if (useRegisterHint(unhandledInterval, registerConstraint, freePositions, needsRegisterPair)) {
      return true;
    }

    // Get the register (pair) that is free the longest. That is the register with the largest
    // free position.
    int candidate = getLargestValidCandidate(
        unhandledInterval, registerConstraint, needsRegisterPair, freePositions, Type.ANY);
    assert candidate != REGISTER_CANDIDATE_NOT_FOUND;
    int largestFreePosition = freePositions.get(candidate);
    if (needsRegisterPair) {
      largestFreePosition = Math.min(largestFreePosition, freePositions.get(candidate + 1));
    }

    // Determine what to do based on how long the selected candidate is free.
    if (largestFreePosition == 0) {
      // Not free. We need to spill.
      if (mode == ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
        // No spilling is allowed when we allow argument reuse. Bailout and start over with
        // argument reuse disallowed.
        return false;
      }
      // If the first use for these intervals is unconstrained, just spill this interval instead
      // of finding another candidate to spill via allocateBlockedRegister.
      if (!unhandledInterval.getUses().first().hasConstraint()) {
        int nextConstrainedPosition = unhandledInterval.firstUseWithConstraint().getPosition();
        int register;
        // Arguments are always in the argument registers, so for arguments just use that register
        // for the unconstrained prefix. For everything else, get a spill register.
        if (unhandledInterval.isArgumentInterval()) {
          register = unhandledInterval.getSplitParent().getRegister();
        } else {
          register = getSpillRegister(unhandledInterval);
        }
        LiveIntervals split = unhandledInterval.splitBefore(nextConstrainedPosition);
        assignRegisterToUnhandledInterval(unhandledInterval, needsRegisterPair, register);
        unhandled.add(split);
      } else {
        allocateBlockedRegister(unhandledInterval);
      }
    } else if (largestFreePosition >= unhandledInterval.getEnd()) {
      // Free for the entire interval. Allocate the register.
      assignRegisterToUnhandledInterval(unhandledInterval, needsRegisterPair, candidate);
    } else {
      if (mode == ArgumentReuseMode.ALLOW_ARGUMENT_REUSE) {
        // No splitting is allowed when we allow argument reuse. Bailout and start over with
        // argument reuse disallowed.
        return false;
      }
      // The candidate is free for the beginning of an interval. We split the interval
      // and use the register for as long as we can.
      LiveIntervals split = unhandledInterval.splitBefore(largestFreePosition);
      assert split != unhandledInterval;
      assignRegisterToUnhandledInterval(unhandledInterval, needsRegisterPair, candidate);
      unhandled.add(split);
    }
    return true;
  }

  // Attempt to use the register hint for the unhandled interval in order to avoid generating
  // moves.
  private boolean useRegisterHint(LiveIntervals unhandledInterval, int registerConstraint,
      RegisterPositions freePositions, boolean needsRegisterPair) {
    // If the unhandled interval has a hint we give it that register if it is available without
    // spilling. For phis we also use the hint before looking at the operand registers. The
    // phi could have a hint from an argument moves which it seems more important to honor in
    // practice.
    LiveIntervals hint = unhandledInterval.getHint();
    if (hint != null) {
      int register = hint.getRegister();
      if (tryHint(unhandledInterval, registerConstraint, freePositions, needsRegisterPair,
          register)) {
        return true;
      }
    }

    // If there is no hint or it cannot be applied we search for a good register for phis using
    // the registers assigned to the operand intervals. We determine all the registers used
    // for operands and try them one by one based on frequency.
    Value value = unhandledInterval.getValue();
    if (value.isPhi()) {
      Phi phi = value.asPhi();
      Multiset<Integer> map = HashMultiset.create();
      List<Value> operands = phi.getOperands();
      for (int i = 0; i < operands.size(); i++) {
        LiveIntervals intervals = operands.get(i).getLiveIntervals();
        if (intervals.hasSplits()) {
          BasicBlock pred = phi.getBlock().getPredecessors().get(i);
          intervals = intervals.getSplitCovering(pred.exit().getNumber());
        }
        int operandRegister = intervals.getRegister();
        if (operandRegister != NO_REGISTER) {
          map.add(operandRegister);
        }
      }
      for (Multiset.Entry<Integer> entry : Multisets.copyHighestCountFirst(map).entrySet()) {
        int register = entry.getElement();
        if (tryHint(unhandledInterval, registerConstraint, freePositions, needsRegisterPair,
            register)) {
          return true;
        }
      }
    }

    return false;
  }

  // Attempt to allocate the hint register to the unhandled intervals.
  private boolean tryHint(LiveIntervals unhandledInterval, int registerConstraint,
      RegisterPositions freePositions, boolean needsRegisterPair, int register) {
    // At some point after the hint has been added, the register allocator can
    // decide to redo allocation for the hint interval. In that case, the hint will be
    // reset to NO_REGISTER and provides no hinting info.
    if (register == NO_REGISTER) {
      return false;
    }
    if (register + (needsRegisterPair ? 1 : 0) <= registerConstraint) {
      int freePosition = freePositions.get(register);
      if (needsRegisterPair) {
        freePosition = Math.min(freePosition, freePositions.get(register + 1));
      }
      if (freePosition >= unhandledInterval.getEnd()) {
        // Check for overlapping long registers issue.
        if (needsOverlappingLongRegisterWorkaround(unhandledInterval) &&
            hasOverlappingLongRegisters(unhandledInterval, register)) {
          return false;
        }
        assignRegisterToUnhandledInterval(unhandledInterval, needsRegisterPair, register);
        return true;
      }
    }
    return false;
  }

  private void assignRegister(LiveIntervals intervals, int register) {
    intervals.setRegister(register);
    updateRegisterHints(intervals);
  }

  private void updateRegisterHints(LiveIntervals intervals) {
    Value value = intervals.getValue();
    // If the value flows into a phi, set the hint for all the operand splits that flow into the
    // phi and do not have hints yet.
    for (Phi phi : value.uniquePhiUsers()) {
      LiveIntervals phiIntervals = phi.getLiveIntervals();
      if (phiIntervals.getHint() == null) {
        for (int i = 0; i < phi.getOperands().size(); i++) {
          Value operand = phi.getOperand(i);
          LiveIntervals operandIntervals = operand.getLiveIntervals();
          BasicBlock pred = phi.getBlock().getPredecessors().get(i);
          operandIntervals = operandIntervals.getSplitCovering(pred.exit().getNumber());
          if (operandIntervals.getHint() == null) {
            operandIntervals.setHint(intervals);
          }
        }
      }
    }
    // If the value is a phi and we are at the start of the interval, we set the register as
    // the hint for all of the operand splits flowing into the phi. We set the hint no matter
    // if there is already a hint. We know the register for the phi and want as many operands
    // as possible to be allocated the same register to avoid phi moves.
    if (value.isPhi() && intervals.getSplitParent() == intervals) {
      Phi phi = value.asPhi();
      BasicBlock block = phi.getBlock();
      for (int i = 0; i < phi.getOperands().size(); i++) {
        Value operand = phi.getOperand(i);
        BasicBlock pred = block.getPredecessors().get(i);
        LiveIntervals operandIntervals =
            operand.getLiveIntervals().getSplitCovering(pred.exit().getNumber());
        operandIntervals.setHint(intervals);
      }
    }
  }

  private void assignRegisterToUnhandledInterval(
      LiveIntervals unhandledInterval, boolean needsRegisterPair, int register) {
    assignRegister(unhandledInterval, register);
    takeRegistersForIntervals(unhandledInterval);
    updateRegisterState(register, needsRegisterPair);
    active.add(unhandledInterval);
  }

  private int getLargestCandidate(int registerConstraint, RegisterPositions freePositions,
      boolean needsRegisterPair, RegisterPositions.Type type) {
    int candidate = REGISTER_CANDIDATE_NOT_FOUND;
    int largest = -1;

    for (int i = 0; i <= registerConstraint; i++) {
      if (!freePositions.hasType(i, type)) {
        continue;
      }
      int freePosition = freePositions.get(i);
      if (needsRegisterPair) {
        if (i >= registerConstraint) {
          break;
        }
        freePosition = Math.min(freePosition, freePositions.get(i + 1));
      }
      if (freePosition > largest) {
        candidate = i;
        largest = freePosition;
        if (largest == Integer.MAX_VALUE) {
          break;
        }
      }
    }
    return candidate;
  }

  private int getLargestValidCandidate(LiveIntervals unhandledInterval, int registerConstraint,
      boolean needsRegisterPair, RegisterPositions freePositions, RegisterPositions.Type type) {
    int candidate = getLargestCandidate(registerConstraint, freePositions, needsRegisterPair, type);
    if (candidate == REGISTER_CANDIDATE_NOT_FOUND) {
      return candidate;
    }
    if (needsOverlappingLongRegisterWorkaround(unhandledInterval)) {
      int lastCandidate = candidate;
      while (hasOverlappingLongRegisters(unhandledInterval, candidate)) {
        // Make the overlapping register unavailable for allocation and try again.
        freePositions.set(candidate, 0);
        candidate = getLargestCandidate(registerConstraint, freePositions, needsRegisterPair, type);
        // If there are only invalid candidates of the give type we will end up with the same
        // candidate returned again once we have tried them all. In that case we didn't find a
        // valid register candidate and we need to broaden the search to other types.
        if (lastCandidate == candidate) {
          return REGISTER_CANDIDATE_NOT_FOUND;
        }
        lastCandidate = candidate;
      }
    }
    return candidate;
  }

  private void allocateBlockedRegister(LiveIntervals unhandledInterval) {
    int registerConstraint = unhandledInterval.getRegisterLimit();
    if (registerConstraint < Constants.U16BIT_MAX) {
      // We always have argument sentinels that will not actually occupy registers. Therefore, we
      // allow the use of NUMBER_OF_SENTINEL_REGISTERS more than the limit. Additionally, we never
      // reuse argument registers and therefore allow the use of numberOfArgumentRegisters as well.
      registerConstraint += numberOfArgumentRegisters;
      registerConstraint += NUMBER_OF_SENTINEL_REGISTERS;
    }

    // Initialize all candidate registers to Integer.MAX_VALUE.
    RegisterPositions usePositions = new RegisterPositions(registerConstraint + 1);
    RegisterPositions blockedPositions = new RegisterPositions(registerConstraint + 1);

    // Compute next use location for all currently active registers.
    for (LiveIntervals intervals : active) {
      int activeRegister = intervals.getRegister();
      if (activeRegister <= registerConstraint) {
        for (int i = 0; i < intervals.requiredRegisters(); i++) {
          if (activeRegister + i <= registerConstraint) {
            int unhandledStart = unhandledInterval.getStart();
            usePositions.set(
                activeRegister + i, intervals.firstUseAfter(unhandledStart), intervals);
          }
        }
      }
    }

    // Compute next use location for all inactive registers that overlaps the unhandled interval.
    for (LiveIntervals intervals : inactive) {
      int inactiveRegister = intervals.getRegister();
      if (inactiveRegister <= registerConstraint && intervals.overlaps(unhandledInterval)) {
        for (int i = 0; i < intervals.requiredRegisters(); i++) {
          if (inactiveRegister + i <= registerConstraint) {
            int firstUse = intervals.firstUseAfter(unhandledInterval.getStart());
            if (firstUse < usePositions.get(inactiveRegister + i)) {
              usePositions.set(inactiveRegister + i, firstUse, intervals);
            }
          }
        }
      }
    }

    // Disallow the reuse of argument registers by always treating them as being used
    // at instruction number 0.
    for (int i = 0; i < numberOfArgumentRegisters + NUMBER_OF_SENTINEL_REGISTERS; i++) {
      usePositions.set(i, 0);
    }

    // Disallow reuse of the move exception register if we have reserved one.
    if (hasDedicatedMoveExceptionRegister) {
      usePositions.set(numberOfArgumentRegisters + NUMBER_OF_SENTINEL_REGISTERS, 0);
    }

    // Treat active linked argument intervals as pinned. They cannot be given another register
    // at their uses.
    blockLinkedRegisters(active, unhandledInterval, registerConstraint, usePositions,
        blockedPositions);

    // Treat inactive linked argument intervals as pinned. They cannot be given another register
    // at their uses.
    blockLinkedRegisters(inactive, unhandledInterval, registerConstraint, usePositions,
        blockedPositions);

    // Get the register (pair) that has the highest use position.
    boolean needsRegisterPair = unhandledInterval.requiredRegisters() == 2;

    // First look for a candidate that can be rematerialized.
    int candidate = getLargestValidCandidate(unhandledInterval, registerConstraint,
        needsRegisterPair, usePositions, Type.CONST_NUMBER);
    if (candidate != Integer.MAX_VALUE) {
      // Look for a non-const, non-monitor candidate.
      int otherCandidate = getLargestValidCandidate(
          unhandledInterval, registerConstraint, needsRegisterPair, usePositions, Type.OTHER);
      if (otherCandidate == Integer.MAX_VALUE || candidate == REGISTER_CANDIDATE_NOT_FOUND) {
        candidate = otherCandidate;
      } else {
        int largestConstUsePosition =
            getLargestPosition(usePositions, candidate, needsRegisterPair);
        if (largestConstUsePosition - MIN_CONSTANT_FREE_FOR_POSITIONS <
            unhandledInterval.getStart()) {
          // The candidate that can be rematerialized has a live range too short to use it.
          candidate = otherCandidate;
        }
      }
      // If looking at constants and non-monitor registers did not find a valid spill candidate
      // we allow ourselves to look at monitor spill candidates as well. Registers holding objects
      // used as monitors should not be spilled if we can avoid it. Spilling them can lead
      // to Art lock verification issues.
      if (candidate == REGISTER_CANDIDATE_NOT_FOUND) {
        candidate = getLargestValidCandidate(
            unhandledInterval, registerConstraint, needsRegisterPair, usePositions, Type.MONITOR);
      }
    }

    int largestUsePosition = getLargestPosition(usePositions, candidate, needsRegisterPair);
    int blockedPosition = getBlockedPosition(blockedPositions, candidate, needsRegisterPair);

    if (largestUsePosition < unhandledInterval.getFirstUse()) {
      // All active and inactive intervals are used before current. Therefore, it is best to spill
      // current itself.
      int splitPosition = unhandledInterval.getFirstUse();
      LiveIntervals split = unhandledInterval.splitBefore(splitPosition);
      assert split != unhandledInterval;
      int registerNumber = getSpillRegister(unhandledInterval);
      assignRegisterToUnhandledInterval(unhandledInterval, needsRegisterPair, registerNumber);
      unhandledInterval.setSpilled(true);
      unhandled.add(split);
    } else if (blockedPosition > unhandledInterval.getEnd()) {
      // Spilling can make a register available for the entire interval.
      assignRegisterAndSpill(unhandledInterval, needsRegisterPair, candidate);
    } else {
      // Spilling only makes a register available for the first part of current.
      LiveIntervals splitChild = unhandledInterval.splitBefore(blockedPosition);
      unhandled.add(splitChild);
      assignRegisterAndSpill(unhandledInterval, needsRegisterPair, candidate);
    }
  }

  private int getLargestPosition(RegisterPositions usePositions, int register,
      boolean needsRegisterPair) {
    int largestUsePosition = usePositions.get(register);

    if (needsRegisterPair) {
      largestUsePosition = Math.min(largestUsePosition, usePositions.get(register + 1));
    }

    return largestUsePosition;
  }

  private int getBlockedPosition(RegisterPositions blockedPositions, int register,
      boolean needsRegisterPair) {
    int blockedPosition = blockedPositions.get(register);

    if (needsRegisterPair) {
      blockedPosition = Math.min(blockedPosition, blockedPositions.get(register + 1));
    }

    return blockedPosition;
  }

  private void assignRegisterAndSpill(
      LiveIntervals unhandledInterval,
      boolean needsRegisterPair,
      int candidate) {
    assignRegister(unhandledInterval, candidate);
    updateRegisterState(candidate, needsRegisterPair);
    // Split and spill intersecting active intervals for this register.
    spillOverlappingActiveIntervals(unhandledInterval, needsRegisterPair, candidate);
    takeRegistersForIntervals(unhandledInterval);
    active.add(unhandledInterval);
    // Split all overlapping inactive intervals for this register. They need to have a new
    // register assigned at the next use.
    splitOverlappingInactiveIntervals(unhandledInterval, needsRegisterPair, candidate);
  }

  protected void splitOverlappingInactiveIntervals(
      LiveIntervals unhandledInterval,
      boolean needsRegisterPair,
      int candidate) {
    List<LiveIntervals> newInactive = new ArrayList<>();
    Iterator<LiveIntervals> inactiveIterator = inactive.iterator();
    while (inactiveIterator.hasNext()) {
      LiveIntervals intervals = inactiveIterator.next();
      if ((intervals.usesRegister(candidate) ||
          (needsRegisterPair && intervals.usesRegister(candidate + 1))) &&
          intervals.overlaps(unhandledInterval)) {
        if (intervals.isLinked() && !intervals.isArgumentInterval()) {
          // If the inactive register is linked but not an argument, it needs to get the
          // same register again at the next use after the start of the unhandled interval.
          // If there are no such uses, we can use a different register for the remainder
          // of the inactive interval and therefore do not have to split here.
          int nextUsePosition = intervals.firstUseAfter(unhandledInterval.getStart());
          if (nextUsePosition != Integer.MAX_VALUE) {
            LiveIntervals split = intervals.splitBefore(nextUsePosition);
            split.setRegister(intervals.getRegister());
            newInactive.add(split);
          }
        }
        if (intervals.getStart() > unhandledInterval.getStart()) {
          // The inactive live intervals hasn't started yet. Clear the temporary register
          // assignment and move back to unhandled for register reassignment.
          intervals.clearRegisterAssignment();
          inactiveIterator.remove();
          unhandled.add(intervals);
        } else {
          // The inactive live intervals is in a live range hole. Split the interval and
          // put the ranges after the hole into the unhandled set for register reassignment.
          LiveIntervals split = intervals.splitBefore(unhandledInterval.getStart());
          unhandled.add(split);
        }
      }
    }
    inactive.addAll(newInactive);
  }

  private void spillOverlappingActiveIntervals(
      LiveIntervals unhandledInterval,
      boolean needsRegisterPair,
      int candidate) {
    List<LiveIntervals> newActive = new ArrayList<>();
    Iterator<LiveIntervals> activeIterator = active.iterator();
    while (activeIterator.hasNext()) {
      LiveIntervals intervals = activeIterator.next();
      if (intervals.usesRegister(candidate) ||
          (needsRegisterPair && intervals.usesRegister(candidate + 1))) {
        activeIterator.remove();
        freeRegistersForIntervals(intervals);
        LiveIntervals splitChild = intervals.splitBefore(unhandledInterval.getStart());
        int registerNumber = getSpillRegister(intervals);
        assignRegister(splitChild, registerNumber);
        splitChild.setSpilled(true);
        takeRegistersForIntervals(splitChild);
        assert splitChild.getRegister() != NO_REGISTER;
        assert intervals.getRegister() != NO_REGISTER;
        newActive.add(splitChild);
        // If the constant is split before its first actual use, mark the constant as being
        // spilled. That will allows us to remove it afterwards if it is rematerializable.
        if (intervals.getValue().isConstNumber()
            && intervals.getStart() == intervals.getValue().definition.getNumber()
            && intervals.getUses().size() == 1) {
          intervals.setSpilled(true);
        }
        if (splitChild.getUses().size() > 0) {
          if (splitChild.isLinked() && !splitChild.isArgumentInterval()) {
            // Spilling a value with a pinned register. We need to move back at the next use.
            LiveIntervals splitOfSplit = splitChild.splitBefore(splitChild.getFirstUse());
            splitOfSplit.setRegister(intervals.getRegister());
            inactive.add(splitOfSplit);
          } else if (intervals.getValue().isConstNumber()) {
            // TODO(ager): Do this for all constants. Currently we only rematerialize const
            // number and therefore we only do it for numbers at this point.
            splitRangesForSpilledConstant(splitChild, registerNumber);
          } else if (intervals.isArgumentInterval()) {
            splitRangesForSpilledArgument(splitChild);
          } else {
            splitRangesForSpilledInterval(splitChild, registerNumber);
          }
        }
      }
    }
    active.addAll(newActive);
  }

  private void splitRangesForSpilledArgument(LiveIntervals spilled) {
    assert spilled.isSpilled();
    assert spilled.isArgumentInterval();
    // Argument intervals are spilled to the original argument register. We don't know what
    // that is yet, and therefore we split before the next use to make sure we get a usable
    // register at the next use.
    if (!spilled.getUses().isEmpty()) {
      LiveIntervals split = spilled.splitBefore(spilled.getUses().first().getPosition());
      unhandled.add(split);
    }
  }

  private void splitRangesForSpilledInterval(LiveIntervals spilled, int registerNumber) {
    // Spilling a non-pinned, non-rematerializable value. We use the value in the spill
    // register for as long as possible to avoid further moves.
    assert spilled.isSpilled();
    assert !spilled.getValue().isConstNumber();
    assert !spilled.isLinked() || spilled.isArgumentInterval();
    if (spilled.isArgumentInterval()) {
      registerNumber = Constants.U16BIT_MAX;
    }
    LiveIntervalsUse firstUseWithLowerLimit = null;
    boolean hasUsesBeforeFirstUseWithLowerLimit = false;
    for (LiveIntervalsUse use : spilled.getUses()) {
      if (registerNumber > use.getLimit()) {
        firstUseWithLowerLimit = use;
        break;
      } else {
        hasUsesBeforeFirstUseWithLowerLimit = true;
      }
    }
    if (hasUsesBeforeFirstUseWithLowerLimit) {
      spilled.setSpilled(false);
    }
    if (firstUseWithLowerLimit != null) {
      LiveIntervals splitOfSplit = spilled.splitBefore(firstUseWithLowerLimit.getPosition());
      unhandled.add(splitOfSplit);
    }
  }

  private void splitRangesForSpilledConstant(LiveIntervals spilled, int spillRegister) {
    // When spilling a constant we should not keep it alive in the spill register, instead
    // we should use rematerialization. We aggressively spill the constant in all gaps
    // between uses that span more than a certain number of instructions. If we needed to
    // spill we are running low on registers and this constant should get out of the way
    // as much as possible.
    assert spilled.isSpilled();
    assert spilled.getValue().isConstNumber();
    assert !spilled.isLinked() || spilled.isArgumentInterval();
    // Do not split range if constant is reused by one of the eleven following instruction.
    int maxGapSize = 11 * INSTRUCTION_NUMBER_DELTA;
    if (!spilled.getUses().isEmpty()) {
      // Split at first use after the spill position and add to unhandled to get a register
      // assigned for rematerialization.
      LiveIntervals split = spilled.splitBefore(spilled.getFirstUse());
      unhandled.add(split);
      // Now repeatedly split for each use that is more than maxGapSize away from the previous use.
      boolean changed = true;
      while (changed) {
        changed = false;
        int previousUse = split.getStart();
        for (LiveIntervalsUse use : split.getUses()) {
          if (use.getPosition() - previousUse > maxGapSize) {
            // Found a use that is more than gap size away from the previous use. Split after
            // the previous use.
            split = split.splitBefore(previousUse + INSTRUCTION_NUMBER_DELTA);
            // If the next use is not at the start of the new split, we split again at the next use
            // and spill the gap.
            if (toGapPosition(use.getPosition()) > split.getStart()) {
              assignRegister(split, spillRegister);
              split.setSpilled(true);
              inactive.add(split);
              split = split.splitBefore(use.getPosition());
            }
            // |split| now starts at the next use - add it to unhandled to get a register
            // assigned for rematerialization.
            unhandled.add(split);
            // Break out of the loop to start iterating the new split uses.
            changed = true;
            break;
          }
          previousUse = use.getPosition();
        }
      }
    }
  }

  private void blockLinkedRegisters(
      List<LiveIntervals> intervalsList, LiveIntervals interval, int registerConstraint,
      RegisterPositions usePositions, RegisterPositions blockedPositions) {
    for (LiveIntervals other : intervalsList) {
      if (other.isLinked()) {
        int register = other.getRegister();
        if (register <= registerConstraint && other.overlaps(interval)) {
          for (int i = 0; i < other.requiredRegisters(); i++) {
            if (register + i <= registerConstraint) {
              int firstUse = other.firstUseAfter(interval.getStart());
              if (firstUse < blockedPositions.get(register + i)) {
                blockedPositions.set(register + i, firstUse, other);
                // If we start blocking registers other than linked arguments, we might need to
                // explicitly update the use positions as well as blocked positions.
                assert usePositions.get(register + i) <= blockedPositions.get(register + i);
              }
            }
          }
        }
      }
    }
  }

  private void insertMoves() {
    SpillMoveSet spillMoves =
        new SpillMoveSet(this, code, numberOfArgumentRegisters + NUMBER_OF_SENTINEL_REGISTERS);
    for (LiveIntervals intervals : liveIntervals) {
      if (intervals.hasSplits()) {
        LiveIntervals current = intervals;
        PriorityQueue<LiveIntervals> sortedChildren = new PriorityQueue<>();
        sortedChildren.addAll(current.getSplitChildren());
        for (LiveIntervals split = sortedChildren.poll();
            split != null;
            split = sortedChildren.poll()) {
          int position = split.getStart();
          spillMoves.addSpillOrRestoreMove(toGapPosition(position), split, current);
          current = split;
        }
      }
    }

    resolveControlFlow(spillMoves);
    firstParallelMoveTemporary = maxRegisterNumber + 1;
    maxRegisterNumber += spillMoves.scheduleAndInsertMoves(maxRegisterNumber + 1);
  }

  // Resolve control flow by inserting phi moves and by inserting moves when the live intervals
  // change for a value across block boundaries.
  private void resolveControlFlow(SpillMoveSet spillMoves) {
    // For a control-flow graph like the following where a value v is split at an instruction in
    // block C a spill move is inserted in block C to transfer the value from register r0 to
    // register r1. However, that move is not executed when taking the control-flow edge from
    // B to D and therefore resolution will insert a move from r0 to r1 on that edge.
    //
    //             r0            r1
    //   v: |----------------|--------|
    //
    //       A ----> B ----> C ----> D
    //               |               ^
    //               +---------------+
    for (BasicBlock block : code.blocks) {
      for (BasicBlock successor : block.getSuccessors()) {
        // If we are processing an exception edge, we need to use the throwing instruction
        // as the instruction we are coming from.
        int fromInstruction = block.exit().getNumber();
        boolean isCatch = block.hasCatchSuccessor(successor);
        if (isCatch) {
          for (Instruction instruction : block.getInstructions()) {
            if (instruction.instructionTypeCanThrow()) {
              fromInstruction = instruction.getNumber();
              break;
            }
          }
        }
        int toInstruction = successor.entry().getNumber();

        // Insert spill/restore moves when a value changes across a block boundary.
        Set<Value> liveAtEntry = liveAtEntrySets.get(successor);
        for (Value value : liveAtEntry) {
          LiveIntervals parentInterval = value.getLiveIntervals();
          LiveIntervals fromIntervals = parentInterval.getSplitCovering(fromInstruction);
          LiveIntervals toIntervals = parentInterval.getSplitCovering(toInstruction);
          if (fromIntervals != toIntervals) {
            if (block.exit().isGoto() && !isCatch) {
              spillMoves.addOutResolutionMove(fromInstruction - 1, toIntervals, fromIntervals);
            } else if (successor.entry().isMoveException()) {
              spillMoves.addInResolutionMove(toInstruction + 1, toIntervals, fromIntervals);
            } else {
              spillMoves.addInResolutionMove(toInstruction - 1, toIntervals, fromIntervals);
            }
          }
        }

        // Insert phi moves.
        int predIndex = successor.getPredecessors().indexOf(block);
        for (Phi phi : successor.getPhis()) {
          LiveIntervals toIntervals = phi.getLiveIntervals().getSplitCovering(toInstruction);
          Value operand = phi.getOperand(predIndex);
          LiveIntervals fromIntervals =
              operand.getLiveIntervals().getSplitCovering(fromInstruction);
          if (fromIntervals != toIntervals && !toIntervals.isArgumentInterval()) {
            assert block.getSuccessors().size() == 1;
            spillMoves.addPhiMove(fromInstruction - 1, toIntervals, fromIntervals);
          }
        }
      }
    }
  }

  /**
   * Compute the set of live values at the entry to each block using a backwards data-flow
   * analysis.
   */
  private void computeLiveAtEntrySets() {
    Queue<BasicBlock> worklist = new LinkedList<>();
    // Since this is a backwards data-flow analysis we process the blocks in reverse
    // topological order to reduce the number of iterations.
    BasicBlock[] sorted = code.topologicallySortedBlocks();
    for (int i = sorted.length - 1; i >= 0; i--) {
      worklist.add(sorted[i]);
    }
    while (!worklist.isEmpty()) {
      BasicBlock block = worklist.poll();
      Set<Value> live = new HashSet<>();
      for (BasicBlock succ : block.getSuccessors()) {
        Set<Value> succLiveAtEntry = liveAtEntrySets.get(succ);
        if (succLiveAtEntry != null) {
          live.addAll(succLiveAtEntry);
        }
        int predIndex = succ.getPredecessors().indexOf(block);
        for (Phi phi : succ.getPhis()) {
          live.add(phi.getOperand(predIndex));
          assert phi.getDebugValues().stream().allMatch(Value::needsRegister);
          live.addAll(phi.getDebugValues());
        }
      }
      ListIterator<Instruction> iterator =
          block.getInstructions().listIterator(block.getInstructions().size());
      while (iterator.hasPrevious()) {
        Instruction instruction = iterator.previous();
        if (instruction.outValue() != null) {
          live.remove(instruction.outValue());
        }
        for (Value use : instruction.inValues()) {
          if (use.needsRegister()) {
            live.add(use);
          }
        }
        assert instruction.getDebugValues().stream().allMatch(Value::needsRegister);
        live.addAll(instruction.getDebugValues());
      }
      for (Phi phi : block.getPhis()) {
        live.remove(phi);
      }
      Set<Value> previousLiveAtEntry = liveAtEntrySets.put(block, live);
      // If the live at entry set changed for this block at the predecessors to the worklist if
      // they are not already there.
      if (previousLiveAtEntry == null || !previousLiveAtEntry.equals(live)) {
        for (BasicBlock pred : block.getPredecessors()) {
          if (!worklist.contains(pred)) {
            worklist.add(pred);
          }
        }
      }
    }
    assert liveAtEntrySets.get(sorted[0]).size() == 0;
  }

  private void addLiveRange(Value v, BasicBlock b, int end) {
    int firstInstructionInBlock = b.entry().getNumber();
    int instructionsSize = b.getInstructions().size() * INSTRUCTION_NUMBER_DELTA;
    int lastInstructionInBlock =
        firstInstructionInBlock + instructionsSize - INSTRUCTION_NUMBER_DELTA;
    int instructionNumber;
    if (v.isPhi()) {
      instructionNumber = firstInstructionInBlock;
    } else {
      Instruction instruction = v.definition;
      instructionNumber = instruction.getNumber();
    }
    if (v.getLiveIntervals() == null) {
      Value current = v.getStartOfConsecutive();
      LiveIntervals intervals = new LiveIntervals(current);
      while (true) {
        liveIntervals.add(intervals);
        Value next = current.getNextConsecutive();
        if (next == null) {
          break;
        }
        LiveIntervals nextIntervals = new LiveIntervals(next);
        intervals.link(nextIntervals);
        current = next;
        intervals = nextIntervals;
      }
    }
    LiveIntervals intervals = v.getLiveIntervals();
    if (firstInstructionInBlock <= instructionNumber &&
        instructionNumber <= lastInstructionInBlock) {
      if (v.isPhi()) {
        // Phis need to interfere with spill restore moves inserted before the instruction because
        // the phi value is defined on the inflowing edge.
        instructionNumber--;
      }
      intervals.addRange(new LiveRange(instructionNumber, end));
      if (!v.isPhi()) {
        int constraint = v.definition.maxOutValueRegister();
        intervals.addUse(new LiveIntervalsUse(instructionNumber, constraint));
      }
    } else {
      intervals.addRange(new LiveRange(firstInstructionInBlock - 1, end));
    }
  }

  /**
   * Compute live ranges based on liveAtEntry sets for all basic blocks.
   */
  private void computeLiveRanges() {
    for (BasicBlock block : code.topologicallySortedBlocks()) {
      Set<Value> live = new HashSet<>();
      List<BasicBlock> successors = block.getSuccessors();
      Set<Value> phiOperands = new HashSet<>();
      for (BasicBlock successor : successors) {
        live.addAll(liveAtEntrySets.get(successor));
        for (Phi phi : successor.getPhis()) {
          live.remove(phi);
          phiOperands.add(phi.getOperand(successor.getPredecessors().indexOf(block)));
          assert phi.getDebugValues().stream().allMatch(Value::needsRegister);
          phiOperands.addAll(phi.getDebugValues());
        }
      }
      live.addAll(phiOperands);
      List<Instruction> instructions = block.getInstructions();
      for (Value value : live) {
        int end = block.entry().getNumber() + instructions.size() * INSTRUCTION_NUMBER_DELTA;
        // Make sure that phi operands do not overlap the phi live range. The phi operand is
        // not live until the next instruction, but only until the gap before the next instruction
        // where the phi value takes over.
        if (phiOperands.contains(value)) {
          end--;
        }
        addLiveRange(value, block, end);
      }
      ListIterator<Instruction> iterator =
          block.getInstructions().listIterator(block.getInstructions().size());
      while (iterator.hasPrevious()) {
        Instruction instruction = iterator.previous();
        Value definition = instruction.outValue();
        if (definition != null) {
          // For instructions that define values which have no use create a live range covering
          // the instruction. This will typically be instructions that can have side effects even
          // if their output is not used.
          if (!definition.isUsed()) {
            addLiveRange(definition, block, instruction.getNumber() + INSTRUCTION_NUMBER_DELTA);
          }
          live.remove(definition);
        }
        for (Value use : instruction.inValues()) {
          if (!live.contains(use) && use.needsRegister()) {
            live.add(use);
            addLiveRange(use, block, instruction.getNumber());
          }
          if (use.needsRegister()) {
            LiveIntervals useIntervals = use.getLiveIntervals();
            useIntervals.addUse(
                new LiveIntervalsUse(instruction.getNumber(), instruction.maxInValueRegister()));
          }
        }
        if (options.debug) {
          int number = instruction.getNumber();
          for (Value use : instruction.getDebugValues()) {
            assert use.needsRegister();
            if (!live.contains(use)) {
              live.add(use);
              addLiveRange(use, block, number);
            }
          }
        }
      }
    }
  }

  private void clearUserInfo() {
    code.blocks.forEach(BasicBlock::clearUserInfo);
  }

  private Value createValue(ValueType type) {
    Value value = code.createValue(type, null);
    value.setNeedsRegister(true);
    return value;
  }

  private void replaceArgument(Invoke invoke, int index, Value newArgument) {
    Value argument = invoke.arguments().get(index);
    invoke.arguments().set(index, newArgument);
    argument.removeUser(invoke);
    newArgument.addUser(invoke);
  }

  private void generateArgumentMoves(Invoke invoke, InstructionListIterator insertAt) {
    // If the invoke instruction require more than 5 registers we link the inputs because they
    // need to be in consecutive registers.
    if (invoke.requiredArgumentRegisters() > 5 && !argumentsAreAlreadyLinked(invoke)) {
      List<Value> arguments = invoke.arguments();
      Value previous = null;
      for (int i = 0; i < arguments.size(); i++) {
        Value argument = arguments.get(i);
        Value newArgument = argument;
        // In debug mode, we have debug instructions that are also moves. Do not generate another
        // move if there already is a move instruction that we can use. We generate moves if:
        //
        // 1. the argument is not defined by a move,
        //
        // 2. the argument is already linked or would cause a cycle if linked, or
        //
        // 3. the argument has a register constraint (the argument moves are there to make the
        //    input value to a ranged invoke unconstrained.)
        if (argument.definition == null ||
            !argument.definition.isMove() ||
            argument.isLinked() ||
            argument == previous ||
            argument.hasRegisterConstraint()) {
          newArgument = createValue(argument.outType());
          Move move = new Move(newArgument, argument);
          move.setBlock(invoke.getBlock());
          move.setPosition(invoke.getPosition());
          replaceArgument(invoke, i, newArgument);
          insertAt.add(move);
        }
        if (previous != null) {
          previous.linkTo(newArgument);
        }
        previous = newArgument;
      }
    }
  }

  private boolean argumentsAreAlreadyLinked(Invoke invoke) {
    Iterator<Value> it = invoke.arguments().iterator();
    Value current = it.next();
    while (it.hasNext()) {
      Value next = it.next();
      if (!current.isLinked() || current.getNextConsecutive() != next) {
        return false;
      }
      current = next;
    }
    return true;
  }

  private Value createSentinelRegisterValue() {
    return createValue(ValueType.OBJECT);
  }

  private LiveIntervals createSentinelLiveInterval(Value sentinelValue) {
    LiveIntervals sentinelInterval = new LiveIntervals(sentinelValue);
    sentinelInterval.addRange(LiveRange.INFINITE);
    liveIntervals.add(sentinelInterval);
    return sentinelInterval;
  }

  private void linkArgumentValues() {
    Value current = preArgumentSentinelValue = createSentinelRegisterValue();
    for (Value argument : code.collectArguments()) {
      current.linkTo(argument);
      current = argument;
    }
    Value endSentinel = createSentinelRegisterValue();
    current.linkTo(endSentinel);
  }

  private void setupArgumentLiveIntervals() {
    Value current = preArgumentSentinelValue;
    LiveIntervals currentIntervals = createSentinelLiveInterval(current);
    current = current.getNextConsecutive();
    int index = 0;
    while (current.getNextConsecutive() != null) {
      // Add a live range to this value from the beginning of the block up to the argument
      // instruction to avoid dead arguments without a range. This may create an actually empty
      // range like [0,0[ but that works, too.
      LiveIntervals argumentInterval = new LiveIntervals(current);
      argumentInterval.addRange(new LiveRange(0, index * INSTRUCTION_NUMBER_DELTA));
      index++;
      liveIntervals.add(argumentInterval);
      currentIntervals.link(argumentInterval);
      currentIntervals = argumentInterval;
      current = current.getNextConsecutive();
    }
    currentIntervals.link(createSentinelLiveInterval(current));
  }

  private void insertArgumentMoves() {
    // Record the constraint that incoming arguments are in consecutive registers.
    // We also insert sentinel registers before and after the arguments at this
    // point.
    linkArgumentValues();
    setupArgumentLiveIntervals();
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        Instruction instruction = it.next();
        if (instruction.isInvoke()) {
          // Rewind so moves are inserted before the invoke.
          it.previous();
          // Generate the argument moves.
          generateArgumentMoves(instruction.asInvoke(), it);
          // Move past the move again.
          it.next();
        }
      }
    }
  }

  private void computeNeedsRegister() {
    for (BasicBlock block : code.topologicallySortedBlocks()) {
      for (Instruction instruction : block.getInstructions()) {
        if (instruction.outValue() != null) {
          instruction.outValue().computeNeedsRegister();
        }
      }
    }
  }

  private void pinArgumentRegisters() {
    // Special handling for arguments. Pin their register.
    int register = 0;
    Value current = preArgumentSentinelValue;
    while (current != null) {
      LiveIntervals argumentLiveInterval = current.getLiveIntervals();
      // Pin the argument register. We use getFreeConsecutiveRegisters to make sure that we update
      // the max register number.
      register = getFreeConsecutiveRegisters(argumentLiveInterval.requiredRegisters());
      assignRegister(argumentLiveInterval, register);
      current = current.getNextConsecutive();
    }
    assert register == numberOfArgumentRegisters + NUMBER_OF_SENTINEL_REGISTERS - 1;
  }

  private int getFreeConsecutiveRegisters(int numberOfRegister) {
    List<Integer> unused = new ArrayList<>();
    int first = getNextFreeRegister();
    int current = first;
    while ((current - first + 1) != numberOfRegister) {
      for (int i = 0; i < numberOfRegister - 1; i++) {
        int next = getNextFreeRegister();
        if (next != current + 1) {
          for (int j = first; j <= current; j++) {
            unused.add(j);
          }
          first = next;
          current = first;
          break;
        }
        current++;
      }
    }
    freeRegisters.addAll(unused);
    maxRegisterNumber = Math.max(maxRegisterNumber, first + numberOfRegister - 1);
    return first;
  }

  private int getNextFreeRegister() {
    if (freeRegisters.size() > 0) {
      return freeRegisters.pollFirst();
    }
    return nextUnusedRegisterNumber++;
  }

  private void excludeRegistersForInterval(LiveIntervals intervals, Set<Integer> excluded) {
    int register = intervals.getRegister();
    for (int i = 0; i < intervals.requiredRegisters(); i++) {
      if (freeRegisters.remove(register + i)) {
        excluded.add(register + i);
      }
    }
  }

  private void freeRegistersForIntervals(LiveIntervals intervals) {
    int register = intervals.getRegister();
    freeRegisters.add(register);
    if (intervals.getType() == MoveType.WIDE) {
      freeRegisters.add(register + 1);
    }
  }

  private void takeRegistersForIntervals(LiveIntervals intervals) {
    int register = intervals.getRegister();
    freeRegisters.remove(register);
    if (intervals.getType() == MoveType.WIDE) {
      freeRegisters.remove(register + 1);
    }
  }

  private boolean noLinkedValues() {
    for (BasicBlock block : code.blocks) {
      for (Phi phi : block.getPhis()) {
        assert phi.getNextConsecutive() == null;
      }
      for (Instruction instruction : block.getInstructions()) {
        for (Value value : instruction.inValues()) {
          assert value.getNextConsecutive() == null;
        }
        assert instruction.outValue() == null ||
            instruction.outValue().getNextConsecutive() == null;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("Live ranges:\n");
    for (LiveIntervals intervals : liveIntervals) {
      Value value = intervals.getValue();
      builder.append(value);
      builder.append(" ");
      builder.append(intervals);
    }
    builder.append("\nLive range ascii art: \n");
    for (LiveIntervals intervals : liveIntervals) {
      Value value = intervals.getValue();
      if (intervals.getRegister() == NO_REGISTER) {
        StringUtils.appendRightPadded(builder, value + " (no reg): ", 20);
      } else {
        StringUtils.appendRightPadded(builder, value + " r" + intervals.getRegister() + ": ", 20);
      }
      builder.append("|");
      builder.append(intervals.toAscciArtString());
      builder.append("\n");
    }
    return builder.toString();
  }
}
