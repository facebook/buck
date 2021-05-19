package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.TokenKind;

/** Utility to build bytecode. */
class BcWriter {

  /** Marker address for yet unknown forward jump. */
  private static final int FORWARD_JUMP_ADDR = -17;

  private final FileLocations fileLocations;
  private final Module module;
  /** Function name. */
  private final String name;
  /** Number of local variables. */
  private final int nlocals;

  private final ImmutableList<Resolver.Binding> locals;
  private final ImmutableList<Resolver.Binding> freeVars;

  /** {@code 0..ip} of the array is bytecode. */
  private int[] text = ArraysForStarlark.EMPTY_INT_ARRAY;
  /** Current instruction pointer. */
  private int ip = 0;

  /** Slots available for reuse. */
  private ArrayDeque<Integer> freeSlots = new ArrayDeque<>();

  /** Total number of registers needed to execute this function. */
  private int maxSlots;

  /** The stack of for statements. */
  private ArrayList<CurrentFor> fors = new ArrayList<>();
  /** Max depth of for loops. */
  private int maxLoopDepth = 0;

  /** Alternating instr, file locations offset */
  private final BcInstrToLoc.Builder instrToLoc;

  /** Starlark values as constant registers. */
  private IndexedList<Object> constSlots = new IndexedList<>();
  /** Strings referenced in currently built bytecode. */
  private IndexedList<String> strings = new IndexedList<>();
  /** Other untyped objects referenced in currently built bytecode. */
  private ArrayList<Object> objects = new ArrayList<>();

  BcWriter(
      FileLocations fileLocations,
      Module module,
      String name,
      ImmutableList<Resolver.Binding> locals,
      ImmutableList<Resolver.Binding> freeVars) {
    this.fileLocations = fileLocations;
    this.module = module;
    this.name = name;
    this.nlocals = locals.size();

    this.locals = locals;
    this.freeVars = freeVars;
    this.maxSlots = nlocals;
    this.instrToLoc = new BcInstrToLoc.Builder(fileLocations);
  }

  public int getIp() {
    return ip;
  }

  /** Allocate a register. Return a pair of generation/slot number. */
  int allocSlot() {
    Integer slot = freeSlots.pollFirst();
    if (slot != null) {
      return slot;
    } else {
      return maxSlots++;
    }
  }

  void releaseSlot(int slot) {
    Preconditions.checkArgument(slot >= nlocals);
    freeSlots.addLast(slot);
  }

  void assertAllSlotsReleased() {
    Preconditions.checkState(nlocals + freeSlots.size() == maxSlots, "not all slots released");
  }

  /**
   * Store a string in a string pool, return an index of that string. Note these strings are special
   * strings like variable or field names. These are not constant registers.
   */
  int allocString(String s) {
    return strings.index(s);
  }

  /** Store an arbitrary object in an object storage; the object store is not a const registers. */
  int allocObject(Object o) {
    Preconditions.checkNotNull(o);
    int r = objects.size();
    objects.add(o);
    return r;
  }

  /** Allocate constant slot. */
  int allocConstSlot(Object value) {
    return BcSlot.constValue(this.constSlots.index(value));
  }

  /** Write an instruction which throws eval exception at evaluation. */
  void writeEvalException(LocOffset locOffset, String message) {
    write(BcInstrOpcode.EVAL_EXCEPTION, locOffset, allocString(message));
  }

  void writeContinue(LocOffset locOffset) {
    if (fors.isEmpty()) {
      writeEvalException(locOffset, "continue statement must be inside a for loop");
    } else {
      write(
          BcInstrOpcode.CONTINUE,
          locOffset,
          currentFor().nextValueSlot,
          currentFor().bodyIp,
          BcWriter.FORWARD_JUMP_ADDR);
      currentFor().endsToPatch.add(ip - 1);
    }
  }

  void writeBreak(LocOffset locOffset) {
    if (fors.isEmpty()) {
      writeEvalException(locOffset, "break statement must be inside a for loop");
    } else {
      write(BcInstrOpcode.BREAK, locOffset, BcWriter.FORWARD_JUMP_ADDR);
      currentFor().endsToPatch.add(ip - 1);
    }
  }

  void writeForInit(LocOffset collectionLocOffset, int collectionSlot, int nextValueSlot) {
    write(
        BcInstrOpcode.FOR_INIT,
        collectionLocOffset,
        collectionSlot,
        nextValueSlot,
        FORWARD_JUMP_ADDR);

    int endToPatch = ip - 1;

    CurrentFor currentFor = new CurrentFor(ip, nextValueSlot);
    fors.add(currentFor);
    currentFor.endsToPatch.add(endToPatch);

    maxLoopDepth = Math.max(fors.size(), maxLoopDepth);
  }

  void writeForClose() {
    patchForwardJumps(currentFor().endsToPatch);
    fors.remove(fors.size() - 1);
  }

  public String getName() {
    return name;
  }

  public int[] text() {
    return Arrays.copyOf(text, ip);
  }

  enum JumpCond {
    IF(BcInstrOpcode.IF_BR_LOCAL),
    IF_NOT(BcInstrOpcode.IF_NOT_BR_LOCAL),
    ;

    final BcInstrOpcode opcode;

    JumpCond(BcInstrOpcode opcode) {
      this.opcode = opcode;
    }
  }

  enum JumpBindCond {
    EQ(BcInstrOpcode.IF_EQ_BR, TokenKind.EQUALS_EQUALS),
    NOT_EQ(BcInstrOpcode.IF_NOT_EQ_BR, TokenKind.NOT_EQUALS),
    IN(BcInstrOpcode.IF_IN_BR, TokenKind.IN),
    NOT_IN(BcInstrOpcode.IF_NOT_IN_BR, TokenKind.NOT_IN),
    ;
    final BcInstrOpcode opcode;
    final TokenKind tokenKind;

    JumpBindCond(BcInstrOpcode opcode, TokenKind tokenKind) {
      this.opcode = opcode;
      this.tokenKind = tokenKind;
    }

    @Nullable
    static JumpBindCond fromBinOpToken(TokenKind tokenKind) {
      for (JumpBindCond cond : values()) {
        if (cond.tokenKind == tokenKind) {
          return cond;
        }
      }
      return null;
    }

    JumpBindCond not() {
      switch (this) {
        case EQ:
          return NOT_EQ;
        case NOT_EQ:
          return EQ;
        case IN:
          return NOT_IN;
        case NOT_IN:
          return IN;
        default:
          throw new AssertionError("unreachable");
      }
    }
  }

  /** Store values indexed by an integer. */
  private static class IndexedList<T> {
    private ArrayList<T> values = new ArrayList<>();
    private HashMap<Object, Integer> index = new HashMap<>();

    /** Be able to store 1 and 1.0 in index as distinct entries, while the objects are equal. */
    private static class StarlarkFloatWrapper {
      private final StarlarkFloat f;

      public StarlarkFloatWrapper(StarlarkFloat f) {
        this.f = f;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        StarlarkFloatWrapper that = (StarlarkFloatWrapper) o;
        return f.equals(that.f);
      }

      @Override
      public int hashCode() {
        return Objects.hash(f);
      }
    }

    private Object makeKey(T value) {
      if (value instanceof StarlarkFloat) {
        return new StarlarkFloatWrapper((StarlarkFloat) value);
      } else {
        return value;
      }
    }

    int index(T s) {
      Preconditions.checkNotNull(s);
      return index.computeIfAbsent(
          makeKey(s),
          k -> {
            int r = values.size();
            values.add(s);
            return r;
          });
    }

    public T[] toArray(T[] emptyArray) {
      return values.toArray(emptyArray);
    }
  }

  /** Newtype for offset within {@link #fileLocations}. */
  static class LocOffset {
    private final int offset;

    LocOffset(int offset) {
      this.offset = offset;
    }
  }

  /** Write complete opcode with validation. */
  void write(BcInstrOpcode opcode, LocOffset locOffset, int... args) {
    instrToLoc.add(ip, locOffset.offset);

    int prevIp = ip;

    int instrLen = BcInstr.INSTR_HEADER_LEN + args.length;
    if (ip + instrLen > text.length) {
      text = Arrays.copyOf(text, Math.max(text.length * 2, ip + instrLen));
    }

    text[ip++] = opcode.ordinal();
    System.arraycopy(args, 0, text, ip, args.length);
    ip += args.length;

    if (StarlarkAssertions.ENABLED) {
      int expectedArgCount = opcode.operands.codeSize(text, prevIp + BcInstr.INSTR_HEADER_LEN);
      Preconditions.checkState(
          expectedArgCount == args.length,
          "incorrect signature for %s: expected %s, actual %s",
          opcode,
          expectedArgCount,
          args.length);
    }
  }

  static int[] args(int[] args0, int... args1) {
    if (args0.length == 0) {
      return args1;
    } else if (args1.length == 0) {
      return args0;
    } else {
      int[] args = Arrays.copyOf(args0, args0.length + args1.length);
      System.arraycopy(args1, 0, args, args0.length, args1.length);
      return args;
    }
  }

  static int[] args(int[] args0, int[] args1, int[] args2) {
    IntArrayBuilder args = new IntArrayBuilder(args0.length + args1.length + args2.length);
    args.addAll(args0);
    args.addAll(args1);
    args.addAll(args2);
    return args.buildArray();
  }

  /**
   * Write forward condition jump instruction. Return an address to be patched when the jump address
   * is known.
   */
  int writeForwardCondJump(JumpCond jumpCond, LocOffset locOffset, int cond) {
    write(jumpCond.opcode, locOffset, cond, FORWARD_JUMP_ADDR);
    return ip - 1;
  }

  static BcInstrOpcode typeIsJumpOpcode(JumpCond jumpCond) {
    switch (jumpCond) {
      case IF:
        return BcInstrOpcode.IF_TYPE_IS_BR;
      case IF_NOT:
        return BcInstrOpcode.IF_NOT_TYPE_IS_BR;
      default:
        throw new AssertionError("unreachable");
    }
  }

  /**
   * Write forward condition jump instruction. Return an address to be patched when the jump address
   * is known.
   */
  int writeForwardTypeIsJump(JumpCond jumpCond, LocOffset locOffset, int expr, String type) {
    write(typeIsJumpOpcode(jumpCond), locOffset, expr, allocString(type), FORWARD_JUMP_ADDR);
    return ip - 1;
  }

  /**
   * Write forward condition jump instruction. Return an address to be patched when the jump address
   * is known.
   */
  int writeForwardBinCondJump(JumpBindCond jumpBindCond, LocOffset locOffset, int a, int b) {
    write(jumpBindCond.opcode, locOffset, a, b, FORWARD_JUMP_ADDR);
    return ip - 1;
  }

  /**
   * Write unconditional forward jump. Return an address to be patched when the jump address is
   * known.
   */
  int writeForwardJump(LocOffset locOffset) {
    write(BcInstrOpcode.BR, locOffset, FORWARD_JUMP_ADDR);
    return ip - 1;
  }

  /** Patch previously registered forward jump address. */
  void patchForwardJump(int ip) {
    Preconditions.checkState(text[ip] == FORWARD_JUMP_ADDR);
    text[ip] = this.ip;
  }

  void patchForwardJumps(IntArrayBuilder ips) {
    for (int i = 0; i != ips.size(); ++i) {
      patchForwardJump(ips.get(i));
    }
  }

  /** Current for block in the compiler; used to compile break and continue statements. */
  private static class CurrentFor {
    /** Instruction pointer of the for statement body. */
    private final int bodyIp;

    /**
     * Register which stores next iterator value. This register is updated by {@code FOR_INIT} and
     * {@code CONTINUE} instructions.
     */
    private final int nextValueSlot;

    /**
     * Pointers to the pointers to the end of the for statement body; patched in the end of the for
     * compilation.
     */
    private final IntArrayBuilder endsToPatch = new IntArrayBuilder();

    private CurrentFor(int bodyIp, int nextValueSlot) {
      this.bodyIp = bodyIp;
      this.nextValueSlot = nextValueSlot;
    }
  }

  /** Closest containing for statement. */
  private CurrentFor currentFor() {
    return fors.get(fors.size() - 1);
  }

  BcCompiled finish(@Nullable Object returnsConst, @Nullable String returnsTypeIs) {
    return new BcCompiled(
        name,
        fileLocations,
        locals,
        freeVars,
        module,
        strings.toArray(ArraysForStarlark.EMPTY_STRING_ARRAY),
        objects.toArray(ArraysForStarlark.EMPTY_OBJECT_ARRAY),
        Arrays.copyOf(text, ip),
        maxSlots,
        constSlots.toArray(ArraysForStarlark.EMPTY_OBJECT_ARRAY),
        maxLoopDepth,
        instrToLoc.build(),
        returnsConst,
        returnsTypeIs);
  }

  private ImmutableList<String> getFreeVarNames() {
    return freeVars.stream()
        .map(Resolver.Binding::getName)
        .collect(ImmutableList.toImmutableList());
  }

  private ImmutableList<String> getLocalNames() {
    return locals.stream().map(Resolver.Binding::getName).collect(ImmutableList.toImmutableList());
  }

  @Override
  public String toString() {
    return BcCompiled.toStringImpl(
        name,
        text(),
        new BcInstrOperand.OpcodePrinterFunctionContext(
            getLocalNames(), module.getResolverModule().getGlobalNamesSlow(), getFreeVarNames()),
        strings.values,
        constSlots.values,
        objects);
  }
}
