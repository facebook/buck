// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.ir.regalloc.LiveIntervals;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.LongInterval;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class Value {

  // Lazily allocated internal data for the debug information of locals.
  // This is wrapped in a class to avoid multiple pointers in the value structure.
  private static class DebugData {

    final DebugLocalInfo local;
    Map<Instruction, DebugUse> users = new HashMap<>();
    Set<Phi> phiUsers = new HashSet<>();

    DebugData(DebugLocalInfo local) {
      this.local = local;
    }
  }

  // A debug-value user represents a point where the value is live, ends or starts.
  // If a point is marked as both ending and starting then it is simply live, but we maintain
  // the marker so as not to unintentionally end it if marked again.
  private enum DebugUse {
    LIVE, START, END, LIVE_FINAL;

    DebugUse start() {
      switch (this) {
        case LIVE:
        case START:
          return START;
        case END:
        case LIVE_FINAL:
          return LIVE_FINAL;
        default:
          throw new Unreachable();
      }
    }

    DebugUse end() {
      switch (this) {
        case LIVE:
        case END:
          return END;
        case START:
        case LIVE_FINAL:
          return LIVE_FINAL;
        default:
          throw new Unreachable();
      }
    }
  }

  public static final int UNDEFINED_NUMBER = -1;

  public static final Value UNDEFINED = new Value(UNDEFINED_NUMBER, ValueType.OBJECT, null);

  protected final int number;
  protected final ValueType type;
  public Instruction definition = null;
  private LinkedList<Instruction> users = new LinkedList<>();
  private Set<Instruction> uniqueUsers = null;
  private LinkedList<Phi> phiUsers = new LinkedList<>();
  private Set<Phi> uniquePhiUsers = null;
  private Value nextConsecutive = null;
  private Value previousConsecutive = null;
  private LiveIntervals liveIntervals;
  private int needsRegister = -1;
  private boolean neverNull = false;
  private boolean isThis = false;
  private boolean isArgument = false;
  private boolean knownToBeBoolean = false;
  private LongInterval valueRange;
  private DebugData debugData;

  public Value(int number, ValueType type, DebugLocalInfo local) {
    this.number = number;
    this.type = type;
    this.debugData = local == null ? null : new DebugData(local);
  }

  public boolean isFixedRegisterValue() {
    return false;
  }

  public FixedRegisterValue asFixedRegisterValue() {
    return null;
  }

  public int getNumber() {
    return number;
  }

  public int requiredRegisters() {
    return type.requiredRegisters();
  }

  public DebugLocalInfo getLocalInfo() {
    return debugData == null ? null : debugData.local;
  }

  public boolean hasLocalInfo() {
    return getLocalInfo() != null;
  }

  public void setLocalInfo(DebugLocalInfo local) {
    assert local != null;
    assert debugData == null;
    debugData = new DebugData(local);
  }

  public void clearLocalInfo() {
    assert debugData.users.isEmpty();
    assert debugData.phiUsers.isEmpty();
    debugData = null;
  }

  public List<Instruction> getDebugLocalStarts() {
    if (debugData == null) {
      return Collections.emptyList();
    }
    List<Instruction> starts = new ArrayList<>(debugData.users.size());
    for (Entry<Instruction, DebugUse> entry : debugData.users.entrySet()) {
      if (entry.getValue() == DebugUse.START) {
        starts.add(entry.getKey());
      }
    }
    return starts;
  }

  public List<Instruction> getDebugLocalEnds() {
    if (debugData == null) {
      return Collections.emptyList();
    }
    List<Instruction> ends = new ArrayList<>(debugData.users.size());
    for (Entry<Instruction, DebugUse> entry : debugData.users.entrySet()) {
      if (entry.getValue() == DebugUse.END) {
        ends.add(entry.getKey());
      }
    }
    return ends;
  }

  public void addDebugLocalStart(Instruction start) {
    assert start != null;
    debugData.users.put(start, markStart(debugData.users.get(start)));
  }

  private DebugUse markStart(DebugUse use) {
    assert use != null;
    return use == null ? DebugUse.START : use.start();
  }

  public void addDebugLocalEnd(Instruction end) {
    assert end != null;
    debugData.users.put(end, markEnd(debugData.users.get(end)));
  }

  private DebugUse markEnd(DebugUse use) {
    assert use != null;
    return use == null ? DebugUse.END : use.end();
  }

  public void linkTo(Value other) {
    assert nextConsecutive == null || nextConsecutive == other;
    assert other.previousConsecutive == null || other.previousConsecutive == this;
    other.previousConsecutive = this;
    nextConsecutive = other;
  }

  public void replaceLink(Value newArgument) {
    assert isLinked();
    if (previousConsecutive != null) {
      previousConsecutive.nextConsecutive = newArgument;
      newArgument.previousConsecutive = previousConsecutive;
      previousConsecutive = null;
    }
    if (nextConsecutive != null) {
      nextConsecutive.previousConsecutive = newArgument;
      newArgument.nextConsecutive = nextConsecutive;
      nextConsecutive = null;
    }
  }

  public boolean isLinked() {
    return nextConsecutive != null || previousConsecutive != null;
  }

  public Value getStartOfConsecutive() {
    Value current = this;
    while (current.getPreviousConsecutive() != null) {
      current = current.getPreviousConsecutive();
    }
    return current;
  }

  public Value getNextConsecutive() {
    return nextConsecutive;
  }

  public Value getPreviousConsecutive() {
    return previousConsecutive;
  }

  public Set<Instruction> uniqueUsers() {
    if (uniqueUsers != null) {
      return uniqueUsers;
    }
    return uniqueUsers = ImmutableSet.copyOf(users);
  }

  public Set<Phi> uniquePhiUsers() {
    if (uniquePhiUsers != null) {
      return uniquePhiUsers;
    }
    return uniquePhiUsers = ImmutableSet.copyOf(phiUsers);
  }

  public Set<Instruction> debugUsers() {
    return debugData == null ? null : Collections.unmodifiableSet(debugData.users.keySet());
  }

  public Set<Phi> debugPhiUsers() {
    return debugData == null ? null : Collections.unmodifiableSet(debugData.phiUsers);
  }

  public int numberOfUsers() {
    int size = users.size();
    if (size <= 1) {
      return size;
    }
    return uniqueUsers().size();
  }

  public int numberOfPhiUsers() {
    int size = phiUsers.size();
    if (size <= 1) {
      return size;
    }
    return uniquePhiUsers().size();
  }

  public int numberOfAllNonDebugUsers() {
    return numberOfUsers() + numberOfPhiUsers();
  }

  public int numberOfDebugUsers() {
    return debugData == null ? 0 : debugData.users.size();
  }

  public int numberOfDebugPhiUsers() {
    return debugData == null ? 0 : debugData.phiUsers.size();
  }

  public int numberOfAllDebugUsers() {
    return numberOfDebugUsers() + numberOfDebugPhiUsers();
  }

  public int numberOfAllUsers() {
    return numberOfAllNonDebugUsers() + numberOfAllDebugUsers();
  }

  public boolean isUsed() {
    return !users.isEmpty() || !phiUsers.isEmpty() || numberOfAllDebugUsers() > 0;
  }

  public boolean usedInMonitorOperation() {
    for (Instruction instruction : uniqueUsers()) {
      if (instruction.isMonitor()) {
        return true;
      }
    }
    return false;
  }

  public void addUser(Instruction user) {
    users.add(user);
    uniqueUsers = null;
  }

  public void removeUser(Instruction user) {
    users.remove(user);
    uniqueUsers = null;
  }

  public void clearUsers() {
    users.clear();
    uniqueUsers = null;
    phiUsers.clear();
    uniquePhiUsers = null;
    if (debugData != null) {
      debugData.users.clear();
      debugData.phiUsers.clear();
    }
  }

  public void addPhiUser(Phi user) {
    phiUsers.add(user);
    uniquePhiUsers = null;
  }

  public void removePhiUser(Phi user) {
    phiUsers.remove(user);
    uniquePhiUsers = null;
  }

  public void addDebugUser(Instruction user) {
    assert hasLocalInfo();
    debugData.users.putIfAbsent(user, DebugUse.LIVE);
  }

  public void addDebugPhiUser(Phi user) {
    assert hasLocalInfo();
    debugData.phiUsers.add(user);
  }

  public boolean isUninitializedLocal() {
    return definition != null && definition.isDebugLocalUninitialized();
  }

  public boolean isInitializedLocal() {
    return !isUninitializedLocal();
  }

  public void removeDebugUser(Instruction user) {
    if (debugData != null && debugData.users != null) {
      debugData.users.remove(user);
      return;
    }
    assert false;
  }

  public void removeDebugPhiUser(Phi user) {
    if (debugData != null && debugData.phiUsers != null) {
      debugData.phiUsers.remove(user);
      return;
    }
    assert false;
  }

  public boolean hasUsersInfo() {
    return users != null;
  }

  public void clearUsersInfo() {
    users = null;
    uniqueUsers = null;
    phiUsers = null;
    uniquePhiUsers = null;
    if (debugData != null) {
      debugData.users = null;
      debugData.phiUsers = null;
    }
  }

  public void replaceUsers(Value newValue) {
    if (this == newValue) {
      return;
    }
    for (Instruction user : uniqueUsers()) {
      user.replaceValue(this, newValue);
    }
    for (Phi user : uniquePhiUsers()) {
      user.replaceOperand(this, newValue);
    }
    if (debugData != null) {
      for (Instruction user : debugUsers()) {
        user.replaceDebugValue(this, newValue);
      }
      for (Phi user : debugPhiUsers()) {
        user.replaceDebugValue(this, newValue);
      }
    }
    clearUsers();
  }

  public void replaceDebugUser(Instruction oldUser, Instruction newUser) {
    DebugUse use = debugData.users.remove(oldUser);
    if (use != null) {
      newUser.addDebugValue(this);
      debugData.users.put(newUser, use);
    }
  }

  public void setLiveIntervals(LiveIntervals intervals) {
    assert liveIntervals == null;
    liveIntervals = intervals;
  }

  public LiveIntervals getLiveIntervals() {
    return liveIntervals;
  }

  public boolean needsRegister() {
    assert needsRegister >= 0;
    assert !hasUsersInfo() || (needsRegister > 0) == internalComputeNeedsRegister();
    return needsRegister > 0;
  }

  public void setNeedsRegister(boolean value) {
    assert needsRegister == -1 || (needsRegister > 0) == value;
    needsRegister = value ? 1 : 0;
  }

  public void computeNeedsRegister() {
    assert needsRegister < 0;
    setNeedsRegister(internalComputeNeedsRegister());
  }

  public boolean internalComputeNeedsRegister() {
    if (!isConstNumber()) {
      return true;
    }
    if (numberOfPhiUsers() > 0) {
      return true;
    }
    for (Instruction user : uniqueUsers()) {
      if (user.needsValueInRegister(this)) {
        return true;
      }
    }
    return false;
  }

  public boolean hasRegisterConstraint() {
    for (Instruction instruction : uniqueUsers()) {
      if (instruction.maxInValueRegister() != Constants.U16BIT_MAX) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int hashCode() {
    return number;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("v");
    builder.append(number);
    boolean isConstant = definition != null && definition.isConstNumber();
    if (isConstant || hasLocalInfo()) {
      builder.append("(");
      if (isConstant) {
        ConstNumber constNumber = definition.asConstNumber();
        if (constNumber.outType().isSingle()) {
          builder.append((int) constNumber.getRawValue());
        } else {
          builder.append(constNumber.getRawValue());
        }
      }
      if (isConstant && hasLocalInfo()) {
        builder.append(", ");
      }
      if (hasLocalInfo()) {
        builder.append(getLocalInfo());
      }
      builder.append(")");
    }
    if (valueRange != null) {
      builder.append(valueRange);
    }
    return builder.toString();
  }

  public ValueType outType() {
    return type;
  }

  public ConstInstruction getConstInstruction() {
    assert isConstant();
    return definition.getOutConstantConstInstruction();
  }

  public boolean isConstNumber() {
    return isConstant() && getConstInstruction().isConstNumber();
  }

  public boolean isConstString() {
    return isConstant() && getConstInstruction().isConstString();
  }

  public boolean isConstant() {
    return definition.isOutConstant() && !hasLocalInfo();
  }

  public boolean isPhi() {
    return false;
  }

  public Phi asPhi() {
    return null;
  }

  public void markNeverNull() {
    assert !neverNull;
    neverNull = true;
  }

  /**
   * Returns whether this value is know to never be <code>null</code>.
   */
  public boolean isNeverNull() {
    return neverNull;
  }

  public boolean canBeNull() {
    return !neverNull;
  }

  public void markAsArgument() {
    assert !isArgument;
    assert !isThis;
    isArgument = true;
  }

  public boolean isArgument() {
    return isArgument;
  }

  public void setKnownToBeBoolean(boolean knownToBeBoolean) {
    this.knownToBeBoolean = knownToBeBoolean;
  }

  public boolean knownToBeBoolean() {
    return knownToBeBoolean;
  }

  public void markAsThis() {
    assert isArgument;
    assert !isThis;
    isThis = true;
    markNeverNull();
  }

  /**
   * Returns whether this value is known to be the receiver (this argument) in a method body.
   * <p>
   * For a receiver value {@link #isNeverNull()} is guarenteed to be <code>true</code> as well.
   */
  public boolean isThis() {
    return isThis;
  }

  public void setValueRange(LongInterval range) {
    valueRange = range;
  }

  public boolean hasValueRange() {
    return valueRange != null || isConstNumber();
  }

  public boolean isValueInRange(int value) {
    if (isConstNumber()) {
      return value == getConstInstruction().asConstNumber().getIntValue();
    } else {
      return valueRange != null && valueRange.containsValue(value);
    }
  }

  public LongInterval getValueRange() {
    if (isConstNumber()) {
      if (type.isSingle()) {
        int value = getConstInstruction().asConstNumber().getIntValue();
        return new LongInterval(value, value);
      } else {
        assert type.isWide();
        long value = getConstInstruction().asConstNumber().getLongValue();
        return new LongInterval(value, value);
      }
    } else {
      return valueRange;
    }
  }

  public boolean isDead(InternalOptions options) {
    // Totally unused values are trivially dead.
    return !isUsed() || isDead(options, new HashSet<>());
  }

  protected boolean isDead(InternalOptions options, Set<Value> active) {
    // If the value has debug users we cannot eliminate it since it represents a value in a local
    // variable that should be visible in the debugger.
    if (numberOfDebugUsers() != 0) {
      return false;
    }
    // This is a candidate for a dead value. Guard against looping by adding it to the set of
    // currently active values.
    active.add(this);
    for (Instruction instruction : uniqueUsers()) {
      if (!instruction.canBeDeadCode(null, options)) {
        return false;
      }
      Value outValue = instruction.outValue();
      // Instructions with no out value cannot be dead code by the current definition
      // (unused out value). They typically side-effect input values or deals with control-flow.
      assert outValue != null;
      if (!active.contains(outValue) && !outValue.isDead(options, active)) {
        return false;
      }
    }
    for (Phi phi : uniquePhiUsers()) {
      if (!active.contains(phi) && !phi.isDead(options, active)) {
        return false;
      }
    }
    return true;
  }

  public boolean isZero() {
    return isConstant()
        && getConstInstruction().isConstNumber()
        && getConstInstruction().asConstNumber().isZero();
  }
}
