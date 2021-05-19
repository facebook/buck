package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import net.starlark.java.annot.internal.BcOpcodeNumber;

/**
 * Opcodes as enum. We use enums in the compiler, but we use only raw integers in the interpreter.
 *
 * <p>Enums are much nicer to work with, but they are much more expensive. Thus we use enums only in
 * the compiler, or during debugging.
 */
enum BcInstrOpcode {
  /** {@code a1 = a0}. */
  CP(BcOpcodeNumber.CP, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
  /** Similar to {@link #CP} but assumes in slot is local. */
  CP_LOCAL(BcOpcodeNumber.CP_LOCAL, BcInstrOperand.IN_LOCAL, BcInstrOperand.OUT_SLOT),
  /** {@code return a0} */
  RETURN(BcOpcodeNumber.RETURN, BcInstrOperand.IN_SLOT),
  /** Goto. */
  BR(BcOpcodeNumber.BR, BcInstrOperand.ADDR),
  /** Goto if. */
  IF_BR_LOCAL(BcOpcodeNumber.IF_BR_LOCAL, BcInstrOperand.IN_LOCAL, BcInstrOperand.ADDR),
  /** Goto if not. */
  IF_NOT_BR_LOCAL(BcOpcodeNumber.IF_NOT_BR_LOCAL, BcInstrOperand.IN_LOCAL, BcInstrOperand.ADDR),
  /** Goto if type is. */
  IF_TYPE_IS_BR(
      BcOpcodeNumber.IF_TYPE_IS_BR,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.STRING,
      BcInstrOperand.ADDR),
  /** Goto if type is not. */
  IF_NOT_TYPE_IS_BR(
      BcOpcodeNumber.IF_NOT_TYPE_IS_BR,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.STRING,
      BcInstrOperand.ADDR),
  /** Goto if equal. */
  IF_EQ_BR(
      BcOpcodeNumber.IF_EQ_BR, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.ADDR),
  /** Goto if not equal. */
  IF_NOT_EQ_BR(
      BcOpcodeNumber.IF_NOT_EQ_BR,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.ADDR),
  /** Goto if in. */
  IF_IN_BR(
      BcOpcodeNumber.IF_IN_BR, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.ADDR),
  /** Goto if not in. */
  IF_NOT_IN_BR(
      BcOpcodeNumber.IF_NOT_IN_BR,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.ADDR),
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
      BcOpcodeNumber.FOR_INIT,
      // Collection parameter
      BcInstrOperand.IN_SLOT,
      // Next value register
      BcInstrOperand.OUT_SLOT,
      BcInstrOperand.ADDR),
  /**
   * Continue the loop:
   *
   * <ul>
   *   <li>If current iterator (stored on the stack) is empty, unlock the iterable and pop iterable
   *       and iterable from the stack and go to the label "e" after the end of the loop.
   *   <li>Otherwise assign the next iterator item to the provided register and go to the label "b",
   *       loop body.
   * </ul>
   */
  CONTINUE(
      BcOpcodeNumber.CONTINUE,
      // Iterator next value.
      BcInstrOperand.OUT_SLOT,
      // Beginning of the loop
      BcInstrOperand.ADDR,
      // End of the loop
      BcInstrOperand.ADDR),
  /**
   * Exit the loop: unlock the iterable, pop it from the loop stack and goto a label after the loop.
   */
  BREAK(BcOpcodeNumber.BREAK, BcInstrOperand.ADDR),
  /**
   * {@code a1 = not a0}.
   *
   * <p>This could be handled by generic UNARY opcode, but it is specialized for performance.
   */
  NOT(BcOpcodeNumber.NOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
  /** {@code a2 = (a1) a0}. */
  UNARY(
      BcOpcodeNumber.UNARY,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.TOKEN_KIND,
      BcInstrOperand.OUT_SLOT),
  /**
   * {@code a2 = a0 == a1}. This is quite common operation, which deserves its own opcode to avoid
   * switching in generic binary operator handling.
   */
  EQ(BcOpcodeNumber.EQ, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
  /**
   * {@code a2 = a0 != a1}. This is quite common operation, which deserves its own opcode to avoid
   * switching in generic binary operator handling.
   */
  NOT_EQ(
      BcOpcodeNumber.NOT_EQ,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.OUT_SLOT),
  /** {@code a2 = a0 in a1}. */
  IN(BcOpcodeNumber.IN, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
  /** {@code a2 = a0 not in a1}. */
  NOT_IN(
      BcOpcodeNumber.NOT_IN,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.OUT_SLOT),
  /** {@code a2 = a0 + a1}. */
  PLUS(
      BcOpcodeNumber.PLUS, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OUT_SLOT),
  /** {@code a2 = a0 + a1}. */
  PLUS_STRING(
      BcOpcodeNumber.PLUS_STRING,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.OUT_SLOT),
  /** {@code a2 = a0 + a1}. */
  PLUS_LIST(
      BcOpcodeNumber.PLUS_LIST,
      // lhs
      BcInstrOperand.IN_SLOT,
      // rhs list elements
      BcInstrOperand.IN_LIST,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  /** "aaa%sbbb" % arg */
  PERCENT_S_ONE(
      BcOpcodeNumber.PERCENT_S_ONE,
      // format
      BcInstrOperand.STRING,
      // index of %s
      BcInstrOperand.NUMBER,
      // param
      BcInstrOperand.IN_SLOT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  /** "aaa%sbbb" % (arg,) */
  PERCENT_S_ONE_TUPLE(
      BcOpcodeNumber.PERCENT_S_ONE_TUPLE,
      // format
      BcInstrOperand.STRING,
      // index of %s
      BcInstrOperand.NUMBER,
      // param
      BcInstrOperand.IN_SLOT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  /** {@code a3 = a0 += a1}. */
  PLUS_IN_PLACE(
      BcOpcodeNumber.PLUS_IN_PLACE,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.OUT_SLOT),
  /** {@code a3 = a0 += a1}. */
  PLUS_STRING_IN_PLACE(
      BcOpcodeNumber.PLUS_STRING_IN_PLACE,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.OUT_SLOT),
  /** {@code a3 = a0 += [a1...]}. */
  PLUS_LIST_IN_PLACE(
      BcOpcodeNumber.PLUS_LIST_IN_PLACE,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_LIST,
      BcInstrOperand.OUT_SLOT),
  /** {@code a2 = type(a0) == a1} */
  TYPE_IS(
      BcOpcodeNumber.TYPE_IS,
      BcInstrOperand.IN_LOCAL,
      BcInstrOperand.STRING,
      BcInstrOperand.OUT_SLOT),
  /** {@code a3 = a0 (a2) a1}. */
  BINARY(
      BcOpcodeNumber.BINARY,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.TOKEN_KIND,
      BcInstrOperand.OUT_SLOT),
  /** Assign a value without destructuring to a global variable. */
  SET_GLOBAL(
      BcOpcodeNumber.SET_GLOBAL,
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
      BcOpcodeNumber.SET_CELL,
      // value
      BcInstrOperand.IN_SLOT,
      // cell index
      BcInstrOperand.NUMBER),
  /** {@code a2 = a0.a1} */
  DOT(BcOpcodeNumber.DOT, BcInstrOperand.IN_SLOT, BcInstrOperand.OBJECT, BcInstrOperand.OUT_SLOT),
  /** {@code a2 = a0[a1]} */
  INDEX(
      BcOpcodeNumber.INDEX,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.OUT_SLOT),
  /** {@code a4 = a0[a1:a2:a3]} */
  SLICE(
      BcOpcodeNumber.SLICE,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.OUT_SLOT),
  /** Generic call invocation. */
  CALL(
      BcOpcodeNumber.CALL,
      // Function
      BcInstrOperand.IN_SLOT,
      // BcDynCallSite
      BcInstrOperand.OBJECT,
      // Positional arguments followed by named parameters
      BcInstrOperand.IN_LIST,
      // *args
      BcInstrOperand.IN_SLOT,
      // **kwargs
      BcInstrOperand.IN_SLOT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  CALL_1(
      BcOpcodeNumber.CALL_1,
      // Function
      BcInstrOperand.IN_SLOT,
      // BcDynCallSite
      BcInstrOperand.OBJECT,
      // arg0
      BcInstrOperand.IN_SLOT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  CALL_2(
      BcOpcodeNumber.CALL_2,
      // Function
      BcInstrOperand.IN_SLOT,
      // BcDynCallSite
      BcInstrOperand.OBJECT,
      // arg0
      BcInstrOperand.IN_SLOT,
      // arg1
      BcInstrOperand.IN_SLOT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  CALL_LINKED(
      BcOpcodeNumber.CALL_LINKED,
      // BcCallLocs
      BcInstrOperand.OBJECT,
      // StarlarkCallableLinked
      BcInstrOperand.OBJECT,
      // Positional args followed by named args, no keys
      BcInstrOperand.IN_LIST,
      // *args
      BcInstrOperand.IN_SLOT,
      // **kwargs
      BcInstrOperand.IN_SLOT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  CALL_LINKED_1(
      BcOpcodeNumber.CALL_LINKED_1,
      // BcCallLocs
      BcInstrOperand.OBJECT,
      // StarlarkCallableLinked
      BcInstrOperand.OBJECT,
      // arg0
      BcInstrOperand.IN_SLOT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  CALL_LINKED_2(
      BcOpcodeNumber.CALL_LINKED_2,
      // BcCallLocs
      BcInstrOperand.OBJECT,
      // StarlarkCallableLinked
      BcInstrOperand.OBJECT,
      // arg0
      BcInstrOperand.IN_SLOT,
      // arg1
      BcInstrOperand.IN_SLOT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  CALL_CACHED(
      BcOpcodeNumber.CALL_CACHED,
      // BcCallCached
      BcInstrOperand.OBJECT,
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  /** List constructor. */
  LIST(
      BcOpcodeNumber.LIST,
      // List size followed by list items.
      BcInstrOperand.IN_LIST,
      BcInstrOperand.OUT_SLOT),
  /** Tuple constructor; similar to the list constructor above. */
  TUPLE(BcOpcodeNumber.TUPLE, BcInstrOperand.IN_LIST, BcInstrOperand.OUT_SLOT),
  /** Dict constructor. */
  DICT(
      BcOpcodeNumber.DICT,
      BcInstrOperand.lengthDelimited(
          BcInstrOperand.fixed(BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT)),
      BcInstrOperand.OUT_SLOT),
  /** {@code a0.append(a1)}. */
  LIST_APPEND(BcOpcodeNumber.LIST_APPEND, BcInstrOperand.IN_SLOT, BcInstrOperand.IN_SLOT),
  /** {@code a0[a1] = a2}. */
  SET_INDEX(
      BcOpcodeNumber.SET_INDEX,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.IN_SLOT),
  /** {@code (a1[0], a1[1], a1[2], ...) = a0}. */
  UNPACK(
      BcOpcodeNumber.UNPACK,
      BcInstrOperand.IN_SLOT,
      BcInstrOperand.lengthDelimited(BcInstrOperand.OUT_SLOT)),
  /** Create a new function. */
  NEW_FUNCTION(
      BcOpcodeNumber.NEW_FUNCTION,
      // Resolver.Function
      BcInstrOperand.OBJECT,
      // Function default values
      BcInstrOperand.lengthDelimited(BcInstrOperand.IN_SLOT),
      // Where to store result
      BcInstrOperand.OUT_SLOT),
  /** Load statement. */
  LOAD_STMT(
      BcOpcodeNumber.LOAD_STMT,
      // LoadStatement object.
      BcInstrOperand.OBJECT),
  /** Throw an {@code EvalException} on execution of this instruction. */
  EVAL_EXCEPTION(BcOpcodeNumber.EVAL_EXCEPTION, BcInstrOperand.STRING),
  ;

  /** Type of opcode operands. */
  final BcInstrOperand.Operands operands;

  BcInstrOpcode(BcOpcodeNumber opcode, BcInstrOperand.Operands... operands) {
    this(opcode, operands.length != 1 ? BcInstrOperand.fixed(operands) : operands[0]);
  }

  BcInstrOpcode(BcOpcodeNumber opcode, BcInstrOperand.Operands operands) {
    // We maintain the invariant: the opcode is equal to enum variant ordinal.
    // It is a bit inconvenient to maintain, but make is much easier/safer to work with.
    Preconditions.checkState(
        opcode.ordinal() == ordinal(),
        String.format("opcode numbers mismatch: expected %s, actual %s", this, opcode));
    this.operands = operands;
  }

  private static final BcInstrOpcode[] values = values();

  static BcInstrOpcode fromInt(int opcode) {
    return values[opcode];
  }

  static BcInstrOpcode fromNumber(BcOpcodeNumber number) {
    return fromInt(number.ordinal());
  }

  /** Partially-decoded instruction. Used in tests. */
  static class Decoded {
    final BcInstrOpcode opcode;
    final BcInstrOperand.Operands.Decoded args;

    Decoded(BcInstrOpcode opcode, BcInstrOperand.Operands.Decoded args) {
      this.opcode = opcode;
      this.args = args;
    }

    BcInstrOperand.Operands.Decoded getArg(int i) {
      BcInstrOperand.FixedOperandsOpcode.Decoded args =
          (BcInstrOperand.FixedOperandsOpcode.Decoded) this.args;
      return args.operands.get(i);
    }

    int getArgObject(int i) {
      BcInstrOperand.Operands.Decoded arg = getArg(i);
      return ((BcInstrOperand.ObjectArg.Decoded) arg).index;
    }
  }
}
