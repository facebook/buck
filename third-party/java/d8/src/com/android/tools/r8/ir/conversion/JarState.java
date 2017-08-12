// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.errors.InvalidDebugInfoException;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.JarApplicationReader;
import com.android.tools.r8.utils.DescriptorUtils;
import com.google.common.base.Equivalence;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LocalVariableNode;

/**
 * Abstraction of the java bytecode state at a given control-flow point.
 *
 * The abstract state is defined by the abstract contents of locals and contents of the stack.
 */
public class JarState {

  // Type representatives of "any object/array" using invalid names (only valid for asserting).
  public static final Type REFERENCE_TYPE = Type.getObjectType("<any reference>");
  public static final Type OBJECT_TYPE = Type.getObjectType("<any object>");
  public static final Type ARRAY_TYPE = Type.getObjectType("[<any array>");

  // Type representative for the null value (non-existent but works for tracking the types here).
  public static final Type NULL_TYPE = Type.getObjectType("<null>");

  // TODO(zerny): Define an internal Type wrapping ASM types so that we can define an actual value.
  // Type representative for a value that may be either a boolean or a byte.
  public static final Type BYTE_OR_BOOL_TYPE = null;

  // Equivalence to canonicalize local-variable information.
  private static class LocalNodeEquivalence extends Equivalence<LocalVariableNode> {

    @Override
    protected boolean doEquivalent(LocalVariableNode a, LocalVariableNode b) {
      if (!a.name.equals(b.name) || !a.desc.equals(b.desc)) {
        return false;
      }
      return (a.signature == null && b.signature == null)
          || (a.signature != null && a.signature.equals(b.signature));
    }

    @Override
    protected int doHash(LocalVariableNode local) {
      return 31 * local.name.hashCode()
          + 7 * local.desc.hashCode()
          + (local.signature == null ? 0 : local.signature.hashCode());
    }
  }

  // Simple pairing of local-node with its debug info and ASM type.
  private static class LocalNodeInfo {
    final Type type;
    final LocalVariableNode node;
    final DebugLocalInfo info;

    LocalNodeInfo(LocalVariableNode node, DebugLocalInfo info) {
      this.node = node;
      this.info = info;
      type = Type.getType(node.desc);
    }
  }

  // Collection of locals information at a program point.
  private static class LocalsAtOffset {
    // Note that we assume live is always a super-set of starts.
    final List<LocalNodeInfo> live;
    final List<LocalNodeInfo> starts;
    final List<LocalNodeInfo> ends;

    IdentityHashMap<DebugLocalInfo, DebugLocalInfo> liveInfosCache = null;

    static final LocalsAtOffset EMPTY = new LocalsAtOffset();

    LocalsAtOffset() {
      live = Collections.emptyList();
      starts = Collections.emptyList();
      ends = Collections.emptyList();
    }

    LocalsAtOffset(LocalsAtOffset other) {
      live = new ArrayList<>(other.live);
      // Starts and ends are never shared.
      starts = new ArrayList<>();
      ends = new ArrayList<>();
    }

    void addStart(LocalVariableNode node, DebugLocalInfo info) {
      starts.add(new LocalNodeInfo(node, info));
    }

    void addEnd(LocalVariableNode node, DebugLocalInfo info) {
      ends.add(new LocalNodeInfo(node, info));
    }

    void addLive(LocalVariableNode node, DebugLocalInfo info) {
      assert liveInfosCache == null;
      live.add(new LocalNodeInfo(node, info));
    }

    boolean isLive(DebugLocalInfo info) {
      if (live.size() < 10) {
        for (LocalNodeInfo entry : live) {
          if (entry.info == info) {
            return true;
          }
        }
        return false;
      }
      if (liveInfosCache == null) {
        liveInfosCache = new IdentityHashMap<>(live.size());
        for (LocalNodeInfo entry : live) {
          liveInfosCache.put(entry.info, entry.info);
        }
      }
      return liveInfosCache.containsKey(info);
    }
  }

  // Typed mapping from a local slot or stack slot to a virtual register.
  public static class Slot {
    public final int register;
    public final Type type;

    @Override
    public String toString() {
      return "r" + register + ":" + type;
    }

    public Slot(int register, Type type) {
      assert type != REFERENCE_TYPE;
      assert type != OBJECT_TYPE;
      assert type != ARRAY_TYPE;
      this.register = register;
      this.type = type;
    }

    public boolean isCompatibleWith(Type other) {
      return isCompatible(type, other);
    }

    public boolean isCategory1() {
      return isCategory1(type);
    }

    public Type getArrayElementType() {
      assert type == NULL_TYPE || type == ARRAY_TYPE || type.getSort() == Type.ARRAY;
      if (type == JarState.NULL_TYPE) {
        return null;
      }
      return getArrayElementType(type);
    }

    public static boolean isCategory1(Type type) {
      return type != Type.LONG_TYPE && type != Type.DOUBLE_TYPE;
    }

    public static boolean isCompatible(Type type, Type other) {
      assert type != REFERENCE_TYPE;
      assert type != OBJECT_TYPE;
      assert type != ARRAY_TYPE;
      if (type == BYTE_OR_BOOL_TYPE) {
        type = Type.BYTE_TYPE;
      }
      if (other == BYTE_OR_BOOL_TYPE) {
        other = Type.BYTE_TYPE;
      }
      int sort = type.getSort();
      int otherSort = other.getSort();
      if (isReferenceCompatible(type, other)) {
        return true;
      }
      // Integers are assumed compatible with any other 32-bit integral.
      if (isIntCompatible(sort)) {
        return isIntCompatible(otherSort);
      }
      if (isIntCompatible(otherSort)) {
        return isIntCompatible(sort);
      }
      // In all other cases we require the two types to represent the same concrete type.
      return type.equals(other);
    }

    private static Type getArrayElementType(Type type) {
      String desc = type.getDescriptor();
      assert desc.charAt(0) == '[';
      return Type.getType(desc.substring(1));
    }

    private static boolean isIntCompatible(int sort) {
      return Type.BOOLEAN <= sort && sort <= Type.INT;
    }

    private static boolean isReferenceCompatible(Type type, Type other) {
      int sort = type.getSort();
      int otherSort = other.getSort();

      // Catch all matching.
      if (other == REFERENCE_TYPE) {
        return sort == Type.OBJECT || sort == Type.ARRAY || sort == Type.METHOD;
      }
      if (other == OBJECT_TYPE) {
        return sort == Type.OBJECT;
      }
      if (other == ARRAY_TYPE) {
        return type == NULL_TYPE || sort == Type.ARRAY;
      }

      return (sort == Type.OBJECT && otherSort == Type.ARRAY)
          || (sort == Type.ARRAY && otherSort == Type.OBJECT)
          || (sort == Type.OBJECT && otherSort == Type.OBJECT)
          || (sort == Type.ARRAY && otherSort == Type.ARRAY);
    }
  }

  public static class Local {
    final Slot slot;
    final DebugLocalInfo info;

    public Local(Slot slot, DebugLocalInfo info) {
      this.slot = slot;
      this.info = info;
    }
  }

  // Immutable recording of the state (locals and stack should not be mutated).
  private static class Snapshot {
    public final Local[] locals;
    public final ImmutableList<Slot> stack;

    public Snapshot(Local[] locals, ImmutableList<Slot> stack) {
      this.locals = locals;
      this.stack = stack;
    }

    @Override
    public String toString() {
      return "locals: " + localsToString(Arrays.asList(locals))
          + ", stack: " + stackToString(stack);
    }
  }

  final int startOfStack;
  private int topOfStack;

  // Locals are split into three parts based on types:
  //  1) reference-type locals have registers in range: [0; localsSize[
  //  2) single-width locals have registers in range: [localsSize; 2*localsSize[
  //  3) wide-width locals have registers in range: [2*localsSize; 3*localsSize[
  // This ensures that we can insert debugging-ranges into the SSA graph (via DebugLocal{Start,End})
  // without conflating locals that are shared among different types. This issue arises because a
  // debugging range can be larger than the definite-assignment scope of a local (eg, a local
  // introduced in an unscoped switch case). To ensure that the SSA graph is valid we must introduce
  // the local before inserting any DebugLocalRead (we do so in the method prelude, but that can
  // potentially lead to phi functions merging locals of different move-types. Thus we allocate
  // registers from the three distinct spaces.
  private final int localsSize;
  private final Local[] locals;

  // Equivalence on the position-independent parts of local-variable nodes.
  private final LocalNodeEquivalence localNodeEquivalence = new LocalNodeEquivalence();

  // Canonical local-variable info objects.
  private final Map<Equivalence.Wrapper<LocalVariableNode>, DebugLocalInfo> canonicalLocalInfo;

  // Active locals at each program point at which they change.
  private final Int2ReferenceSortedMap<LocalsAtOffset> localsAtOffsetTable;

  private final Deque<Slot> stack = new ArrayDeque<>();

  private final Map<Integer, Snapshot> targetStates = new HashMap<>();

  // Mode denoting that the state setup is done and we are now emitting IR.
  // Concretely we treat all remaining byte-or-bool types as bytes (no actual type can flow there).
  private boolean building = false;

  public JarState(int maxLocals, List localNodes, JarSourceCode source, JarApplicationReader application) {
    int localsRegistersSize = maxLocals * 3;
    localsSize = maxLocals;
    locals = new Local[localsRegistersSize];
    startOfStack = localsRegistersSize;
    topOfStack = startOfStack;
    localsAtOffsetTable = new Int2ReferenceAVLTreeMap<>();
    localsAtOffsetTable.put(-1, LocalsAtOffset.EMPTY);
    if (localNodes.size() == 0) {
      canonicalLocalInfo = Collections.emptyMap();
    } else if (localNodes.size() == 1) {
      LocalVariableNode local = (LocalVariableNode) localNodes.get(0);
      DebugLocalInfo info = createLocalInfo(local, application);
      canonicalLocalInfo = Collections.singletonMap(localNodeEquivalence.wrap(local), info);
      populateLocalsAtTable(local, info, source);
    } else {
      canonicalLocalInfo = new HashMap<>(localNodes.size());
      for (Object o : localNodes) {
        LocalVariableNode node = (LocalVariableNode) o;
        Equivalence.Wrapper<LocalVariableNode> wrapped = localNodeEquivalence.wrap(node);
        DebugLocalInfo info = canonicalLocalInfo.get(wrapped);
        if (info == null) {
          info = createLocalInfo(node, application);
          canonicalLocalInfo.put(wrapped, info);
        }
        populateLocalsAtTable(node, info, source);
      }
    }
  }

  private static DebugLocalInfo createLocalInfo(
      LocalVariableNode node,
      JarApplicationReader application) {
    return new DebugLocalInfo(
        application.getString(node.name),
        application.getTypeFromDescriptor(node.desc),
        node.signature == null ? null : application.getString(node.signature));
  }

  private void populateLocalsAtTable(
      LocalVariableNode node, DebugLocalInfo info, JarSourceCode source) {
    if (node.start == node.end) {
      return;
    }
    int start = source.getOffset(node.start);
    int end = source.getOffset(node.end);
    // Ensure that there is an entry at the starting point of the local.
    {
      LocalsAtOffset atStart;
      int lastOrStart = localsAtOffsetTable.headMap(start + 1).lastIntKey();
      if (lastOrStart < start) {
        atStart = new LocalsAtOffset(localsAtOffsetTable.get(lastOrStart));
        localsAtOffsetTable.put(start, atStart);
      } else {
        atStart = localsAtOffsetTable.get(start);
      }
      atStart.addStart(node, info);
    }
    // Ensure there is an entry at the ending point of the local.
    {
      LocalsAtOffset atEnd;
      int lastOrEnd = localsAtOffsetTable.headMap(end + 1).lastIntKey();
      if (lastOrEnd < end) {
        atEnd = new LocalsAtOffset(localsAtOffsetTable.get(lastOrEnd));
        localsAtOffsetTable.put(end, atEnd);
      } else {
        atEnd = localsAtOffsetTable.get(end);
      }
      atEnd.addEnd(node, info);
    }
    // Add the local to all live entries in its range.
    for (LocalsAtOffset entry : localsAtOffsetTable.subMap(start, end).values()) {
      entry.addLive(node, info);
    }
  }

  public List<Local> localsNotLiveAtAllSuccessors(IntSet successors) {
    Local[] liveLocals = Arrays.copyOf(locals, locals.length);
    List<Local> deadLocals = new ArrayList<>(locals.length);
    IntIterator it = successors.iterator();
    while (it.hasNext()) {
      LocalsAtOffset localsAtOffset = getLocalsAt(it.nextInt());
      for (int i = 0; i < liveLocals.length; i++) {
        Local live = liveLocals[i];
        if (live != null && live.info != null && !localsAtOffset.isLive(live.info)) {
          deadLocals.add(live);
          liveLocals[i] = null;
        }
      }
    }
    return deadLocals;
  }

  private LocalsAtOffset getLocalsAt(int offset) {
    return offset < 0
        ? LocalsAtOffset.EMPTY
        : localsAtOffsetTable.get(localsAtOffsetTable.headMap(offset + 1).lastIntKey());
  }

  public void setBuilding() {
    assert stack.isEmpty();
    building = true;
    for (int i = 0; i < locals.length; i++) {
      Local local = locals[i];
      if (local != null && local.slot.type == BYTE_OR_BOOL_TYPE) {
        locals[i] = new Local(new Slot(local.slot.register, Type.BYTE_TYPE), local.info);
      }
    }
    for (Entry<Integer, Snapshot> entry : targetStates.entrySet()) {
      Local[] locals = entry.getValue().locals;
      for (int i = 0; i < locals.length; i++) {
        Local local = locals[i];
        if (local != null && local.slot.type == BYTE_OR_BOOL_TYPE) {
          locals[i] = new Local(new Slot(local.slot.register, Type.BYTE_TYPE), local.info);
        }
      }
      ImmutableList.Builder<Slot> builder = ImmutableList.builder();
      boolean found = false;
      for (Slot slot : entry.getValue().stack) {
        if (slot.type == BYTE_OR_BOOL_TYPE) {
          found = true;
          builder.add(new Slot(slot.register, Type.BYTE_TYPE));
        } else {
          builder.add(slot);
        }
      }
      if (found) {
        entry.setValue(new Snapshot(locals, builder.build()));
      }
    }
  }

  // Local variable procedures.

  public List<Local> openLocals(int offset) {
    LocalsAtOffset localsAtOffset = localsAtOffsetTable.get(offset);
    if (localsAtOffset == null) {
      return Collections.emptyList();
    }
    ArrayList<Local> locals = new ArrayList<>(localsAtOffset.starts.size());
    for (LocalNodeInfo start : localsAtOffset.starts) {
      locals.add(setLocalInfo(start.node.index, start.type, start.info));
    }
    return locals;
  }

  public List<Local> getLocalsToClose(int offset) {
    LocalsAtOffset localsAtOffset = localsAtOffsetTable.get(offset);
    if (localsAtOffset == null) {
      return Collections.emptyList();
    }
    ArrayList<Local> locals = new ArrayList<>(localsAtOffset.ends.size());
    for (LocalNodeInfo end : localsAtOffset.ends) {
      int register = getLocalRegister(end.node.index, end.type);
      Local local = getLocalForRegister(register);
      assert local != null;
      if (local.info != null) {
        locals.add(local);
      }
    }
    return locals;
  }

  public void closeLocals(List<Local> localsToClose) {
    for (Local local : localsToClose) {
      assert local != null;
      assert local == getLocalForRegister(local.slot.register);
      setLocalForRegister(local.slot.register, local.slot.type, null);
    }
  }

  public ImmutableList<Local> getLocals() {
    ImmutableList.Builder<Local> nonNullLocals = ImmutableList.builder();
    for (Local local : locals) {
      if (local != null) {
        nonNullLocals.add(local);
      }
    }
    return nonNullLocals.build();
  }

  int getLocalRegister(int index, Type type) {
    assert index < localsSize;
    if (type == BYTE_OR_BOOL_TYPE) {
      assert Slot.isCategory1(type);
      return index + localsSize;
    }
    if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
      return index;
    }
    return Slot.isCategory1(type) ? index + localsSize : index + 2 * localsSize;
  }

  public DebugLocalInfo getLocalInfoForRegister(int register) {
    if (register >= locals.length) {
      return null;
    }
    Local local = getLocalForRegister(register);
    return local == null ? null : local.info;
  }

  private Local getLocalForRegister(int register) {
    return locals[register];
  }

  private Local getLocal(int index, Type type) {
    return getLocalForRegister(getLocalRegister(index, type));
  }

  private Local setLocal(int index, Type type, DebugLocalInfo info) {
    return setLocalForRegister(getLocalRegister(index, type), type, info);
  }

  private Local setLocalForRegister(int register, Type type, DebugLocalInfo info) {
    Slot slot = new Slot(register, type);
    Local local = new Local(slot, info);
    locals[register] = local;
    return local;
  }

  private Local setLocalInfo(int index, Type type, DebugLocalInfo info) {
    return setLocalInfoForRegister(getLocalRegister(index, type), info);
  }

  private Local setLocalInfoForRegister(int register, DebugLocalInfo info) {
    Local existingLocal = getLocalForRegister(register);
    Type type = Type.getType(info.type.toDescriptorString());
    if (!existingLocal.slot.isCompatibleWith(type)) {
      throw new InvalidDebugInfoException(
          "Attempt to define local of type " + prettyType(existingLocal.slot.type) + " as " + info);
    }
    Local local = new Local(existingLocal.slot, info);
    locals[register] = local;
    return local;
  }


  public int writeLocal(int index, Type type) {
    assert nonNullType(type);
    Local local = getLocal(index, type);
    if (local != null && local.info != null && !local.slot.isCompatibleWith(type)) {
      throw new InvalidDebugInfoException(
          "Attempt to write value of type " + prettyType(type) + " to local " + local.info);
    }
    // We cannot assume consistency for writes because we do not have complete information about the
    // scopes of locals. We assume the program to be verified and overwrite if the types mismatch.
    if (local == null || !typeEquals(local.slot.type, type)) {
      DebugLocalInfo info = local == null ? null : local.info;
      local = setLocal(index, type, info);
    }
    return local.slot.register;
  }

  public boolean typeEquals(Type type1, Type type2) {
    return (type1 == BYTE_OR_BOOL_TYPE && type2 == BYTE_OR_BOOL_TYPE)
        || (type1 != null && type1.equals(type2));
  }

  public Slot readLocal(int index, Type type) {
    Local local = getLocal(index, type);
    assert local != null;
    if (local.info != null && !local.slot.isCompatibleWith(type)) {
      throw new InvalidDebugInfoException(
          "Attempt to read value of type " + prettyType(type) + " from local " + local.info);
    }
    assert local.slot.isCompatibleWith(type);
    return local.slot;
  }

  public boolean nonNullType(Type type) {
    return type != null || !building;
  }

  // Stack procedures.

  public int push(Type type) {
    assert nonNullType(type);
    int top = topOfStack;
    // For simplicity, every stack slot (and local variable) is wide (uses two registers).
    topOfStack += 2;
    Slot slot = new Slot(top, type);
    stack.push(slot);
    return top;
  }

  public Slot peek() {
    return stack.peek();
  }

  public Slot peek(Type type) {
    Slot slot = stack.peek();
    assert slot.isCompatibleWith(type);
    return slot;
  }

  public Slot pop() {
    assert topOfStack > startOfStack;
    // For simplicity, every stack slot (and local variable) is wide (uses two registers).
    topOfStack -= 2;
    Slot slot = stack.pop();
    assert nonNullType(slot.type);
    assert slot.register == topOfStack;
    return slot;
  }

  public Slot pop(Type type) {
    Slot slot = pop();
    assert slot.isCompatibleWith(type);
    return slot;
  }

  public Slot[] popReverse(int count) {
    Slot[] slots = new Slot[count];
    for (int i = count - 1; i >= 0; i--) {
      slots[i] = pop();
    }
    return slots;
  }

  public Slot[] popReverse(int count, Type type) {
    Slot[] slots = popReverse(count);
    assert verifySlots(slots, type);
    return slots;
  }

  // State procedures.

  public boolean hasState(int offset) {
    return targetStates.get(offset) != null;
  }

  public void restoreState(int offset) {
    Snapshot snapshot = targetStates.get(offset);
    assert snapshot != null;
    assert locals.length == snapshot.locals.length;
    System.arraycopy(snapshot.locals, 0, locals, 0, locals.length);
    stack.clear();
    stack.addAll(snapshot.stack);
    topOfStack = startOfStack + 2 * stack.size();
  }

  public boolean recordStateForTarget(int target) {
    return recordStateForTarget(target, locals.clone(), ImmutableList.copyOf(stack));
  }

  public boolean recordStateForExceptionalTarget(int target) {
    return recordStateForTarget(
        target,
        locals.clone(),
        ImmutableList.of(new Slot(startOfStack, JarSourceCode.THROWABLE_TYPE)));
  }

  private boolean recordStateForTarget(int target, Local[] locals, ImmutableList<Slot> stack) {
    if (!canonicalLocalInfo.isEmpty()) {
      for (int i = 0; i < locals.length; i++) {
        if (locals[i] != null) {
          locals[i] = new Local(locals[i].slot, null);
        }
      }
      LocalsAtOffset localsAtOffset = getLocalsAt(target);
      for (LocalNodeInfo live : localsAtOffset.live) {
        int register = getLocalRegister(live.node.index, live.type);
        Local local = locals[register];
        locals[register] = new Local(local.slot, live.info);
      }
    }
    Snapshot snapshot = targetStates.get(target);
    if (snapshot != null) {
      Local[] newLocals = mergeLocals(snapshot.locals, locals);
      ImmutableList<Slot> newStack = mergeStacks(snapshot.stack, stack);
      if (newLocals != snapshot.locals || newStack != snapshot.stack) {
        targetStates.put(target, new Snapshot(newLocals, newStack));
        return true;
      }
      // The snapshot is up to date - no new type information recoded.
      return false;
    }
    targetStates.put(target, new Snapshot(locals, stack));
    return true;
  }

  private boolean isRefinement(Type current, Type other) {
    return (current == JarState.NULL_TYPE && other != JarState.NULL_TYPE)
        || (current == JarState.BYTE_OR_BOOL_TYPE && other != JarState.BYTE_OR_BOOL_TYPE);
  }

  private ImmutableList<Slot> mergeStacks(
      ImmutableList<Slot> currentStack, ImmutableList<Slot> newStack) {
    assert currentStack.size() == newStack.size();
    List<Slot> mergedStack = null;
    for (int i = 0; i < currentStack.size(); i++) {
      if (isRefinement(currentStack.get(i).type, newStack.get(i).type)) {
        if (mergedStack == null) {
          mergedStack = new ArrayList<>();
          mergedStack.addAll(currentStack.subList(0, i));
        }
        mergedStack.add(newStack.get(i));
      } else if (mergedStack != null) {
        assert currentStack.get(i).isCompatibleWith(newStack.get(i).type);
        mergedStack.add(currentStack.get(i));
      }
    }
    return mergedStack != null ? ImmutableList.copyOf(mergedStack) : currentStack;
  }

  private Local[] mergeLocals(Local[] currentLocals, Local[] newLocals) {
    assert currentLocals.length == newLocals.length;
    Local[] mergedLocals = null;
    for (int i = 0; i < currentLocals.length; i++) {
      Local currentLocal = currentLocals[i];
      Local newLocal = newLocals[i];
      if (currentLocal == null || newLocal == null) {
        continue;
      }
      // If this assert triggers we can get different debug information for the same local
      // on different control-flow paths and we will have to merge them.
      assert currentLocal.info == newLocal.info;
      if (isRefinement(currentLocal.slot.type, newLocal.slot.type)) {
        if (mergedLocals == null) {
          mergedLocals = new Local[currentLocals.length];
          System.arraycopy(currentLocals, 0, mergedLocals, 0, i);
        }
        Slot newSlot = new Slot(newLocal.slot.register, newLocal.slot.type);
        mergedLocals[i] = new Local(newSlot, newLocal.info);
      } else if (mergedLocals != null) {
        mergedLocals[i] = currentLocals[i];
      }
    }
    return mergedLocals != null ? mergedLocals : currentLocals;
  }

  // Other helpers.

  private static boolean verifySlots(Slot[] slots, Type type) {
    for (Slot slot : slots) {
      assert slot.isCompatibleWith(type);
    }
    return true;
  }

  // Printing helpers.

  @Override
  public String toString() {
    return "locals: " + localsToString(Arrays.asList(locals)) + ", stack: " + stackToString(stack);
  }

  public static String stackToString(Collection<Slot> stack) {
    List<String> strings = new ArrayList<>(stack.size());
    for (Slot slot : stack) {
      if (slot.type == BYTE_OR_BOOL_TYPE) {
        strings.add("<byte|bool>");
      } else {
        strings.add(slot.type.toString());
      }
    }
    StringBuilder builder = new StringBuilder("{ ");
    for (int i = strings.size() - 1; i >= 0; i--) {
      builder.append(strings.get(i));
      if (i > 0) {
        builder.append(", ");
      }
    }
    builder.append(" }");
    return builder.toString();
  }

  public static String localsToString(Collection<Local> locals) {
    StringBuilder builder = new StringBuilder("{ ");
    boolean first = true;
    for (Local local : locals) {
      if (!first) {
        builder.append(", ");
      } else {
        first = false;
      }
      if (local == null) {
        builder.append("_");
      } else if (local.info != null) {
        builder.append(local.info);
      } else if (local.slot.type == BYTE_OR_BOOL_TYPE) {
        builder.append("<byte|bool>");
      } else {
        builder.append(local.slot.type.toString());
      }
    }
    builder.append(" }");
    return builder.toString();
  }

  private String prettyType(Type type) {
    if (type == BYTE_OR_BOOL_TYPE) {
      return "<byte|bool>";
    }
    if (type == ARRAY_TYPE) {
      return type.getElementType().getInternalName();
    }
    if (type == REFERENCE_TYPE || type == OBJECT_TYPE || type == NULL_TYPE) {
      return type.getInternalName();
    }
    return DescriptorUtils.descriptorToJavaType(type.getDescriptor());
  }
}
