package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import net.starlark.java.syntax.*;

import javax.annotation.Nullable;
import java.util.*;

/** Bytecode interpreter. Takes a compiled function body and returns a result. */
class BcEval {

  private static final TokenKind[] TOKENS = TokenKind.values();

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
  private final int[] text;

  /** Current instruction pointer */
  private int currentIp;

  /** Instruction pointer while decoding operands */
  private int ip = 0;

  /** Number of instructions executed in this function. */
  private int localSteps = 0;

  private BcEval(StarlarkThread.Frame fr, StarlarkFunction fn, Object[] locals) {
    this.fr = fr;

    this.fn = fn;
    this.compiled = fn.compiled;
    this.slots = locals;
    this.loops = ArraysForStarlark.newObjectArray(fn.compiled.loopDepth * 2);
    this.loopInts = ArraysForStarlark.newIntArray(fn.compiled.loopDepth * 2);
    this.text = fn.compiled.text;
  }

  /** Public API. */
  public static Object eval(StarlarkThread.Frame fr, StarlarkFunction fn, Object[] locals)
      throws InterruptedException, EvalException {
    BcEval bcEval = new BcEval(fr, fn, locals);
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
      while (ip != text.length) {
        if (++fr.thread.steps >= fr.thread.stepLimit) {
          throw new EvalException("Starlark computation cancelled: too many steps");
        }

        currentIp = ip;

        // Each instruction is:
        // * opcode
        // * operands which depend on opcode
        int opcode = text[ip++];

        if (StarlarkRuntimeStats.ENABLED) {
          StarlarkRuntimeStats.recordInst(opcode);
          ++localSteps;
        }

        switch (opcode) {
          case BcInstr.CP:
            cp();
            break;
          case BcInstr.CP_LOCAL:
            cpLocal();
            break;
          case BcInstr.EQ:
            eq();
            break;
          case BcInstr.NOT_EQ:
            notEq();
            break;
          case BcInstr.PLUS:
            plus();
            break;
          case BcInstr.PLUS_STRING:
            plusString();
            break;
          case BcInstr.PLUS_LIST:
            plusList();
            break;
          case BcInstr.IN:
            binaryIn();
            break;
          case BcInstr.NOT_IN:
            binaryNotIn();
            break;
          case BcInstr.NOT:
            not();
            break;
          case BcInstr.UNARY:
            unary();
            break;
          case BcInstr.BINARY:
            binary();
            break;
          case BcInstr.PLUS_IN_PLACE:
            plusInPlace();
            break;
          case BcInstr.PERCENT_S_ONE:
            percentSOne();
            break;
          case BcInstr.PERCENT_S_ONE_TUPLE:
            percentSOneTuple();
            break;
          case BcInstr.PLUS_STRING_IN_PLACE:
            plusStringInPlace();
            break;
          case BcInstr.PLUS_LIST_IN_PLACE:
            plusListInPlace();
            break;
          case BcInstr.TYPE_IS:
            typeIs();
            break;
          case BcInstr.BR:
            br();
            continue;
          case BcInstr.IF_BR_LOCAL:
            ifBrLocal();
            continue;
          case BcInstr.IF_NOT_BR_LOCAL:
            ifNotBrLocal();
            continue;
          case BcInstr.DOT:
            dot();
            break;
          case BcInstr.INDEX:
            index();
            break;
          case BcInstr.SLICE:
            slice();
            break;
          case BcInstr.CALL:
            call();
            break;
          case BcInstr.CALL_LINKED:
            callLinked();
            break;
          case BcInstr.CALL_CACHED:
            callCached();
            break;
          case BcInstr.RETURN:
            return returnInstr();
          case BcInstr.NEW_FUNCTION:
            newFunction();
            break;
          case BcInstr.TUPLE:
            tuple();
            break;
          case BcInstr.LIST:
            list();
            break;
          case BcInstr.DICT:
            dict();
            break;
          case BcInstr.UNPACK:
            unpack();
            break;
          case BcInstr.SET_GLOBAL:
            setGlobal();
            break;
          case BcInstr.SET_CELL:
            setCell();
            break;
          case BcInstr.FOR_INIT:
            forInit();
            continue;
          case BcInstr.BREAK:
            breakInstr();
            continue;
          case BcInstr.CONTINUE:
            continueInstr();
            continue;
          case BcInstr.LIST_APPEND:
            listAppend();
            break;
          case BcInstr.SET_INDEX:
            setIndex();
            break;
          case BcInstr.LOAD_STMT:
            loadStmt();
            break;
          case BcInstr.EVAL_EXCEPTION:
            evalException();
            continue;
          default:
            throw otherOpcode(opcode);
        }

        validateInstructionDecodedCorrectly();

      }
    } catch (Throwable e) {
      try {
        fr.setLocation(compiled.locationAt(currentIp));
      } catch (Throwable ignore) {
        // do not crash if we are already crashing
      }
      throw e;
    } finally {
      while (loopDepth != 0) {
        popFor();
      }
    }
    return Starlark.NONE;
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
    return Starlark.errorf(
        "%s variable '%s' is referenced before assignment.", scope, name);
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
      fr.setErrorLocation(fr.getLocation());
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

  private void cp() throws EvalException {
    int fromSlot = nextOperand();
    int resultSlot = nextOperand();
    Object value = getSlot(fromSlot);
    setSlot(resultSlot, value);
  }

  private void cpLocal() throws EvalException {
    int fromSlot = nextOperand();
    int resultSlot = nextOperand();
    Object value = getLocal(fromSlot);
    setSlot(resultSlot, value);
  }

  private void setGlobal() throws EvalException {
    Object value = getSlot(nextOperand());
    int globalVarIndex = nextOperand();
    int nameConstantIndex = nextOperand();
    boolean postAssignHook = nextOperand() != 0;
    fn.getModule().setGlobalByIndex(globalVarIndex, value);
    if (postAssignHook) {
      if (fr.thread.postAssignHook != null) {
        if (fn.isToplevel()) {
          String name = compiled.strings[nameConstantIndex];
          fr.thread.postAssignHook.assign(name, value);
        }
      }
    }
  }

  private void setCell() throws EvalException {
    Object value = getSlot(nextOperand());
    int cellIndex = nextOperand();
    ((StarlarkFunction.Cell) slots[cellIndex]).x = value;
  }

  private void loadStmt() throws EvalException, InterruptedException {
    LoadStatement statement = (LoadStatement) compiled.objects[nextOperand()];
    Eval.execLoad(fr, slots, statement);
  }

  private void setIndex() throws EvalException {
    Object dict = getSlot(nextOperand());
    Object key = getSlot(nextOperand());
    Object value = getSlot(nextOperand());
    EvalUtils.setIndex(dict, key, value);
  }

  @SuppressWarnings("unchecked")
  private void listAppend() throws EvalException {
    StarlarkList<Object> list = (StarlarkList<Object>) getSlot(nextOperand());
    Object item = getSlot(nextOperand());
    list.addElement(item);
  }

  private Object returnInstr() throws EvalException {
    Object result = getSlotNullAsNone(nextOperand());
    validateInstructionDecodedCorrectly();
    return result;
  }

  private void newFunction() throws EvalException, InterruptedException {
    Resolver.Function fn = (Resolver.Function) compiled.objects[nextOperand()];
    Tuple parameterDefaults = Tuple.wrap(nextNSlotsListSharedArray());
    int result = nextOperand();
    StarlarkFunction starlarkFunction = Eval.newFunction(fr, slots, fn, parameterDefaults);
    setSlot(result, starlarkFunction);
  }

  private void br() {
    int dest = nextOperand();
    validateInstructionDecodedCorrectly();
    ip = dest;
  }

  private void ifBrLocal() throws EvalException {
    int condSlot = nextOperand();
    int dest = nextOperand();
    Object cond = getLocal(condSlot);
    if (Starlark.truth(cond)) {
      validateInstructionDecodedCorrectly();
      ip = dest;
    } else {
      validateInstructionDecodedCorrectly();
    }
  }

  private void ifNotBrLocal() throws EvalException {
    int condSlot = nextOperand();
    int dest = nextOperand();
    Object cond = getLocal(condSlot);
    if (!Starlark.truth(cond)) {
      validateInstructionDecodedCorrectly();
      ip = dest;
    } else {
      validateInstructionDecodedCorrectly();
    }
  }

  /** Set the instruction pointer. */
  private void goTo(int addr) {
    validateInstructionDecodedCorrectly();
    ip = addr;
  }

  private void forInit() throws EvalException {
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

  private void continueInstr() throws InterruptedException {
    int nextValueSlot = nextOperand();
    int b = nextOperand();
    int e = nextOperand();

    fr.thread.checkInterrupt();

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

  private void breakInstr() {
    int e = nextOperand();
    popFor();
    validateInstructionDecodedCorrectly();
    ip = e;
  }

  private void unpack() throws EvalException {
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

  private void list() throws EvalException {
    Object[] data = nextNSlotsListUnsharedArray();
    StarlarkList<?> result = StarlarkList.wrap(fr.thread.mutability(), data);
    setSlot(nextOperand(), result);
  }

  private void tuple() throws EvalException {
    Object[] data = nextNSlotsListSharedArray();
    Tuple result = Tuple.wrap(data);
    setSlot(nextOperand(), result);
  }

  private void dict() throws EvalException {
    int size = nextOperand();
    Dict<?, ?> result;
    if (size == 0) {
      result = Dict.of(fr.thread.mutability());
    } else {
      LinkedHashMap<Object, Object> lhm = Maps.newLinkedHashMapWithExpectedSize(size);
      for (int j = 0; j != size; ++j) {
        Object key = getSlot(nextOperand());
        Starlark.checkHashable(key);
        Object value = getSlot(nextOperand());
        Object prev = lhm.put(key, value);
        if (prev != null) {
          throw new EvalException(
              "dictionary expression has duplicate key: " + Starlark.repr(key));
        }
      }
      result = Dict.wrap(fr.thread.mutability(), lhm);
    }
    setSlot(nextOperand(), result);
  }

  /** Dot operator. */
  private void dot() throws EvalException, InterruptedException {
    int selfSlot = nextOperand();
    int siteIndex = nextOperand();
    int resultSlot = nextOperand();

    Object self = getSlot(selfSlot);
    BcDotSite site = (BcDotSite) compiled.objects[siteIndex];
    Object result = site.getattr(fr.thread, self);
    setSlot(resultSlot, result);
  }

  /** Index operator. */
  private void index() throws EvalException {
    Object object = getSlot(nextOperand());
    Object index = getSlot(nextOperand());
    setSlot(
        nextOperand(),
        EvalUtils.index(fr.thread.mutability(), fr.thread.getSemantics(), object, index));
  }

  /** Slice operator. */
  private void slice() throws EvalException {
    Object object = getSlot(nextOperand());
    Object start = getSlotNullAsNone(nextOperand());
    Object stop = getSlotNullAsNone(nextOperand());
    Object step = getSlotNullAsNone(nextOperand());
    setSlot(nextOperand(), Starlark.slice(fr.thread.mutability(), object, start, stop, step));
  }

  /** Call operator. */
  private void call() throws EvalException, InterruptedException {
    fr.thread.checkInterrupt();

    BcCallLocs locs = (BcCallLocs) compiled.objects[nextOperand()];
    fr.setLocation(locs.getLparentLocation());

    StarlarkCallable fn = BcCall.callable(fr.thread, getSlot(nextOperand()));
    BcDynCallSite callSite = (BcDynCallSite) compiled.objects[nextOperand()];
    Object[] args = nextNSlotsListSharedArray();
    Object star = getSlotOrNull(nextOperand());
    Object starStar = getSlotOrNull(nextOperand());

    if (star != null) {
      if (!(star instanceof Sequence)) {
        throw new EvalException(
            locs.starLocation(compiled.getFileLocations()),
            "argument after * must be an iterable, not " + Starlark.type(star));
      }
    }

    if (starStar != null) {
      if (!(starStar instanceof Dict)) {
        throw new EvalException(
            locs.starStarLocation(compiled.getFileLocations()),
            "argument after ** must be a dict, not " + Starlark.type(starStar));
      }
    }

    Object result = BcCall.linkAndCallCs(fr.thread, fn, callSite, args, (Sequence<?>) star, (Dict<?, ?>) starStar);

    setSlot(nextOperand(), result);
  }

  /** Call after successful link at compile time. */
  private void callLinked() throws EvalException, InterruptedException {
    fr.thread.checkInterrupt();

    BcCallLocs locs = (BcCallLocs) compiled.objects[nextOperand()];
    fr.setLocation(locs.getLparentLocation());

    StarlarkCallableLinked fn = (StarlarkCallableLinked) compiled.objects[nextOperand()];

    Object[] args = nextNSlotsListSharedArray();

    Object star = getSlotOrNull(nextOperand());
    Object starStar = getSlotOrNull(nextOperand());

    if (star != null && !(star instanceof Sequence<?>)) {
      throw new EvalException(
          locs.starLocation(compiled.getFileLocations()),
          String.format("argument after * must be an iterable, not %s", Starlark.type(star)));
    }
    if (starStar != null && !(starStar instanceof Dict<?, ?>)) {
      throw new EvalException(
          locs.starStarLocation(compiled.getFileLocations()),
          String.format("argument after ** must be a dict, not %s", Starlark.type(starStar)));
    }

    Object result = BcCall.callLinked(fr.thread, fn, args, (Sequence<?>) star, (Dict<?, ?>) starStar);
    setSlot(nextOperand(), result);
  }

  private void callCached() throws EvalException, InterruptedException {
    int callCachedIndex = nextOperand();
    int resultSlot = nextOperand();
    BcCallCached callCached = (BcCallCached) compiled.objects[callCachedIndex];
    Object result = callCached.call(fr.thread);
    setSlot(resultSlot, result);
  }

  /** Not operator. */
  private void not() throws EvalException {
    Object value = getSlot(nextOperand());
    setSlot(nextOperand(), !Starlark.truth(value));
  }

  /** Generic unary operator. */
  private void unary() throws EvalException {
    Object value = getSlot(nextOperand());
    TokenKind op = TOKENS[nextOperand()];
    setSlot(nextOperand(), EvalUtils.unaryOp(op, value));
  }

  /**
   * Generic binary operator
   *
   * <p>Note that {@code and} and {@code or} are not emitted as binary operator instruction. .
   */
  private void binary() throws EvalException {
    Object x = getSlot(nextOperand());
    Object y = getSlot(nextOperand());
    TokenKind op = TOKENS[nextOperand()];
    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.recordBinaryOp(op);
    }
    setSlot(
        nextOperand(),
        EvalUtils.binaryOp(op, x, y, fr.thread.getSemantics(), fr.thread.mutability()));
  }

  /** {@code +=} operator. */
  private void plusInPlace() throws EvalException {
    int lhs = nextOperand();
    int rhs = nextOperand();
    int result = nextOperand();
    Object x = getSlot(lhs);
    Object y = getSlot(rhs);
    setSlot(result, Eval.inplaceBinaryPlus(fr, x, y));
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

  private void percentSOne() throws EvalException {
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

  private void percentSOneTuple() throws EvalException {
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
  private void plusStringInPlace() throws EvalException {
    int lhs = nextOperand();
    int rhs = nextOperand();
    int resultSlot = nextOperand();
    Object x = getSlot(lhs);
    Object y = getSlot(rhs);
    Object result;
    if (x instanceof String && y instanceof String) {
      result = (String) x + (String) y;
    } else {
      result = Eval.inplaceBinaryPlus(fr, x, y);
    }
    setSlot(resultSlot, result);
  }

  /** {@code x += [...]}. */
  @SuppressWarnings("unchecked")
  private void plusListInPlace() throws EvalException {
    Object x = getSlot(nextOperand());
    Object result;
    if (x instanceof StarlarkList<?>) {
      Object[] rhsValues = nextNSlotsListSharedArray();
      ((StarlarkList<Object>) x).addElements(rhsValues);
      result = x;
    } else {
      Object[] rhsValues = nextNSlotsListUnsharedArray();
      result = Eval.inplaceBinaryPlus(fr, x, StarlarkList.wrap(fr.thread.mutability(), rhsValues));
    }
    int resultSlot = nextOperand();
    setSlot(resultSlot, result);
  }

  private void typeIs() throws EvalException {
    int lhsSlot = nextOperand();
    int typeIndex = nextOperand();
    int resultSlot = nextOperand();

    Object lhs = getLocal(lhsSlot);
    String lhsType = Starlark.type(lhs);
    String rhsType = compiled.strings[typeIndex];
    boolean result = lhsType == rhsType || lhsType.equals(rhsType);
    setSlot(resultSlot, result);
  }

  /** Equality. */
  private void eq() throws EvalException {
    Object lhs = getSlot(nextOperand());
    Object rhs = getSlot(nextOperand());
    setSlot(nextOperand(), lhs == rhs || lhs.equals(rhs));
  }

  /** Equality. */
  private void notEq() throws EvalException {
    Object lhs = getSlot(nextOperand());
    Object rhs = getSlot(nextOperand());
    setSlot(nextOperand(), lhs != rhs && !lhs.equals(rhs));
  }

  /** a + b. */
  private void plus() throws EvalException {
    int lhsSlot = nextOperand();
    int rhsSlot = nextOperand();
    int resultSlot = nextOperand();
    Object lhs = getSlot(lhsSlot);
    Object rhs = getSlot(rhsSlot);
    setSlot(resultSlot, EvalUtils.binaryPlus(lhs, rhs, fr.thread.mutability()));
  }

  /** a + b where a and b are likely strings. */
  private void plusString() throws EvalException {
    int lhsSlot = nextOperand();
    int rhsSlot = nextOperand();
    int resultSlot = nextOperand();
    Object lhs = getSlot(lhsSlot);
    Object rhs = getSlot(rhsSlot);
    Object result;
    if (lhs instanceof String && rhs instanceof String) {
      result = (String) lhs + (String) rhs;
    } else {
      result = EvalUtils.binaryPlus(lhs, rhs, fr.thread.mutability());
    }
    setSlot(resultSlot, result);
  }

  /** a + b where b is a {@link BcInstrOperand#IN_LIST}. */
  private void plusList() throws EvalException {
    Object lhs = getSlot(nextOperand());
    Object result;
    if (lhs instanceof StarlarkList<?>) {
      Object[] rhs = nextNSlotsListSharedArray();
      result = StarlarkList.concat((StarlarkList<?>) lhs, rhs, fr.thread.mutability());
    } else {
      Object[] rhs = nextNSlotsListUnsharedArray();
      result = EvalUtils.binaryPlus(
          lhs,
          StarlarkList.wrap(fr.thread.mutability(), rhs),
          fr.thread.mutability());
    }
    int resultSlot = nextOperand();
    setSlot(resultSlot, result);
  }

  /** {@code a in b} */
  private void binaryIn() throws EvalException {
    int lhsSlot = nextOperand();
    int rhsSlot = nextOperand();
    int resultSlot = nextOperand();
    Object lhs = getSlot(lhsSlot);
    Object rhs = getSlot(rhsSlot);
    setSlot(resultSlot, EvalUtils.binaryIn(lhs, rhs, fr.thread.getSemantics()));
  }

  /** {@code a not in b} */
  private void binaryNotIn() throws EvalException {
    int lhsSlot = nextOperand();
    int rhsSlot = nextOperand();
    int resultSlot = nextOperand();
    Object lhs = getSlot(lhsSlot);
    Object rhs = getSlot(rhsSlot);
    setSlot(resultSlot, !EvalUtils.binaryIn(lhs, rhs, fr.thread.getSemantics()));
  }

  private void evalException() throws EvalException {
    String message = compiled.strings[nextOperand()];
    validateInstructionDecodedCorrectly();
    throw new EvalException(message);
  }

  private EvalException otherOpcode(int opcode) {
    if (opcode < BcInstr.Opcode.values().length) {
      throw new IllegalStateException("not implemented opcode: " + BcInstr.Opcode.values()[opcode]);
    } else {
      throw new IllegalStateException("wrong opcode: " + opcode);
    }
  }

  private void validateInstructionDecodedCorrectly() {
    if (Bc.ASSERTIONS) {
      // Validate the last instruction was decoded correctly
      // (got all the argument, and no extra arguments).
      // This is quite helpful, but expensive assertion, only enabled when bytecode assertions
      // are on.
      BcInstr.Opcode opcode = BcInstr.Opcode.values()[text[currentIp]];
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
