/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import java.util.*;
import javax.annotation.Nullable;
import net.starlark.java.annot.internal.BcOpcodeHandler;
import net.starlark.java.annot.internal.BcOpcodeNumber;
import net.starlark.java.syntax.*;

/** Bytecode interpreter. Takes a compiled function body and returns a result. */
class BcEval {

  private static final TokenKind[] TOKENS = TokenKind.values();

  final StarlarkThread thread;
  private final StarlarkThread.Frame fr;
  private final StarlarkFunction fn;
  private final BcCompiled compiled;

  /** Registers. */
  private final Object[] slots;

  // These variables hold iterator data for the loops.
  //
  // Each iteration is represented by a pair of objects and pair of ints,
  // stored respectively in `loops` and `loopInts` arrays.
  //
  // Iterations is done for either:
  // * array-based collection (list or tuple)
  // * anything else
  //
  // For default iteration (of anything except list or tuple), `loops` array pair
  // is a (collection, iterator). Int array is not used.
  //
  // For array-based iteration,
  // * `loops` array contains a pair of (collection, collection data array)
  // * `loopInts` array contains a pair of (collection size, current index)
  private final Object[] loops;
  private final int[] loopInts;

  /** Current loop depth. */
  private int loopDepth = 0;

  /** Program text. */
  final int[] text;

  /** Current instruction pointer */
  int currentIp;

  /** Instruction pointer while decoding operands */
  int ip = 0;

  /** Number of instructions executed in this function. */
  int localSteps = 0;

  private BcEval(
      StarlarkThread thread, StarlarkThread.Frame fr, StarlarkFunction fn, Object[] locals) {
    this.thread = thread;
    this.fr = fr;
    this.fn = fn;
    this.compiled = fn.compiled;
    this.slots = locals;
    this.loops = ArraysForStarlark.newObjectArray(fn.compiled.loopDepth * 2);
    this.loopInts = ArraysForStarlark.newIntArray(fn.compiled.loopDepth * 2);
    this.text = fn.compiled.text;

    fr.bcEval = this;
  }

  /** Public API. */
  public static Object eval(
      StarlarkThread thread, StarlarkThread.Frame fr, StarlarkFunction fn, Object[] locals)
      throws InterruptedException, EvalException {
    BcEval bcEval = new BcEval(thread, fr, fn, locals);
    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.enter(StarlarkRuntimeStats.WhereWeAre.BC_EVAL);
    }
    try {
      return bcEval.eval();
    } finally {
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.leaveStarlarkCall(fn.getName(), bcEval.localSteps);
      }
    }
  }

  private Object eval() throws EvalException, InterruptedException {
    try {
      return BcEvalDispatch.run(this);
    } finally {
      while (loopDepth != 0) {
        popFor();
      }
    }
  }

  @Nullable private Location errorLocation;

  Location location() {
    return errorLocation != null ? errorLocation : compiled.locationAt(currentIp);
  }

  /** Pop one for statement. */
  private void popFor() {
    StarlarkIterable<?> iterable = (StarlarkIterable<?>) loops[(loopDepth - 1) * 2];
    if (iterable != null) {
      // Iterable is not initialized (null) for tuple
      // because there's no iterator counter for tuple.
      EvalUtils.removeIterator(iterable);
      loops[(loopDepth - 1) * 2] = null;
    }
    loops[(loopDepth - 1) * 2 + 1] = null;
    --loopDepth;
  }

  /** Next instruction operand. */
  private int nextOperand() {
    return text[ip++];
  }

  private EvalException referencedBeforeAssignment(Resolver.Scope scope, String name) {
    return Starlark.errorf("%s variable '%s' is referenced before assignment.", scope, name);
  }

  /** Get a value from the register slot. */
  private Object getSlot(int slot) throws EvalException {
    int kind = slot >> BcSlot.INDEX_BITS;
    int index = slot & ~BcSlot.MASK;
    switch (kind) {
      case BcSlot.LOCAL_KIND:
        return getLocal(index);
      case BcSlot.CONST_KIND:
        return compiled.constSlots[index];
      case BcSlot.GLOBAL_KIND:
        return getGlobal(index);
      case BcSlot.CELL_KIND:
        return getCell(index);
      case BcSlot.FREE_KIND:
        return getFree(index);
      default:
        throw new IllegalStateException("wrong slot: " + slot);
    }
  }

  /** Get a value from the local slot. */
  private Object getLocal(int index) throws EvalException {
    Object value = slots[index];
    if (value == null) {
      throwLocalNotFound(index);
    }
    return value;
  }

  private void throwLocalNotFound(int index) throws EvalException {
    if (index < fn.compiled.getLocals().size()) {
      Resolver.Binding binding = fn.compiled.getLocals().get(index);
      throw referencedBeforeAssignment(binding.getScope(), binding.getName());
    } else {
      // Now this is always IllegalStateException,
      // but it should be also EvalException when we store locals in registers.
      throw new IllegalStateException("slot value is undefined: " + index);
    }
  }

  private Object getGlobal(int index) throws EvalException {
    Object value = fn.getModule().getGlobalByIndex(index);
    if (value == null) {
      String name = fn.getModule().getResolverModule().getGlobalNameByIndexSlow(index);
      throw referencedBeforeAssignment(Resolver.Scope.GLOBAL, name);
    }
    return value;
  }

  private Object getFree(int index) throws EvalException {
    Object value = fn.getFreeVar(index).x;
    if (value == null) {
      String name = fn.compiled.getFreeVars().get(index).getName();
      throw referencedBeforeAssignment(Resolver.Scope.FREE, name);
    }
    return value;
  }

  private Object getCell(int index) throws EvalException {
    Object value = ((StarlarkFunction.Cell) slots[index]).x;
    if (value == null) {
      String name = fn.compiled.getLocals().get(index).getName();
      throw referencedBeforeAssignment(Resolver.Scope.FREE, name);
    }
    return value;
  }

  /** Get argument with special handling of {@link BcSlot#NULL_FLAG}. */
  @Nullable
  private Object getSlotOrNull(int slot) throws EvalException {
    return slot != BcSlot.NULL_FLAG ? getSlot(slot) : null;
  }

  /** Get argument with special handling of {@link BcSlot#NULL_FLAG}. */
  @Nullable
  private Object getSlotNullAsNone(int slot) throws EvalException {
    return slot != BcSlot.NULL_FLAG ? getSlot(slot) : Starlark.NONE;
  }

  private void setSlot(int slot, Object value) {
    slots[slot] = value;
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.CP)
  void cp() throws EvalException {
    int fromSlot = nextOperand();
    int resultSlot = nextOperand();
    Object value = getSlot(fromSlot);
    setSlot(resultSlot, value);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.CP_LOCAL)
  void cpLocal() throws EvalException {
    int fromSlot = nextOperand();
    int resultSlot = nextOperand();
    Object value = getLocal(fromSlot);
    setSlot(resultSlot, value);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.SET_GLOBAL)
  void setGlobal() throws EvalException {
    Object value = getSlot(nextOperand());
    int globalVarIndex = nextOperand();
    int nameConstantIndex = nextOperand();
    boolean postAssignHook = nextOperand() != 0;
    fn.getModule().setGlobalByIndex(globalVarIndex, value);
    if (postAssignHook) {
      if (thread.postAssignHook != null) {
        if (fn.isToplevel()) {
          String name = compiled.strings[nameConstantIndex];
          thread.postAssignHook.assign(name, value);
        }
      }
    }
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.SET_CELL)
  void setCell() throws EvalException {
    Object value = getSlot(nextOperand());
    int cellIndex = nextOperand();
    ((StarlarkFunction.Cell) slots[cellIndex]).x = value;
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.LOAD_STMT)
  void loadStmt() throws EvalException, InterruptedException {
    LoadStatement statement = (LoadStatement) compiled.objects[nextOperand()];
    Eval.execLoad(thread, fr, slots, statement);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.SET_INDEX)
  void setIndex() throws EvalException {
    Object dict = getSlot(nextOperand());
    Object key = getSlot(nextOperand());
    Object value = getSlot(nextOperand());
    EvalUtils.setIndex(dict, key, value);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.LIST_APPEND)
  @SuppressWarnings("unchecked")
  void listAppend() throws EvalException {
    StarlarkList<Object> list = (StarlarkList<Object>) getSlot(nextOperand());
    Object item = getSlot(nextOperand());
    list.addElement(item);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.RETURN)
  Object returnInstr() throws EvalException {
    Object result = getSlotNullAsNone(nextOperand());
    validateInstructionDecodedCorrectly();
    return result;
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.NEW_FUNCTION)
  void newFunction() throws EvalException, InterruptedException {
    Resolver.Function fn = (Resolver.Function) compiled.objects[nextOperand()];
    Tuple parameterDefaults = Tuple.wrap(nextNSlotsListSharedArray());
    int result = nextOperand();
    StarlarkFunction starlarkFunction = Eval.newFunction(thread, fr, slots, fn, parameterDefaults);
    setSlot(result, starlarkFunction);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.BR, mayJump = true)
  void br() {
    int dest = nextOperand();
    goTo(dest);
  }

  /** If condition is true, set the instruction pointer to a given value. */
  private void goToIf(boolean cond, int dest) {
    if (cond) {
      goTo(dest);
    } else {
      validateInstructionDecodedCorrectly();
    }
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.IF_BR_LOCAL, mayJump = true)
  void ifBrLocal() throws EvalException {
    int condSlot = nextOperand();
    int dest = nextOperand();
    Object cond = getLocal(condSlot);
    goToIf(Starlark.truth(cond), dest);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.IF_NOT_BR_LOCAL, mayJump = true)
  void ifNotBrLocal() throws EvalException {
    int condSlot = nextOperand();
    int dest = nextOperand();
    Object cond = getLocal(condSlot);
    goToIf(!Starlark.truth(cond), dest);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.IF_TYPE_IS_BR, mayJump = true)
  void ifTypeIsBr() throws EvalException {
    int valueSlot = nextOperand();
    int stringIndex = nextOperand();
    int dest = nextOperand();
    Object value = getSlot(valueSlot);
    String type = compiled.strings[stringIndex];
    goToIf(EvalUtils.typeIs(value, type), dest);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.IF_NOT_TYPE_IS_BR, mayJump = true)
  void ifNotTypeIsBr() throws EvalException {
    int valueSlot = nextOperand();
    int stringIndex = nextOperand();
    int dest = nextOperand();
    Object value = getSlot(valueSlot);
    String type = compiled.strings[stringIndex];
    goToIf(!EvalUtils.typeIs(value, type), dest);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.IF_EQ_BR, mayJump = true)
  void ifEqBr() throws EvalException {
    int aSlot = nextOperand();
    int bSlot = nextOperand();
    int dest = nextOperand();
    Object a = getSlot(aSlot);
    Object b = getSlot(bSlot);
    goToIf(EvalUtils.equal(a, b), dest);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.IF_NOT_EQ_BR, mayJump = true)
  void ifNotEqBr() throws EvalException {
    int aSlot = nextOperand();
    int bSlot = nextOperand();
    int dest = nextOperand();
    Object a = getSlot(aSlot);
    Object b = getSlot(bSlot);
    goToIf(!EvalUtils.equal(a, b), dest);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.IF_IN_BR, mayJump = true)
  void ifInBr() throws EvalException {
    int aSlot = nextOperand();
    int bSlot = nextOperand();
    int dest = nextOperand();
    Object a = getSlot(aSlot);
    Object b = getSlot(bSlot);
    goToIf(EvalUtils.binaryIn(a, b, thread.getSemantics()), dest);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.IF_NOT_IN_BR, mayJump = true)
  void ifNotInBr() throws EvalException {
    int aSlot = nextOperand();
    int bSlot = nextOperand();
    int dest = nextOperand();
    Object a = getSlot(aSlot);
    Object b = getSlot(bSlot);
    goToIf(!EvalUtils.binaryIn(a, b, thread.getSemantics()), dest);
  }

  /** Set the instruction pointer. */
  private void goTo(int addr) {
    validateInstructionDecodedCorrectly();
    ip = addr;
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.FOR_INIT, mayJump = true)
  void forInit() throws EvalException {
    Object value = getSlot(nextOperand());
    int nextValueSlot = nextOperand();
    int end = nextOperand();

    // See the documentation of `loops` field for the explanation
    // of this logic.
    Object item;
    if (value instanceof StarlarkList<?>) {
      StarlarkList<?> list = (StarlarkList<?>) value;
      int size = list.size();
      if (size == 0) {
        goTo(end);
        return;
      }
      EvalUtils.addIterator(list);
      Object[] elems = list.getElemsUnsafe();
      loops[loopDepth * 2] = list;
      loops[loopDepth * 2 + 1] = elems;
      loopInts[loopDepth * 2] = size;
      loopInts[loopDepth * 2 + 1] = 1;
      item = elems[0];
    } else if (value instanceof Tuple) {
      Tuple tuple = (Tuple) value;
      Object[] elems = tuple.getElemsUnsafe();
      if (elems.length == 0) {
        goTo(end);
        return;
      }
      // skipping addIterator for tuple, it is no-op
      loops[loopDepth * 2 + 1] = elems;
      loopInts[loopDepth * 2] = elems.length;
      loopInts[loopDepth * 2 + 1] = 1;
      item = elems[0];
    } else {
      StarlarkIterable<?> seq = Starlark.toIterable(value);
      Iterator<?> iterator = seq.iterator();
      if (!iterator.hasNext()) {
        goTo(end);
        return;
      }
      EvalUtils.addIterator(seq);
      loops[loopDepth * 2] = seq;
      loops[loopDepth * 2 + 1] = iterator;
      item = iterator.next();
    }

    ++loopDepth;

    setSlot(nextValueSlot, item);
    validateInstructionDecodedCorrectly();
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.CONTINUE, mayJump = true)
  void continueInstr() throws InterruptedException {
    int nextValueSlot = nextOperand();
    int b = nextOperand();
    int e = nextOperand();

    thread.checkInterrupt();

    // After then continue iteration we are branching to either
    // `b` or `e` label.
    int gotoAddr;

    // See the documentation of `loops` field for the explanation
    // of this logic.
    Object iteratorObject = loops[(loopDepth - 1) * 2 + 1];
    if (iteratorObject.getClass() == Object[].class) {
      Object[] elems = (Object[]) iteratorObject;
      int currentIndex = loopInts[(loopDepth - 1) * 2 + 1];
      int size = loopInts[(loopDepth - 1) * 2];
      if (currentIndex != size) {
        // has next
        Object next = elems[loopInts[(loopDepth - 1) * 2 + 1]++];
        setSlot(nextValueSlot, next);
        gotoAddr = b;
      } else {
        popFor();
        gotoAddr = e;
      }
    } else {
      Iterator<?> iterator = (Iterator<?>) iteratorObject;
      if (iterator.hasNext()) {
        setSlot(nextValueSlot, iterator.next());
        gotoAddr = b;
      } else {
        popFor();
        gotoAddr = e;
      }
    }
    goTo(gotoAddr);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.BREAK, mayJump = true)
  void breakInstr() {
    int e = nextOperand();
    popFor();
    validateInstructionDecodedCorrectly();
    ip = e;
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.UNPACK)
  void unpack() throws EvalException {
    Object x = getSlot(nextOperand());
    int nrhs = Starlark.len(x);
    int nlhs = nextOperand();
    if (nrhs < 0 || x instanceof String) {
      throw Starlark.errorf(
          "got '%s' in sequence assignment (want %d-element sequence)", Starlark.type(x), nlhs);
    }
    if (nrhs != nlhs) {
      throw Starlark.errorf(
          "too %s values to unpack (got %d, want %d)", nrhs < nlhs ? "few" : "many", nrhs, nlhs);
    }
    Iterable<?> rhs = Starlark.toIterable(x); // fails if x is a string
    for (Object item : rhs) {
      setSlot(nextOperand(), item);
    }
  }

  private Object[] nextNSlots(int size) throws EvalException {
    if (size == 0) {
      return ArraysForStarlark.EMPTY_OBJECT_ARRAY;
    }
    int[] text = this.text;
    int ip = this.ip;
    Object[] array = new Object[size];
    for (int j = 0; j != array.length; ++j) {
      array[j] = getSlot(text[ip++]);
    }
    this.ip = ip;
    return array;
  }

  /** Next N slots returning unshared array. */
  private Object[] nextNSlotsListUnsharedArray() throws EvalException {
    int size = nextOperand();
    if (size < 0) {
      return ((Object[]) compiled.objects[BcSlot.negativeSizeToObjectIndex(size)]).clone();
    }
    return nextNSlots(size);
  }

  /** Next N slots returning maybe shared array. */
  private Object[] nextNSlotsListSharedArray() throws EvalException {
    int size = nextOperand();
    if (size < 0) {
      return (Object[]) compiled.objects[BcSlot.negativeSizeToObjectIndex(size)];
    }
    return nextNSlots(size);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.LIST)
  void list() throws EvalException {
    Object[] data = nextNSlotsListUnsharedArray();
    StarlarkList<?> result = StarlarkList.wrap(thread.mutability(), data);
    setSlot(nextOperand(), result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.TUPLE)
  void tuple() throws EvalException {
    Object[] data = nextNSlotsListSharedArray();
    Tuple result = Tuple.wrap(data);
    setSlot(nextOperand(), result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.DICT)
  void dict() throws EvalException {
    int size = nextOperand();
    Dict<?, ?> result;
    if (size == 0) {
      result = Dict.of(thread.mutability());
    } else {
      DictMap<Object, Object> lhm = new DictMap<>(size);
      for (int j = 0; j != size; ++j) {
        Object key = getSlot(nextOperand());
        Starlark.checkHashable(key);
        Object value = getSlot(nextOperand());
        Object prev = lhm.putNoResize(key, value);
        if (prev != null) {
          throw new EvalException("dictionary expression has duplicate key: " + Starlark.repr(key));
        }
      }
      result = Dict.wrap(thread.mutability(), lhm);
    }
    setSlot(nextOperand(), result);
  }

  /** Dot operator. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.DOT)
  void dot() throws EvalException, InterruptedException {
    int selfSlot = nextOperand();
    int siteIndex = nextOperand();
    int resultSlot = nextOperand();

    Object self = getSlot(selfSlot);
    BcDotSite site = (BcDotSite) compiled.objects[siteIndex];
    Object result = site.getattr(thread, self);
    setSlot(resultSlot, result);
  }

  /** Index operator. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.INDEX)
  void index() throws EvalException {
    Object object = getSlot(nextOperand());
    Object index = getSlot(nextOperand());
    setSlot(
        nextOperand(), EvalUtils.index(thread.mutability(), thread.getSemantics(), object, index));
  }

  /** Slice operator. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.SLICE)
  void slice() throws EvalException {
    Object object = getSlot(nextOperand());
    Object start = getSlotNullAsNone(nextOperand());
    Object stop = getSlotNullAsNone(nextOperand());
    Object step = getSlotNullAsNone(nextOperand());
    setSlot(nextOperand(), Starlark.slice(thread.mutability(), object, start, stop, step));
  }

  private StarlarkCallable nextCallable() throws EvalException {
    return BcCall.callable(getSlot(nextOperand()));
  }

  private BcDynCallSite nextDynCallSite() {
    BcDynCallSite callSite = (BcDynCallSite) compiled.objects[nextOperand()];
    return callSite;
  }

  /** Call operator. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.CALL)
  void call() throws EvalException, InterruptedException {
    thread.checkInterrupt();

    StarlarkCallable fn = nextCallable();
    BcDynCallSite callSite = nextDynCallSite();
    Object[] args = nextNSlotsListSharedArray();
    Object star = getSlotOrNull(nextOperand());
    Object starStar = getSlotOrNull(nextOperand());

    if (star != null) {
      if (!(star instanceof Sequence)) {
        errorLocation = callSite.callLocs.starLocation(compiled.getFileLocations());
        throw new EvalException("argument after * must be an iterable, not " + Starlark.type(star));
      }
    }

    if (starStar != null) {
      if (!(starStar instanceof Dict)) {
        errorLocation = callSite.callLocs.starStarLocation(compiled.getFileLocations());
        throw new EvalException("argument after ** must be a dict, not " + Starlark.type(starStar));
      }
    }

    Object result =
        BcCall.linkAndCallCs(thread, fn, callSite, args, (Sequence<?>) star, (Dict<?, ?>) starStar);

    setSlot(nextOperand(), result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.CALL_1)
  void call1() throws EvalException, InterruptedException {
    thread.checkInterrupt();

    StarlarkCallable fn = nextCallable();
    BcDynCallSite callSite = nextDynCallSite();

    Object arg0 = getSlot(nextOperand());
    Object result = BcCall.linkAndCall1Cs(thread, fn, callSite, arg0);

    setSlot(nextOperand(), result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.CALL_2)
  void call2() throws InterruptedException, EvalException {
    thread.checkInterrupt();

    StarlarkCallable fn = nextCallable();
    BcDynCallSite callSite = nextDynCallSite();

    Object arg0 = getSlot(nextOperand());
    Object arg1 = getSlot(nextOperand());
    Object result = BcCall.linkAndCall2Cs(thread, fn, callSite, arg0, arg1);

    setSlot(nextOperand(), result);
  }

  private BcCallLocs nextCallLocs() {
    return (BcCallLocs) compiled.objects[nextOperand()];
  }

  private StarlarkCallableLinked nextCallableLinked() {
    return (StarlarkCallableLinked) compiled.objects[nextOperand()];
  }

  /** Call after successful link at compile time. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.CALL_LINKED)
  void callLinked() throws EvalException, InterruptedException {
    thread.checkInterrupt();

    BcCallLocs locs = nextCallLocs();
    StarlarkCallableLinked fn = nextCallableLinked();

    Object[] args = nextNSlotsListSharedArray();

    Object star = getSlotOrNull(nextOperand());
    Object starStar = getSlotOrNull(nextOperand());

    if (star != null && !(star instanceof Sequence<?>)) {
      errorLocation = locs.starLocation(compiled.getFileLocations());
      throw new EvalException(
          String.format("argument after * must be an iterable, not %s", Starlark.type(star)));
    }
    if (starStar != null && !(starStar instanceof Dict<?, ?>)) {
      errorLocation = locs.starStarLocation(compiled.getFileLocations());
      throw new EvalException(
          String.format("argument after ** must be a dict, not %s", Starlark.type(starStar)));
    }

    Object result = BcCall.callLinked(thread, fn, args, (Sequence<?>) star, (Dict<?, ?>) starStar);
    setSlot(nextOperand(), result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.CALL_LINKED_1)
  void callLinked1() throws InterruptedException, EvalException {
    thread.checkInterrupt();

    nextCallLocs();
    StarlarkCallableLinked fn = nextCallableLinked();

    Object arg0 = getSlot(nextOperand());

    Object result = BcCall.callLinked1(thread, fn, arg0);
    setSlot(nextOperand(), result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.CALL_LINKED_2)
  void callLinked2() throws InterruptedException, EvalException {
    thread.checkInterrupt();

    nextCallLocs();
    StarlarkCallableLinked fn = nextCallableLinked();

    int arg0Slot = nextOperand();
    int arg1Slot = nextOperand();
    Object arg0 = getSlot(arg0Slot);
    Object arg1 = getSlot(arg1Slot);

    Object result = BcCall.callLinked2(thread, fn, arg0, arg1);
    setSlot(nextOperand(), result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.CALL_CACHED)
  void callCached() throws EvalException, InterruptedException {
    int callCachedIndex = nextOperand();
    int resultSlot = nextOperand();
    BcCallCached callCached = (BcCallCached) compiled.objects[callCachedIndex];
    Object result = callCached.call(thread);
    setSlot(resultSlot, result);
  }

  /** Not operator. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.NOT)
  void not() throws EvalException {
    Object value = getSlot(nextOperand());
    setSlot(nextOperand(), !Starlark.truth(value));
  }

  /** Generic unary operator. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.UNARY)
  void unary() throws EvalException {
    Object value = getSlot(nextOperand());
    TokenKind op = TOKENS[nextOperand()];
    setSlot(nextOperand(), EvalUtils.unaryOp(op, value, thread.getSemantics()));
  }

  /**
   * Generic binary operator
   *
   * <p>Note that {@code and} and {@code or} are not emitted as binary operator instruction. .
   */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.BINARY)
  void binary() throws EvalException {
    Object x = getSlot(nextOperand());
    Object y = getSlot(nextOperand());
    TokenKind op = TOKENS[nextOperand()];
    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.recordBinaryOp(op);
    }
    setSlot(nextOperand(), EvalUtils.binaryOp(op, x, y, thread));
  }

  /** {@code +=} operator. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.PLUS_IN_PLACE)
  void plusInPlace() throws EvalException {
    int lhs = nextOperand();
    int rhs = nextOperand();
    int result = nextOperand();
    Object x = getSlot(lhs);
    Object y = getSlot(rhs);
    setSlot(result, Eval.inplaceBinaryPlus(thread, x, y));
  }

  /** {@code "aaa%sbbb" % string} */
  private String percentSOneImplStringArg(String format, int percent, String arg) {
    StringBuilder sb = new StringBuilder(format.length() - 2 + arg.length());
    sb.append(format, 0, percent);
    sb.append(arg);
    sb.append(format, percent + 2, format.length());
    return sb.toString();
  }

  /** {@code "aaa%sbbb" % (arg,)} */
  private String percentSOneImplObject(String format, int percent, Object arg) {
    if (arg instanceof String) {
      return percentSOneImplStringArg(format, percent, (String) arg);
    } else {
      Printer printer = new Printer();
      printer.append(format, 0, percent);
      printer.str(arg);
      printer.append(format, percent + 2, format.length());
      return printer.toString();
    }
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.PERCENT_S_ONE)
  void percentSOne() throws EvalException {
    int formatIndex = nextOperand();
    int percent = nextOperand();
    int argIndex = nextOperand();
    int out = nextOperand();

    String format = compiled.strings[formatIndex];
    Object arg = getSlot(argIndex);

    String result;
    if (arg instanceof String) {
      result = percentSOneImplStringArg(format, percent, (String) arg);
    } else if (arg instanceof Tuple) {
      if (((Tuple) arg).size() == 1) {
        Object item0 = ((Tuple) arg).get(0);
        result = percentSOneImplObject(format, percent, item0);
      } else {
        // let it fail
        Starlark.format(format, arg);
        throw new IllegalStateException("unreachable");
      }
    } else {
      result = percentSOneImplObject(format, percent, arg);
    }

    setSlot(out, result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.PERCENT_S_ONE_TUPLE)
  void percentSOneTuple() throws EvalException {
    int formatIndex = nextOperand();
    int percent = nextOperand();
    int argIndex = nextOperand();
    int out = nextOperand();

    String format = compiled.strings[formatIndex];
    Object arg = getSlot(argIndex);

    String result = percentSOneImplObject(format, percent, arg);

    setSlot(out, result);
  }

  /** {@code +=} operator where operators are likely strings. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.PLUS_STRING_IN_PLACE)
  void plusStringInPlace() throws EvalException {
    int lhs = nextOperand();
    int rhs = nextOperand();
    int resultSlot = nextOperand();
    Object x = getSlot(lhs);
    Object y = getSlot(rhs);
    Object result;
    if (x instanceof String && y instanceof String) {
      result = (String) x + (String) y;
    } else {
      result = Eval.inplaceBinaryPlus(thread, x, y);
    }
    setSlot(resultSlot, result);
  }

  /** {@code x += [...]}. */
  @SuppressWarnings("unchecked")
  @BcOpcodeHandler(opcode = BcOpcodeNumber.PLUS_LIST_IN_PLACE)
  void plusListInPlace() throws EvalException {
    Object x = getSlot(nextOperand());
    Object result;
    if (x instanceof StarlarkList<?>) {
      Object[] rhsValues = nextNSlotsListSharedArray();
      ((StarlarkList<Object>) x).addElements(rhsValues);
      result = x;
    } else {
      Object[] rhsValues = nextNSlotsListUnsharedArray();
      result = Eval.inplaceBinaryPlus(thread, x, StarlarkList.wrap(thread.mutability(), rhsValues));
    }
    int resultSlot = nextOperand();
    setSlot(resultSlot, result);
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.TYPE_IS)
  void typeIs() throws EvalException {
    int lhsSlot = nextOperand();
    int typeIndex = nextOperand();
    int resultSlot = nextOperand();

    Object lhs = getLocal(lhsSlot);
    String type = compiled.strings[typeIndex];

    boolean result = EvalUtils.typeIs(lhs, type);
    setSlot(resultSlot, result);
  }

  /** Equality. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.EQ)
  void eq() throws EvalException {
    Object lhs = getSlot(nextOperand());
    Object rhs = getSlot(nextOperand());
    setSlot(nextOperand(), EvalUtils.equal(lhs, rhs));
  }

  /** Equality. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.NOT_EQ)
  void notEq() throws EvalException {
    Object lhs = getSlot(nextOperand());
    Object rhs = getSlot(nextOperand());
    setSlot(nextOperand(), !EvalUtils.equal(lhs, rhs));
  }

  /** a + b. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.PLUS)
  void plus() throws EvalException {
    int lhsSlot = nextOperand();
    int rhsSlot = nextOperand();
    int resultSlot = nextOperand();
    Object lhs = getSlot(lhsSlot);
    Object rhs = getSlot(rhsSlot);
    setSlot(resultSlot, EvalUtils.binaryPlus(lhs, rhs, thread));
  }

  /** a + b where a and b are likely strings. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.PLUS_STRING)
  void plusString() throws EvalException {
    int lhsSlot = nextOperand();
    int rhsSlot = nextOperand();
    int resultSlot = nextOperand();
    Object lhs = getSlot(lhsSlot);
    Object rhs = getSlot(rhsSlot);
    Object result;
    if (lhs instanceof String && rhs instanceof String) {
      result = (String) lhs + (String) rhs;
    } else {
      result = EvalUtils.binaryPlus(lhs, rhs, thread);
    }
    setSlot(resultSlot, result);
  }

  /** a + b where b is a {@link BcInstrOperand#IN_LIST}. */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.PLUS_LIST)
  void plusList() throws EvalException {
    Object lhs = getSlot(nextOperand());
    Object result;
    if (lhs instanceof StarlarkList<?>) {
      Object[] rhs = nextNSlotsListSharedArray();
      result = StarlarkList.concat((StarlarkList<?>) lhs, rhs, thread.mutability());
    } else {
      Object[] rhs = nextNSlotsListUnsharedArray();
      result = EvalUtils.binaryPlus(lhs, StarlarkList.wrap(thread.mutability(), rhs), thread);
    }
    int resultSlot = nextOperand();
    setSlot(resultSlot, result);
  }

  /** {@code a in b} */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.IN)
  void binaryIn() throws EvalException {
    int lhsSlot = nextOperand();
    int rhsSlot = nextOperand();
    int resultSlot = nextOperand();
    Object lhs = getSlot(lhsSlot);
    Object rhs = getSlot(rhsSlot);
    setSlot(resultSlot, EvalUtils.binaryIn(lhs, rhs, thread.getSemantics()));
  }

  /** {@code a not in b} */
  @BcOpcodeHandler(opcode = BcOpcodeNumber.NOT_IN)
  void binaryNotIn() throws EvalException {
    int lhsSlot = nextOperand();
    int rhsSlot = nextOperand();
    int resultSlot = nextOperand();
    Object lhs = getSlot(lhsSlot);
    Object rhs = getSlot(rhsSlot);
    setSlot(resultSlot, !EvalUtils.binaryIn(lhs, rhs, thread.getSemantics()));
  }

  @BcOpcodeHandler(opcode = BcOpcodeNumber.EVAL_EXCEPTION)
  void evalException() throws EvalException {
    String message = compiled.strings[nextOperand()];
    validateInstructionDecodedCorrectly();
    throw new EvalException(message);
  }

  EvalException otherOpcode() {
    int opcode = text[currentIp];
    if (opcode < BcInstrOpcode.values().length) {
      throw new IllegalStateException("not implemented opcode: " + BcInstrOpcode.values()[opcode]);
    } else {
      throw new IllegalStateException("wrong opcode: " + opcode);
    }
  }

  void validateInstructionDecodedCorrectly() {
    if (StarlarkAssertions.ENABLED) {
      // Validate the last instruction was decoded correctly
      // (got all the argument, and no extra arguments).
      // This is quite helpful, but expensive assertion, only enabled when bytecode assertions
      // are on.
      BcInstrOpcode opcode = BcInstrOpcode.values()[text[currentIp]];
      int prevInstrLen = compiled.instrLenAt(currentIp);
      Preconditions.checkState(
          ip == currentIp + prevInstrLen,
          "Instruction %s incorrectly handled len; expected len: %s, actual len: %s",
          opcode,
          prevInstrLen,
          ip - currentIp);
    }
  }
}
