package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import net.starlark.java.syntax.*;

import javax.annotation.Nullable;
import java.util.*;

/** Bytecode interpreter. Takes a compiled function body and returns a result. */
class BcEval {
  private static final Object[] EMPTY = {};
  private static final TokenKind[] TOKENS = TokenKind.values();

  private final StarlarkThread.Frame fr;
  private final Resolver.Function rfn;
  private final Bc.Compiled compiled;

  /** Registers. */
  private final Object[] slots;

  /**
   * Currently executed loops stack: pairs of (iterable, iterator).
   *
   * <p>The array is preallocated, {@link #loopDepth} holds the number of currently executed loops.
   */
  private final Object[] loops;

  /** Current loop depth. */
  private int loopDepth = 0;

  /** Program text. */
  private final int[] text;

  /** Current instruction pointer */
  private int currentIp;

  /** Instruction pointer while decoding operands */
  private int ip = 0;

  private BcEval(StarlarkThread.Frame fr, Resolver.Function rfn, Bc.Compiled compiled) {
    this.fr = fr;

    this.rfn = rfn;
    this.compiled = compiled;
    this.slots = fr.locals;
    this.loops = new Object[compiled.loopDepth * 2];
    this.text = compiled.text;
  }

  /** Public API. */
  public static Object eval(StarlarkThread.Frame fr, Resolver.Function rfn, Bc.Compiled compiled)
      throws InterruptedException, EvalException {
    return new BcEval(fr, rfn, compiled).eval();
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
        switch (opcode) {
          case BcInstr.CP:
            cp();
            break;
          case BcInstr.EQ:
            eq();
            break;
          case BcInstr.NOT_EQ:
            notEq();
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
          case BcInstr.BINARY_IN_PLACE:
            binaryInPlace();
            break;
          case BcInstr.BR:
            br();
            continue;
          case BcInstr.IF_BR:
            ifBr();
            continue;
          case BcInstr.IF_NOT_BR:
            ifNotBr();
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
    } catch (EvalException e) {
      fr.setLocation(compiled.locationAt(currentIp));
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
    EvalUtils.removeIterator(loops[(loopDepth - 1) * 2]);
    loops[(loopDepth - 1) * 2] = null;
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
    int index = slot & ~BcSlot.MASK;
    switch (slot & BcSlot.MASK) {
      case BcSlot.LOCAL_FLAG:
        Object value = slots[index];
        if (value == null) {
          throwLocalNotFound(index);
        }
        return value;
      case BcSlot.CONST_FLAG:
        return compiled.constSlots[index];
      case BcSlot.GLOBAL_FLAG:
        return getGlobal(index);
      case BcSlot.CELL_FLAG:
        return getCell(index);
      case BcSlot.FREE_FLAG:
        return getFree(index);
      default:
        throw new IllegalStateException("wrong slot: " + slot);
    }
  }

  private StarlarkFunction fn() {
    return (StarlarkFunction) fr.fn;
  }

  private void throwLocalNotFound(int index) throws EvalException {
    if (index < rfn.getLocals().size()) {
      Resolver.Binding binding = rfn.getLocals().get(index);
      fr.setErrorLocation(fr.getLocation());
      throw referencedBeforeAssignment(binding.getScope(), binding.getName());
    } else {
      // Now this is always IllegalStateException,
      // but it should be also EvalException when we store locals in registers.
      throw new IllegalStateException("slot value is undefined: " + index);
    }
  }

  private Object getGlobal(int index) throws EvalException {
    Object value = fn().getGlobal(index);
    if (value == null) {
      String name = rfn.getGlobals().get(index);
      throw referencedBeforeAssignment(Resolver.Scope.GLOBAL, name);
    }
    return value;
  }

  private Object getFree(int index) throws EvalException {
    Object value = fn().getFreeVar(index).x;
    if (value == null) {
      String name = rfn.getFreeVars().get(index).getName();
      throw referencedBeforeAssignment(Resolver.Scope.FREE, name);
    }
    return value;
  }

  private Object getCell(int index) throws EvalException {
    Object value = ((StarlarkFunction.Cell) fr.locals[index]).x;
    if (value == null) {
      String name = rfn.getLocals().get(index).getName();
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
    Object value = getSlot(nextOperand());
    setSlot(nextOperand(), value);
  }

  private void setGlobal() throws EvalException {
    Object value = getSlot(nextOperand());
    int nameIndex = nextOperand();
    String name = compiled.strings[nextOperand()];
    boolean postAssignHook = nextOperand() != 0;
    StarlarkFunction fn = fn();
    fn.setGlobal(nameIndex, value);
    if (postAssignHook) {
      if (fr.thread.postAssignHook != null) {
        if (fn().isToplevel()) {
          fr.thread.postAssignHook.assign(name, value);
        }
      }
    }
  }

  private void setCell() throws EvalException {
    Object value = getSlot(nextOperand());
    int cellIndex = nextOperand();
    ((StarlarkFunction.Cell) fr.locals[cellIndex]).x = value;
  }

  private void loadStmt() throws EvalException, InterruptedException {
    LoadStatement statement = (LoadStatement) compiled.objects[nextOperand()];
    TokenKind token = Eval.exec(fr, statement);
    Preconditions.checkState(token == TokenKind.PASS);
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
    Tuple parameterDefaults = Tuple.wrap(nextNSlots());
    int result = nextOperand();
    StarlarkFunction starlarkFunction = Eval.newFunction(fr, fn, parameterDefaults);
    setSlot(result, starlarkFunction);
  }

  private void br() {
    int dest = nextOperand();
    validateInstructionDecodedCorrectly();
    ip = dest;
  }

  private void ifBr() throws EvalException {
    Object cond = getSlot(nextOperand());
    int dest = nextOperand();
    if (Starlark.truth(cond)) {
      validateInstructionDecodedCorrectly();
      ip = dest;
    } else {
      validateInstructionDecodedCorrectly();
    }
  }

  private void ifNotBr() throws EvalException {
    Object cond = getSlot(nextOperand());
    int dest = nextOperand();
    if (!Starlark.truth(cond)) {
      validateInstructionDecodedCorrectly();
      ip = dest;
    } else {
      validateInstructionDecodedCorrectly();
    }
  }

  private void forInit() throws EvalException {
    Object value = getSlot(nextOperand());
    int nextValueSlot = nextOperand();
    int end = nextOperand();

    Iterable<?> seq = Starlark.toIterable(value);
    Iterator<?> iterator = seq.iterator();
    if (!iterator.hasNext()) {
      validateInstructionDecodedCorrectly();
      ip = end;
      return;
    }

    EvalUtils.addIterator(seq);
    loops[loopDepth * 2] = seq;
    loops[loopDepth * 2 + 1] = iterator;
    ++loopDepth;

    Object item = iterator.next();
    setSlot(nextValueSlot, item);
    validateInstructionDecodedCorrectly();
  }

  private void continueInstr() throws InterruptedException {
    int nextValueSlot = nextOperand();
    int b = nextOperand();
    int e = nextOperand();

    fr.thread.checkInterrupt();

    Iterator<?> iterator = (Iterator<?>) loops[(loopDepth - 1) * 2 + 1];
    if (iterator.hasNext()) {
      setSlot(nextValueSlot, iterator.next());
      validateInstructionDecodedCorrectly();
      ip = b;
    } else {
      popFor();
      validateInstructionDecodedCorrectly();
      ip = e;
    }
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

  private Object[] nextNSlots() throws EvalException {
    int size = nextOperand();
    if (size == 0) {
      return EMPTY;
    }
    Object[] array = new Object[size];
    for (int j = 0; j != array.length; ++j) {
      array[j] = getSlot(nextOperand());
    }
    return array;
  }

  private void list() throws EvalException {
    Object[] data = nextNSlots();
    StarlarkList<?> result = StarlarkList.wrap(fr.thread.mutability(), data);
    setSlot(nextOperand(), result);
  }

  private void tuple() throws EvalException {
    Object[] data = nextNSlots();
    Tuple result = Tuple.wrap(data);
    setSlot(nextOperand(), result);
  }

  private void dict() throws EvalException {
    int size = nextOperand();
    Dict<?, ?> result;
    if (size == 0) {
      result = Dict.of(fr.thread.mutability());
    } else {
      LinkedHashMap<Object, Object> lhm = new LinkedHashMap<>(size);
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
    Object object = getSlot(nextOperand());
    String name = compiled.strings[nextOperand()];
    Object result = Starlark.getattr(fr.thread.mutability(), fr.thread.getSemantics(), object, name, null);
    setSlot(nextOperand(), result);
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

  private CallExpression currentCallExpression() {
    return (CallExpression) compiled.nodeAt(currentIp);
  }

  private Location currentCallStarLocation() {
    CallExpression callExpression = currentCallExpression();
    return callExpression.getArguments().stream()
        .filter(a -> a instanceof Argument.Star)
        .map(a -> a.getStartLocation())
        .findFirst()
        .orElseGet(() -> {
          // Not really needed, but better be safe
          return callExpression.getStartLocation();
        });
  }

  private Location currentCallStarStarLocation() {
    CallExpression callExpression = currentCallExpression();
    return callExpression.getArguments().stream()
        .filter(a -> a instanceof Argument.StarStar)
        .map(a -> a.getStartLocation())
        .findFirst()
        .orElseGet(() -> {
          // Not really needed, but better be safe
          return callExpression.getStartLocation();
        });
  }

  /** Call operator. */
  private void call() throws EvalException, InterruptedException {
    fr.thread.checkInterrupt();

    Location lparenLocation = (Location) compiled.objects[nextOperand()];
    fr.setLocation(lparenLocation);

    Object fn = getSlot(nextOperand());
    int npos = nextOperand();
    Object[] pos = npos != 0 ? new Object[npos] : EMPTY;
    for (int i = 0; i < npos; ++i) {
      pos[i] = getSlot(nextOperand());
    }
    int nnamed = nextOperand();
    Object[] named = nnamed != 0 ? new Object[nnamed * 2] : EMPTY;
    for (int i = 0; i < nnamed; ++i) {
      named[i * 2] = compiled.strings[nextOperand()];
      named[i * 2 + 1] = getSlot(nextOperand());
    }
    Object star = getSlotOrNull(nextOperand());
    Object starStar = getSlotOrNull(nextOperand());

    if (star != null) {
      if (!(star instanceof StarlarkIterable)) {
        throw new EvalException(
            currentCallStarLocation(),
            "argument after * must be an iterable, not " + Starlark.type(star));
      }
      Iterable<?> iter = (Iterable<?>) star;

      // TODO(adonovan): opt: if value.size is known, preallocate (and skip if empty).
      ArrayList<Object> list = new ArrayList<>();
      Collections.addAll(list, pos);
      Iterables.addAll(list, iter);
      pos = list.toArray();
    }

    if (starStar != null) {
      if (!(starStar instanceof Dict)) {
        throw new EvalException(
            currentCallStarStarLocation(),
            "argument after ** must be a dict, not " + Starlark.type(starStar));
      }
      Dict<?, ?> dict = (Dict<?, ?>) starStar;

      int j = named.length;
      named = Arrays.copyOf(named, j + 2 * dict.size());
      for (Map.Entry<?, ?> e : dict.contentsUnsafe().entrySet()) {
        if (!(e.getKey() instanceof String)) {
          throw new EvalException(
              currentCallStarStarLocation(),
              "keywords must be strings, not " + Starlark.type(e.getKey()));
        }
        named[j++] = e.getKey();
        named[j++] = e.getValue();
      }
    }

    setSlot(nextOperand(), Starlark.fastcall(fr.thread, fn, pos, named));
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
    setSlot(
        nextOperand(),
        EvalUtils.binaryOp(op, x, y, fr.thread.getSemantics(), fr.thread.mutability()));
  }

  /**
   * Generic binary operator
   *
   * <p>Note that {@code and} and {@code or} are not emitted as binary operator instruction. .
   */
  private void binaryInPlace() throws EvalException {
    Object x = getSlot(nextOperand());
    Object y = getSlot(nextOperand());
    TokenKind op = TOKENS[nextOperand()];
    setSlot(nextOperand(), Eval.inplaceBinaryOp(fr, op, x, y));
  }

  /** Equality. */
  private void eq() throws EvalException {
    Object lhs = getSlot(nextOperand());
    Object rhs = getSlot(nextOperand());
    setSlot(nextOperand(), lhs.equals(rhs));
  }

  /** Equality. */
  private void notEq() throws EvalException {
    Object lhs = getSlot(nextOperand());
    Object rhs = getSlot(nextOperand());
    setSlot(nextOperand(), !lhs.equals(rhs));
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
