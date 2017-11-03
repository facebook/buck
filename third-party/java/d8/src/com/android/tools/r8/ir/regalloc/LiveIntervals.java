// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.regalloc;

import static com.android.tools.r8.dex.Constants.U16BIT_MAX;
import static com.android.tools.r8.ir.regalloc.LinearScanRegisterAllocator.NO_REGISTER;

import com.android.tools.r8.code.MoveType;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.utils.CfgPrinter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

public class LiveIntervals implements Comparable<LiveIntervals> {

  private final Value value;
  private final MoveType type;
  private LiveIntervals nextConsecutive;
  private LiveIntervals previousConsecutive;
  private LiveIntervals splitParent;
  private List<LiveIntervals> splitChildren = new ArrayList<>();
  private List<LiveRange> ranges = new ArrayList<>();
  private TreeSet<LiveIntervalsUse> uses = new TreeSet<>();
  private int numberOfConsecutiveRegisters = -1;
  private int register = NO_REGISTER;
  private LiveIntervals hint;
  private boolean spilled = false;
  private boolean usedInMonitorOperations = false;

  // Only registers up to and including the registerLimit are allowed for this interval.
  private int registerLimit = U16BIT_MAX;

  // Max register used for any of the non-spilled splits for these live intervals or for any of the
  // live intervals that this live interval is connected to by phi moves. This is used to
  // conservatively determine if it is safe to use rematerialization for this value.
  private int maxNonSpilledRegister = NO_REGISTER;

  LiveIntervals(Value value) {
    this.value = value;
    this.type = MoveType.fromValueType(value.outType());
    usedInMonitorOperations = value.usedInMonitorOperation();
    splitParent = this;
    value.setLiveIntervals(this);
  }

  LiveIntervals(LiveIntervals splitParent) {
    this.splitParent = splitParent;
    value = splitParent.value;
    type = splitParent.type;
    usedInMonitorOperations = splitParent.usedInMonitorOperations;
  }

  private int toInstructionPosition(int position) {
    return position % 2 == 0 ? position : position + 1;
  }

  private int toGapPosition(int position) {
    return position % 2 == 1 ? position : position - 1;
  }

  public MoveType getType() {
    return type;
  }

  public Value getValue() {
    return value;
  }

  public int requiredRegisters() {
    return type == MoveType.WIDE ? 2 : 1;
  }

  public void setHint(LiveIntervals intervals) {
    hint = intervals;
  }

  public LiveIntervals getHint() {
    return hint;
  }

  public void setSpilled(boolean value) {
    spilled = value;
  }

  public boolean isSpilled() {
    return spilled;
  }

  public boolean isRematerializable(LinearScanRegisterAllocator registerAllocator) {
    if (value.isArgument()) {
      return true;
    }
    // TODO(ager): rematerialize const string as well.
    if (!value.isConstNumber()) {
      return false;
    }
    // If one of the non-spilled splits uses a register that is higher than U8BIT_MAX we cannot
    // rematerialize it using a ConstNumber instruction and we use spill moves instead of
    // rematerialization. We use this check both before and after we have computed the set
    // of unused registers. We therefore have to be careful to use the same max number for
    // these computations. We use the unadjusted real register number to make sure that
    // isRematerializable for the same intervals does not change from one phase of
    // compilation to the next.
    if (getMaxNonSpilledRegister() == NO_REGISTER) {
      assert allSplitsAreSpilled();
      return true;
    }
    int max = registerAllocator.unadjustedRealRegisterFromAllocated(getMaxNonSpilledRegister());
    return max < Constants.U8BIT_MAX;
  }

  private boolean allSplitsAreSpilled() {
    assert isSpilled();
    for (LiveIntervals splitChild : splitChildren) {
      assert splitChild.isSpilled();
    }
    return true;
  }

  public boolean isSpilledAndRematerializable(LinearScanRegisterAllocator allocator) {
    return isSpilled() && isRematerializable(allocator);
  }

  public void link(LiveIntervals next) {
    assert numberOfConsecutiveRegisters == -1;
    nextConsecutive = next;
    next.previousConsecutive = this;
  }

  public boolean isLinked() {
    return splitParent.previousConsecutive != null || splitParent.nextConsecutive != null;
  }

  public boolean isArgumentInterval() {
    // TODO(ager): This is pretty indirect. We might want to have a more direct indication.
    LiveIntervals current = splitParent;
    while (current.previousConsecutive != null) {
      current = current.previousConsecutive;
    }
    return current.ranges.get(0).isInfinite();
  }


  public LiveIntervals getStartOfConsecutive() {
    LiveIntervals current = this;
    while (current.previousConsecutive != null) {
      current = current.previousConsecutive;
    }
    return current;
  }

  public LiveIntervals getNextConsecutive() {
    return nextConsecutive;
  }

  public LiveIntervals getPreviousConsecutive() {
    return previousConsecutive;
  }

  public int numberOfConsecutiveRegisters() {
    LiveIntervals start = getStartOfConsecutive();
    if (start.numberOfConsecutiveRegisters != -1) {
      assert start.numberOfConsecutiveRegisters == computeNumberOfConsecutiveRegisters();
      return start.numberOfConsecutiveRegisters;
    }
    return computeNumberOfConsecutiveRegisters();
  }

  private int computeNumberOfConsecutiveRegisters() {
    LiveIntervals start = getStartOfConsecutive();
    int result = 0;
    for (LiveIntervals current = start;
        current != null;
        current = current.nextConsecutive) {
      result += current.requiredRegisters();
    }
    start.numberOfConsecutiveRegisters = result;
    return result;
  }

  public boolean hasSplits() {
    return splitChildren.size() != 0;
  }

  public List<LiveIntervals> getSplitChildren() {
    return splitChildren;
  }

  public LiveIntervals getSplitParent() {
    return splitParent;
  }

  /**
   * Add a live range to the intervals.
   *
   * @param range the range to add
   */
  public void addRange(LiveRange range) {
    boolean added = tryAddRange(range);
    assert added;
  }

  private boolean tryAddRange(LiveRange range) {
    if (ranges.size() > 0) {
      LiveRange lastRange = ranges.get(ranges.size() - 1);
      if (lastRange.isInfinite()) {
        return false;
      }
      int rangeStartInstructionPosition = toInstructionPosition(range.start);
      int lastRangeEndInstructionPosition = toInstructionPosition(lastRange.end);
      if (lastRangeEndInstructionPosition > rangeStartInstructionPosition) {
        return false;
      }
      if (lastRangeEndInstructionPosition == rangeStartInstructionPosition) {
        lastRange.end = range.end;
        return true;
      }
    }
    ranges.add(range);
    return true;
  }

  /**
   * Record a use for this interval.
   */
  public void addUse(LiveIntervalsUse use) {
    uses.add(use);
    updateRegisterConstraint(use.getLimit());
  }

  public void updateRegisterConstraint(int constraint) {
    registerLimit = Math.min(registerLimit, constraint);
  }

  public TreeSet<LiveIntervalsUse> getUses() {
    return uses;
  }

  public List<LiveRange> getRanges() {
    return ranges;
  }

  public int getStart() {
    assert !ranges.isEmpty();
    return ranges.get(0).start;
  }

  public int getEnd() {
    assert !ranges.isEmpty();
    return ranges.get(ranges.size() - 1).end;
  }

  public int getRegister() {
    return register;
  }

  public int getRegisterLimit() {
    return registerLimit;
  }

  public void setRegister(int n) {
    assert register == NO_REGISTER || register == n;
    register = n;
  }

  private int computeMaxNonSpilledRegister() {
    assert splitParent == this;
    assert maxNonSpilledRegister == NO_REGISTER;
    if (!isSpilled()) {
      maxNonSpilledRegister = getRegister();
    }
    for (LiveIntervals child : splitChildren) {
      if (!child.isSpilled()) {
        maxNonSpilledRegister = Math.max(maxNonSpilledRegister, child.getRegister());
      }
    }
    return maxNonSpilledRegister;
  }

  public void setMaxNonSpilledRegister(int i) {
    assert i >= splitParent.maxNonSpilledRegister;
    splitParent.maxNonSpilledRegister = i;
  }

  public int getMaxNonSpilledRegister() {
    if (splitParent.maxNonSpilledRegister != NO_REGISTER) {
      return splitParent.maxNonSpilledRegister;
    }
    return splitParent.computeMaxNonSpilledRegister();
  }

  public boolean usesRegister(int n) {
    if (register == n || (getType() == MoveType.WIDE && register + 1 == n)) {
      return true;
    }
    return false;
  }

  public void clearRegisterAssignment() {
    register = NO_REGISTER;
    hint = null;
  }

  public boolean overlapsPosition(int position) {
    for (LiveRange range : ranges) {
      if (range.start > position) {
        // Ranges are sorted. When a range starts after position there is no overlap.
        return false;
      }
      if (position < range.end) {
        return true;
      }
    }
    return false;
  }

  public boolean overlaps(LiveIntervals other) {
    return nextOverlap(other) != -1;
  }

  public int nextOverlap(LiveIntervals other) {
    Iterator<LiveRange> it = other.ranges.iterator();
    LiveRange otherRange = it.next();
    for (LiveRange range : ranges) {
      while (otherRange.end <= range.start) {
        if (!it.hasNext()) {
          return -1;
        }
        otherRange = it.next();
      }
      if (otherRange.start < range.end) {
        return otherRange.start;
      }
    }
    return -1;
  }

  public int firstUseAfter(int unhandledStart) {
    for (LiveIntervalsUse use : uses) {
      if (use.getPosition() >= unhandledStart) {
        return use.getPosition();
      }
    }
    return Integer.MAX_VALUE;
  }

  public int getFirstUse() {
    return uses.first().getPosition();
  }

  public LiveIntervalsUse firstUseWithConstraint() {
    for (LiveIntervalsUse use : uses) {
      if (use.hasConstraint()) {
        return use;
      }
    }
    return null;
  }

  public LiveIntervals splitBefore(int start) {
    if (toInstructionPosition(start) == toInstructionPosition(getStart())) {
      assert uses.size() == 0 || getFirstUse() != start;
      register = NO_REGISTER;
      return this;
    }
    start = toGapPosition(start);
    LiveIntervals splitChild = new LiveIntervals(splitParent);
    splitParent.splitChildren.add(splitChild);
    List<LiveRange> beforeSplit = new ArrayList<>();
    List<LiveRange> afterSplit = new ArrayList<>();
    if (start == getEnd()) {
      beforeSplit = ranges;
      afterSplit.add(new LiveRange(start, start));
    } else {
      int rangeToSplitIndex = 0;
      for (; rangeToSplitIndex < ranges.size(); rangeToSplitIndex++) {
        LiveRange range = ranges.get(rangeToSplitIndex);
        if (range.start <= start && range.end > start) {
          break;
        }
        if (range.start > start) {
          break;
        }
      }
      LiveRange rangeToSplit = ranges.get(rangeToSplitIndex);
      beforeSplit.addAll(ranges.subList(0, rangeToSplitIndex));
      if (rangeToSplit.start < start) {
        beforeSplit.add(new LiveRange(rangeToSplit.start, start));
        afterSplit.add(new LiveRange(start, rangeToSplit.end));
      } else {
        afterSplit.add(rangeToSplit);
      }
      afterSplit.addAll(ranges.subList(rangeToSplitIndex + 1, ranges.size()));
    }
    splitChild.ranges = afterSplit;
    ranges = beforeSplit;
    while (!uses.isEmpty() && uses.last().getPosition() >= start) {
      splitChild.addUse(uses.pollLast());
    }
    // Recompute limit after having removed uses from this interval.
    recomputeLimit();
    assert !ranges.isEmpty();
    assert !splitChild.ranges.isEmpty();
    return splitChild;
  }

  private void recomputeLimit() {
    registerLimit = U16BIT_MAX;
    for (LiveIntervalsUse use : uses) {
      updateRegisterConstraint(use.getLimit());
    }
  }

  public LiveIntervals getSplitCovering(int instructionNumber) {
    assert getSplitParent() == this;
    // Check if this interval itself is covering the instruction.
    if (getStart() <= instructionNumber && getEnd() > instructionNumber) {
      return this;
    }
    // If the instruction number is not in this intervals range, we go through all split children.
    // If we do not find a child that contains the instruction number we return the interval
    // whose end is the instruction number. This is needed when transitioning values across
    // control-flow boundaries.
    LiveIntervals matchingEnd = getEnd() == instructionNumber ? this : null;
    for (LiveIntervals splitChild : splitChildren) {
      if (splitChild.getStart() <= instructionNumber && splitChild.getEnd() > instructionNumber) {
        return splitChild;
      }
      if (splitChild.getEnd() == instructionNumber) {
        matchingEnd = splitChild;
      }
    }
    if (matchingEnd != null) {
      return matchingEnd;
    }
    assert false : "Couldn't find split covering instruction position.";
    return null;
  }

  public boolean isConstantNumberInterval() {
    return value.definition != null && value.isConstNumber();
  }

  public boolean usedInMonitorOperation() {
    return usedInMonitorOperations;
  }

  public int numberOfUsesWithConstraint() {
    int count = 0;
    for (LiveIntervalsUse use : getUses()) {
      if (use.hasConstraint()) {
        count++;
      }
    }
    return count;
  }

  @Override
  public int compareTo(LiveIntervals other) {
    int startDiff = getStart() - other.getStart();
    return startDiff != 0 ? startDiff : (value.getNumber() - other.value.getNumber());
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("(cons ");
    // Use the field here to avoid toString to have side effects.
    builder.append(numberOfConsecutiveRegisters);
    builder.append("): ");
    for (LiveRange range : getRanges()) {
      builder.append(range);
      builder.append(" ");
    }
    builder.append("\n");
    return builder.toString();
  }

  public String toAscciArtString() {
    StringBuilder builder = new StringBuilder();
    int current = 0;
    for (LiveRange range : getRanges()) {
      if (range.isInfinite()) {
        builder.append("--- infinite ---...");
        break;
      }
      for (; current < range.start; current++) {
        builder.append(" ");
      }
      for (; current < range.end; current++) {
        builder.append("-");
      }
    }
    return builder.toString();
  }

  public void print(CfgPrinter printer, int number, int parentNumber) {
    printer.append(number * 10000 + register) // range number
        .sp().append("object") // range type
        .sp().append(parentNumber * 10000 + getSplitParent().getRegister()) // split parent
        .sp().append(-1); // hint
    for (LiveRange range : getRanges()) {
      printer.sp().append(range.toString());
    }
    for (LiveIntervalsUse use : getUses()) {
      printer.sp().append(use.getPosition()).sp().append("M");
    }
    printer.append(" \"\"").ln();
    int delta = 0;
    for (LiveIntervals splitChild : splitChildren) {
      delta += 10000;
      splitChild.print(printer, number + delta, number);
    }
  }
}
