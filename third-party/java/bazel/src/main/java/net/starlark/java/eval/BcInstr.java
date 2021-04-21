package net.starlark.java.eval;

import com.google.common.base.Preconditions;

/** Instructions for the bytecode interpreter. */
class BcInstr {

  private BcInstr() {}

  // The instruction header is an opcode.
  static final int INSTR_HEADER_LEN = 1;

  // We assign integer constants explicitly instead of using enum for performance:
  // our bytecode stores integers, and converting each opcode to enum might be expensive.

  public static final int CP = 0;
  public static final int EQ = CP + 1;
  public static final int NOT_EQ = EQ + 1;
  public static final int NOT = NOT_EQ + 1;
  public static final int UNARY = NOT + 1;
  public static final int BR = UNARY + 1;
  public static final int IF_BR = BR + 1;
  public static final int IF_NOT_BR = IF_BR + 1;
  public static final int BINARY = IF_NOT_BR + 1;
  public static final int BINARY_IN_PLACE = BINARY + 1;
  public static final int PERCENT_S_ONE = BINARY_IN_PLACE + 1;
  public static final int PERCENT_S_ONE_TUPLE = PERCENT_S_ONE + 1;
  public static final int SET_GLOBAL = PERCENT_S_ONE_TUPLE + 1;
  public static final int SET_CELL = SET_GLOBAL + 1;
  public static final int DOT = SET_CELL + 1;
  public static final int INDEX = DOT + 1;
  public static final int SLICE = INDEX + 1;
  public static final int CALL = SLICE + 1;
  public static final int CALL_LINKED = CALL + 1;
  public static final int RETURN = CALL_LINKED + 1;
  public static final int NEW_FUNCTION = RETURN + 1;
  public static final int FOR_INIT = NEW_FUNCTION + 1;
  public static final int CONTINUE = FOR_INIT + 1;
  public static final int BREAK = CONTINUE + 1;
  public static final int LIST = BREAK + 1;
  public static final int TUPLE = LIST + 1;
  public static final int DICT = TUPLE + 1;
  public static final int LIST_APPEND = DICT + 1;
  public static final int SET_INDEX = LIST_APPEND + 1;
  public static final int UNPACK = SET_INDEX + 1;
  public static final int LOAD_STMT = UNPACK + 1;
  public static final int EVAL_EXCEPTION = LOAD_STMT + 1;

  /**
   * Opcodes as enum. We use enums in the compiler, but we use only raw integers in the interpreter.
   *
   * <p>Enums are much nicer to work with, but they are much more expensive. Thus we use enums only
   * in the compiler, or during debugging.
   */
  public enum Opcode {
    /** {@code a1 = a0}. */
    CP(BcInstr.CP, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
    /**
     * {@code a2 = a0 == a1}. This is quite common operation, which deserves its own opcode to avoid
     * switching in generic binary operator handling.
     */
    EQ(BcInstr.EQ, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
    /**
     * {@code a2 = a0 != a1}. This is quite common operation, which deserves its own opcode to avoid
     * switching in generic binary operator handling.
     */
    NOT_EQ(BcInstr.NOT_EQ, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
    /**
     * {@code a1 = not a0}.
     *
     * <p>This could be handled by generic UNARY opcode, but it is specialized for performance.
     */
    NOT(BcInstr.NOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
    /** {@code a2 = (a1) a0}. */
    UNARY(
        BcInstr.UNARY, BcInstrOperand.IN_SLOT, BcInstrOperand.TOKEN_KIND, BcInstrOperand.OUT_SLOT),
    /** Goto. */
    BR(BcInstr.BR, BcInstrOperand.addr("j")),
    /** Goto if. */
    IF_BR(BcInstr.IF_BR, BcInstrOperand.IN_SLOT, BcInstrOperand.addr("t")),
    /** Goto if not. */
    IF_NOT_BR(BcInstr.IF_NOT_BR, BcInstrOperand.IN_SLOT, BcInstrOperand.addr("f")),
    /** {@code a3 = a0 (a2) a1}. */
    BINARY(
        BcInstr.BINARY,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.TOKEN_KIND,
        BcInstrOperand.OUT_SLOT),
    /** {@code a3 = a0 (a2)= a1}. */
    BINARY_IN_PLACE(
        BcInstr.BINARY_IN_PLACE,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.TOKEN_KIND,
        BcInstrOperand.OUT_SLOT),
    /** "aaa%sbbb" % arg */
    PERCENT_S_ONE(
        BcInstr.PERCENT_S_ONE,
        // format
        BcInstrOperand.STRING,
        // index of %s
        BcInstrOperand.NUMBER,
        // param
        BcInstrOperand.IN_SLOT,
        // Where to store result
        BcInstrOperand.OUT_SLOT
    ),
    /** "aaa%sbbb" % (arg,) */
    PERCENT_S_ONE_TUPLE(
        BcInstr.PERCENT_S_ONE_TUPLE,
        // format
        BcInstrOperand.STRING,
        // index of %s
        BcInstrOperand.NUMBER,
        // param
        BcInstrOperand.IN_SLOT,
        // Where to store result
        BcInstrOperand.OUT_SLOT
    ),
    /** Assign a value without destructuring to a global variable. */
    SET_GLOBAL(
        BcInstr.SET_GLOBAL,
        // value
        BcInstrOperand.IN_SLOT,
        // global index
        BcInstrOperand.NUMBER,
        // global name
        BcInstrOperand.STRING,
        // 1 if need to invoke post-assign hook, 0 otherwise
        BcInstrOperand.NUMBER),
    /** Set cell variable. */
    SET_CELL(
        BcInstr.SET_CELL,
        // value
        BcInstrOperand.IN_SLOT,
        // cell index
        BcInstrOperand.NUMBER
    ),
    /** {@code a2 = a0.a1} */
    DOT(BcInstr.DOT, BcInstrOperand.IN_SLOT, BcInstrOperand.STRING, BcInstrOperand.OUT_SLOT),
    /** {@code a2 = a0[a1]} */
    INDEX(BcInstr.INDEX, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
    /** {@code a4 = a0[a1:a2:a3]} */
    SLICE(
        BcInstr.SLICE,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.OUT_SLOT),
    /** Generic call invocation. */
    CALL(
        BcInstr.CALL,
        // BcCallLocs
        BcInstrOperand.OBJECT,
        // Function
        BcInstrOperand.IN_SLOT,
        // BcDynCallSite
        BcInstrOperand.OBJECT,
        // Positional arguments followed by named parameters
        BcInstrOperand.lengthDelimited(BcInstrOperand.IN_SLOT),
        // *args
        BcInstrOperand.IN_SLOT,
        // **kwargs
        BcInstrOperand.IN_SLOT,
        // Where to store result
        BcInstrOperand.OUT_SLOT),
    CALL_LINKED(
        BcInstr.CALL_LINKED,
        // BcCallLocs
        BcInstrOperand.OBJECT,
        // StarlarkCallableLinked
        BcInstrOperand.OBJECT,
        // Positional args followed by named args, no keys
        BcInstrOperand.lengthDelimited(BcInstrOperand.IN_SLOT),
        // *args
        BcInstrOperand.IN_SLOT,
        // **kwargs
        BcInstrOperand.IN_SLOT,
        // Where to store result
        BcInstrOperand.OUT_SLOT
    ),
    /** {@code return a0} */
    RETURN(BcInstr.RETURN, BcInstrOperand.IN_SLOT),
    /** Create a new function. */
    NEW_FUNCTION(
        BcInstr.NEW_FUNCTION,
        // Resolver.Function
        BcInstrOperand.OBJECT,
        // Function default values
        BcInstrOperand.lengthDelimited(BcInstrOperand.IN_SLOT),
        // Where to store result
        BcInstrOperand.OUT_SLOT),
    /**
     * For loop init:
     *
     * <ul>
     *   <li>Check if operand is iterable
     *   <li>Lock the iterable
     *   <li>Create an iterator
     *   <li>If iterator has no elements, go to "e".
     *   <li>Otherwise push iterable and iterator onto the stack
     *   <li>Fetch the first element of the iterator and store it in the provided register
     * </ul>
     */
    FOR_INIT(
        BcInstr.FOR_INIT,
        // Collection parameter
        BcInstrOperand.IN_SLOT,
        // Next value register
        BcInstrOperand.OUT_SLOT,
        BcInstrOperand.addr("e")),
    /**
     * Continue the loop:
     *
     * <ul>
     *   <li>If current iterator (stored on the stack) is empty, unlock the iterable and pop
     *       iterable and iterable from the stack and go to the label "e" after the end of the loop.
     *   <li>Otherwise assign the next iterator item to the provided register and go to the label
     *       "b", loop body.
     * </ul>
     */
    CONTINUE(
        BcInstr.CONTINUE,
        // Iterator next value.
        BcInstrOperand.OUT_SLOT,
        // Beginning of the loop
        BcInstrOperand.addr("b"),
        // End of the loop
        BcInstrOperand.addr("e")),
    /**
     * Exit the loop: unlock the iterable, pop it from the loop stack and goto a label after the
     * loop.
     */
    BREAK(BcInstr.BREAK, BcInstrOperand.addr("e")),
    /** List constructor. */
    LIST(
        BcInstr.LIST,
        // List size followed by list items.
        BcInstrOperand.lengthDelimited(BcInstrOperand.IN_SLOT),
        BcInstrOperand.OUT_SLOT),
    /** Tuple constructor; similar to the list constructor above. */
    TUPLE(
        BcInstr.TUPLE,
        BcInstrOperand.lengthDelimited(BcInstrOperand.IN_SLOT),
        BcInstrOperand.OUT_SLOT),
    /** Dict constructor. */
    DICT(
        BcInstr.DICT,
        BcInstrOperand.lengthDelimited(
            BcInstrOperand.fixed(BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT)),
        BcInstrOperand.OUT_SLOT),
    /** {@code a0.append(a1)}. */
    LIST_APPEND(BcInstr.LIST_APPEND, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT),
    /** {@code a0[a1] = a2}. */
    SET_INDEX(
        BcInstr.SET_INDEX, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT),
    /** {@code (a1[0], a1[1], a1[2], ...) = a0}. */
    UNPACK(
        BcInstr.UNPACK,
        BcInstrOperand.IN_SLOT,
        BcInstrOperand.lengthDelimited(BcInstrOperand.OUT_SLOT)),
    /** Load statement. */
    LOAD_STMT(BcInstr.LOAD_STMT,
        // LoadStatement object.
        BcInstrOperand.OBJECT),
    /** Throw an {@code EvalException} on execution of this instruction. */
    EVAL_EXCEPTION(BcInstr.EVAL_EXCEPTION, BcInstrOperand.STRING),
    ;

    /** Type of opcode operands. */
    final BcInstrOperand.Operands operands;

    Opcode(int opcode, BcInstrOperand.Operands... operands) {
      this(opcode, operands.length != 1 ? BcInstrOperand.fixed(operands) : operands[0]);
    }

    Opcode(int opcode, BcInstrOperand.Operands operands) {
      // We maintain the invariant: the opcode is equal to enum variant ordinal.
      // It is a bit inconvenient to maintain, but make is much easier/safer to work with.
      Preconditions.checkState(
          opcode == ordinal(),
          String.format("wrong index for %s: expected %s, actual %s", name(), ordinal(), opcode));
      this.operands = operands;
    }

    private static Opcode[] values = values();

    static Opcode fromInt(int opcode) {
      return values[opcode];
    }
  }

  /** Partially-decoded instruction. Used in tests. */
  static class Decoded {
    final Opcode opcode;
    final BcInstrOperand.Operands.Decoded args;

    Decoded(Opcode opcode, BcInstrOperand.Operands.Decoded args) {
      this.opcode = opcode;
      this.args = args;
    }

    BcInstrOperand.Operands.Decoded getArg(int i) {
      BcInstrOperand.FixedOperandsOpcode.Decoded args = (BcInstrOperand.FixedOperandsOpcode.Decoded) this.args;
      return args.operands.get(i);
    }

    int getArgObject(int i) {
      BcInstrOperand.Operands.Decoded arg = getArg(i);
      return ((BcInstrOperand.ObjectArg.Decoded) arg).index;
    }
  }
}
