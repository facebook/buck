package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.TokenKind;

/** IR instructions are subclasses of this class. */
abstract class BcIrInstr {

  /** Marker for instructions affecting control flow. */
  interface Flow {}

  private BcIrInstr() {}

  @Override
  public final String toString() {
    return Arrays.stream(argsForToString()).map(Objects::toString).collect(Collectors.joining(" "));
  }

  /** To string for an instruction is space-separated arguments. */
  protected abstract Object[] argsForToString();

  /**
   * Serialize this instruction.
   */
  abstract void write(BcIrWriteContext writeContext);

  static class Cp extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot src;
    final BcIrSlot.AnyLocal dest;

    Cp(BcWriter.LocOffset locOffset, BcIrSlot src, BcIrSlot.AnyLocal dest) {
      this.locOffset = locOffset;
      this.src = src;
      this.dest = dest;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.CP, src, dest};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      BcInstr.Opcode opcode;
      if (src instanceof BcIrSlot.AnyLocal) {
        opcode = BcInstr.Opcode.CP_LOCAL;
      } else {
        opcode = BcInstr.Opcode.CP;
      }
      writeContext.writer.write(
          opcode, locOffset, src.encode(writeContext), dest.encode(writeContext));
    }
  }

  static class SetGlobal extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot rhs;
    final int globalIndex;
    final String name;
    final boolean postAssignHook;

    public SetGlobal(
        BcWriter.LocOffset locOffset,
        BcIrSlot rhs,
        int globalIndex,
        String name,
        boolean postAssignHook) {
      this.locOffset = locOffset;
      this.rhs = rhs;
      this.globalIndex = globalIndex;
      this.name = name;
      this.postAssignHook = postAssignHook;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.SET_GLOBAL, rhs, globalIndex, name, postAssignHook};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.SET_GLOBAL,
          locOffset,
          rhs.encode(writeContext),
          globalIndex,
          writeContext.writer.allocString(name),
          postAssignHook ? 1 : 0);
    }
  }

  static class SetCell extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot rhs;
    final int cellIndex;

    SetCell(BcWriter.LocOffset locOffset, BcIrSlot rhs, int cellIndex) {
      this.locOffset = locOffset;
      this.rhs = rhs;
      this.cellIndex = cellIndex;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.SET_CELL, rhs, cellIndex};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.SET_CELL, locOffset, rhs.encode(writeContext), cellIndex);
    }
  }

  static class List extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrListArg arg;
    final BcIrSlot.AnyLocal result;

    List(BcWriter.LocOffset locOffset, BcIrListArg arg, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.arg = arg;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.LIST, arg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] args = BcWriter.args(arg.encode(writeContext), result.encode(writeContext));
      writeContext.writer.write(BcInstr.Opcode.LIST, locOffset, args);
    }
  }

  static class Tuple extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrListArg arg;
    final BcIrSlot.AnyLocal result;

    Tuple(BcWriter.LocOffset locOffset, BcIrListArg arg, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.arg = arg;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.TUPLE, arg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] args = BcWriter.args(arg.encode(writeContext), result.encode(writeContext));
      writeContext.writer.write(BcInstr.Opcode.TUPLE, locOffset, args);
    }
  }

  static class Dict extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final ImmutableList<BcIrSlot> arg;
    final BcIrSlot.AnyLocal result;

    Dict(BcWriter.LocOffset locOffset, ImmutableList<BcIrSlot> arg, BcIrSlot.AnyLocal result) {
      Preconditions.checkArgument(arg.size() % 2 == 0);
      this.locOffset = locOffset;
      this.arg = arg;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.DICT, arg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] args = new int[2 + arg.size()];
      int i = 0;
      args[i++] = arg.size() / 2;
      for (BcIrSlot slot : arg) {
        args[i++] = slot.encode(writeContext);
      }
      args[i++] = result.encode(writeContext);
      Preconditions.checkState(i == args.length);
      writeContext.writer.write(BcInstr.Opcode.DICT, locOffset, args);
    }
  }

  static class Slice extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot object;
    final BcIrSlotOrNull start;
    final BcIrSlotOrNull stop;
    final BcIrSlotOrNull step;
    final BcIrSlot.AnyLocal result;

    Slice(
        BcWriter.LocOffset locOffset,
        BcIrSlot object,
        BcIrSlotOrNull start,
        BcIrSlotOrNull stop,
        BcIrSlotOrNull step,
        BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.object = object;
      this.start = start;
      this.stop = stop;
      this.step = step;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.SLICE, object, start, stop, step};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.SLICE,
          locOffset,
          object.encode(writeContext),
          start.encode(writeContext),
          stop.encode(writeContext),
          step.encode(writeContext),
          result.encode(writeContext));
    }
  }

  static class Index extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot object;
    final BcIrSlot index;
    final BcIrSlot.AnyLocal result;

    Index(BcWriter.LocOffset locOffset, BcIrSlot object, BcIrSlot index, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.object = object;
      this.index = index;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.INDEX, object, index, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.INDEX,
          locOffset,
          object.encode(writeContext),
          index.encode(writeContext),
          result.encode(writeContext));
    }
  }

  static class Dot extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot object;
    final String field;
    final BcIrSlot.AnyLocal result;

    public Dot(
        BcWriter.LocOffset locOffset, BcIrSlot object, String field, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.object = object;
      this.field = field;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.DOT, object, field, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.DOT,
          locOffset,
          object.encode(writeContext),
          writeContext.writer.allocString(field),
          result.encode(writeContext));
    }
  }

  static class SetIndex extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot lhs;
    final BcIrSlot index;
    final BcIrSlot rhs;

    SetIndex(BcWriter.LocOffset locOffset, BcIrSlot lhs, BcIrSlot index, BcIrSlot rhs) {
      this.locOffset = locOffset;
      this.lhs = lhs;
      this.index = index;
      this.rhs = rhs;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.SET_INDEX, lhs, index, rhs};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.SET_INDEX,
          locOffset,
          lhs.encode(writeContext),
          index.encode(writeContext),
          rhs.encode(writeContext));
    }
  }

  static class ListAppend extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot list;
    final BcIrSlot item;

    ListAppend(BcWriter.LocOffset locOffset, BcIrSlot list, BcIrSlot item) {
      this.locOffset = locOffset;
      this.list = list;
      this.item = item;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.LIST_APPEND, list, item};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.LIST_APPEND,
          locOffset,
          list.encode(writeContext),
          item.encode(writeContext));
    }
  }

  /** Binary operation operator. */
  enum BinOpOp {
    EQ(BcInstr.Opcode.EQ, TokenKind.EQUALS_EQUALS),
    NOT_EQ(BcInstr.Opcode.NOT_EQ, TokenKind.NOT_EQUALS),
    PLUS(BcInstr.Opcode.PLUS, TokenKind.PLUS),
    PLUS_STRING(BcInstr.Opcode.PLUS_STRING),
    PLUS_STRING_IN_PLACE(BcInstr.Opcode.PLUS_STRING_IN_PLACE),
    PLUS_IN_PLACE(BcInstr.Opcode.PLUS_IN_PLACE),
    IN(BcInstr.Opcode.IN, TokenKind.IN),
    NOT_IN(BcInstr.Opcode.NOT_IN, TokenKind.NOT_IN),
    PERCENT(TokenKind.PERCENT),
    STAR(TokenKind.STAR),
    MINUS(TokenKind.MINUS),
    SLASH(TokenKind.SLASH),
    SLASH_SLASH(TokenKind.SLASH_SLASH),
    LESS(TokenKind.LESS),
    LESS_EQUAL(TokenKind.LESS_EQUALS),
    GREATER(TokenKind.GREATER),
    GREATER_EQUAL(TokenKind.GREATER_EQUALS),
    LESS_LESS(TokenKind.LESS_LESS),
    GREATER_GREATER(TokenKind.GREATER_GREATER),
    AMPERSAND(TokenKind.AMPERSAND),
    CARET(TokenKind.CARET),
    PIPE(TokenKind.PIPE),
    ;
    final BcInstr.Opcode opcode;
    @Nullable final TokenKind tokenKind;

    private static class MapFromTokenKind {
      private static final BinOpOp[] MAP = new BinOpOp[TokenKind.values().length];
    }

    BinOpOp(BcInstr.Opcode opcode, TokenKind tokenKind) {
      Preconditions.checkState(MapFromTokenKind.MAP[tokenKind.ordinal()] == null);
      MapFromTokenKind.MAP[tokenKind.ordinal()] = this;
      this.opcode = opcode;
      this.tokenKind = tokenKind;
    }

    BinOpOp(TokenKind tokenKind) {
      this(BcInstr.Opcode.BINARY, tokenKind);
    }

    BinOpOp(BcInstr.Opcode opcode) {
      this.opcode = opcode;
      this.tokenKind = null;
    }

    /** Constructor for given token. */
    static BinOpOp fromToken(TokenKind tokenKind) {
      BinOpOp binOpOp = MapFromTokenKind.MAP[tokenKind.ordinal()];
      Preconditions.checkArgument(binOpOp != null, "unknown bin op: %s", tokenKind);
      return binOpOp;
    }
  }

  static class BinOp extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BinOpOp op;
    final BcIrSlot lhs;
    final BcIrSlot rhs;
    final BcIrSlot.AnyLocal result;

    BinOp(
        BcWriter.LocOffset locOffset,
        BinOpOp op,
        BcIrSlot lhs,
        BcIrSlot rhs,
        BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.op = op;
      this.lhs = lhs;
      this.rhs = rhs;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {op, lhs, rhs, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      if (op.opcode == BcInstr.Opcode.BINARY) {
        writeContext.writer.write(
            BcInstr.Opcode.BINARY,
            locOffset,
            lhs.encode(writeContext),
            rhs.encode(writeContext),
            op.tokenKind.ordinal(),
            result.encode(writeContext));
      } else {
        writeContext.writer.write(
            op.opcode,
            locOffset,
            lhs.encode(writeContext),
            rhs.encode(writeContext),
            result.encode(writeContext));
      }
    }
  }

  enum UnOpOp {
    NOT(TokenKind.NOT),
    PLUS(TokenKind.PLUS),
    MINUS(TokenKind.MINUS),
    TILDE(TokenKind.TILDE),
    ;
    final TokenKind tokenKind;

    private static class MapFromTokenKind {
      private static final UnOpOp[] MAP = new UnOpOp[TokenKind.values().length];
    }

    UnOpOp(TokenKind tokenKind) {
      Preconditions.checkState(MapFromTokenKind.MAP[tokenKind.ordinal()] == null);
      MapFromTokenKind.MAP[tokenKind.ordinal()] = this;
      this.tokenKind = tokenKind;
    }

    static UnOpOp fromToken(TokenKind tokenKind) {
      UnOpOp unOpOp = MapFromTokenKind.MAP[tokenKind.ordinal()];
      Preconditions.checkArgument(unOpOp != null, "unknown un op: %s", tokenKind);
      return unOpOp;
    }
  }

  static class UnOp extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final UnOpOp op;
    final BcIrSlot arg;
    final BcIrSlot.AnyLocal result;

    UnOp(BcWriter.LocOffset locOffset, UnOpOp op, BcIrSlot arg, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.op = op;
      this.arg = arg;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {op, arg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      switch (op) {
        case NOT:
          writeContext.writer.write(
              BcInstr.Opcode.NOT, locOffset, arg.encode(writeContext), result.encode(writeContext));
          break;
        default:
          writeContext.writer.write(
              BcInstr.Opcode.UNARY,
              locOffset,
              arg.encode(writeContext),
              op.tokenKind.ordinal(),
              result.encode(writeContext));
          break;
      }
    }
  }

  static class Br extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;
    final JumpLabel jumpLabel;

    Br(BcWriter.LocOffset locOffset, JumpLabel jumpLabel) {
      this.locOffset = locOffset;
      this.jumpLabel = jumpLabel;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.BR, jumpLabel};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writeForwardJump(locOffset, jumpLabel);
    }
  }

  /** Conditional forward jump. */
  static class IfBr extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot.AnyLocal cond;
    final BcWriter.JumpCond jumpCond;
    final JumpLabel jumpLabel;

    IfBr(
        BcWriter.LocOffset locOffset,
        BcIrSlot.AnyLocal cond,
        BcWriter.JumpCond jumpCond,
        JumpLabel jumpLabel) {
      this.locOffset = locOffset;
      this.cond = cond;
      this.jumpCond = jumpCond;
      this.jumpLabel = jumpLabel;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {jumpCond.opcode, cond, jumpLabel};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writeForwardCondJump(locOffset, jumpCond, cond, jumpLabel);
    }

  }

  /** Jump label. Patches previously written jumps on serialization. Generates no bytecode. */
  static class JumpLabel extends BcIrInstr implements Flow {
    JumpLabel(BcIr.Friend friend) {
      friend.markUsed();
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {JumpLabel.class.getSimpleName(), System.identityHashCode(this)};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.patchForwardJump(this);
    }

  }

  /** For loop initialization routine. */
  static class ForInit extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot collection;
    /** A slot to store next collection item. */
    private final BcIrSlot.AnyLocal item;

    public ForInit(BcWriter.LocOffset locOffset, BcIrSlot collection, BcIrSlot.AnyLocal item) {
      this.locOffset = locOffset;
      this.collection = collection;
      this.item = item;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.FOR_INIT, collection, item};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeForInit(
          locOffset, collection.encode(writeContext), item.encode(writeContext));
    }
  }

  /**
   * Instruction to close the for loop.
   *
   * <p>This instruction produces no bytecode, only updates bytecode writer state on serialization.
   */
  static class ForClose extends BcIrInstr implements Flow {
    private ForClose() {}

    @Override
    protected Object[] argsForToString() {
      return new Object[] {ForClose.class.getSimpleName()};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeForClose();
    }

    static final ForClose FOR_CLOSE = new ForClose();
  }

  static class Break extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;

    public Break(BcWriter.LocOffset locOffset) {
      this.locOffset = locOffset;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.BREAK};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeBreak(locOffset);
    }
  }

  static class Continue extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;

    Continue(BcWriter.LocOffset locOffset) {
      this.locOffset = locOffset;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.CONTINUE};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeContinue(locOffset);
    }
  }

  static class PlusList extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot lhs;
    final BcIrListArg rhs;
    final BcIrSlot.AnyLocal result;

    public PlusList(
        BcWriter.LocOffset locOffset, BcIrSlot lhs, BcIrListArg rhs, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.lhs = lhs;
      this.rhs = rhs;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.PLUS_LIST, lhs, rhs, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.PLUS_LIST,
          locOffset,
          BcWriter.args(
              new int[] {lhs.encode(writeContext)},
              rhs.encode(writeContext),
              new int[] {result.encode(writeContext)}));
    }
  }

  static class PlusListInPlace extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot lhs;
    final BcIrListArg rhs;
    final BcIrSlot.AnyLocal result;

    public PlusListInPlace(
        BcWriter.LocOffset locOffset, BcIrSlot lhs, BcIrListArg rhs, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.lhs = lhs;
      this.rhs = rhs;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.PLUS_LIST_IN_PLACE, lhs, rhs, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.PLUS_LIST_IN_PLACE,
          locOffset,
          BcWriter.args(
              new int[] {lhs.encode(writeContext)},
              rhs.encode(writeContext),
              new int[] {result.encode(writeContext)}));
    }
  }

  static class TypeIs extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot.AnyLocal expr;
    final String type;
    final BcIrSlot.AnyLocal result;

    public TypeIs(
        BcWriter.LocOffset locOffset,
        BcIrSlot.AnyLocal expr,
        String type,
        BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.expr = expr;
      this.type = type;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.TYPE_IS, expr, type, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.TYPE_IS,
          locOffset,
          expr.encode(writeContext),
          writeContext.writer.allocString(type),
          result.encode(writeContext));
    }
  }

  static class PercentSOne extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final String format;
    final int indexOfPercent;
    final BcIrSlot arg;
    final boolean tuple;
    final BcIrSlot.AnyLocal result;

    PercentSOne(
        BcWriter.LocOffset locOffset,
        String format,
        int indexOfPercent,
        BcIrSlot arg,
        boolean tuple,
        BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.format = format;
      this.indexOfPercent = indexOfPercent;
      this.arg = arg;
      this.tuple = tuple;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {opcode(), format, indexOfPercent, arg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      BcInstr.Opcode opcode = opcode();
      writeContext.writer.write(
          opcode,
          locOffset,
          writeContext.writer.allocString(format),
          indexOfPercent,
          arg.encode(writeContext),
          result.encode(writeContext));
    }

    private BcInstr.Opcode opcode() {
      return tuple ? BcInstr.Opcode.PERCENT_S_ONE_TUPLE : BcInstr.Opcode.PERCENT_S_ONE;
    }
  }

  static class Return extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot value;

    Return(BcWriter.LocOffset locOffset, BcIrSlot value) {
      this.locOffset = locOffset;
      this.value = value;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.RETURN, value};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      if (value instanceof BcIrSlot.Const && ((BcIrSlot.Const) value).value == Starlark.NONE) {
        writeContext.writer.write(BcInstr.Opcode.RETURN, locOffset, BcSlot.NULL_FLAG);
      } else {
        writeContext.writer.write(BcInstr.Opcode.RETURN, locOffset, value.encode(writeContext));
      }
    }
  }

  static class EvalException extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;
    final String message;

    public EvalException(BcWriter.LocOffset locOffset, String message) {
      this.locOffset = locOffset;
      this.message = message;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.EVAL_EXCEPTION, message};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeEvalException(locOffset, message);
    }
  }

  static class Call extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcCallLocs callLocs;
    final BcIrSlot fn;
    final StarlarkCallableLinkSig linkSig;
    final BcIrListArg listArg;
    final BcIrSlotOrNull starArg;
    final BcIrSlotOrNull starStarArg;
    final BcIrSlot.AnyLocal result;

    public Call(
        BcWriter.LocOffset locOffset,
        BcCallLocs callLocs,
        BcIrSlot fn,
        StarlarkCallableLinkSig linkSig,
        BcIrListArg listArg,
        BcIrSlotOrNull starArg,
        BcIrSlotOrNull starStarArg,
        BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.callLocs = callLocs;
      this.fn = fn;
      this.linkSig = linkSig;
      this.listArg = listArg;
      this.starArg = starArg;
      this.starStarArg = starStarArg;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.CALL, fn, linkSig, listArg, starArg, starStarArg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] args =
          BcWriter.args(
              new int[] {
                writeContext.writer.allocObject(callLocs),
                fn.encode(writeContext),
                writeContext.writer.allocObject(new BcDynCallSite(linkSig)),
              },
              listArg.encode(writeContext),
              new int[] {
                starArg.encode(writeContext),
                starStarArg.encode(writeContext),
                result.encode(writeContext),
              });

      writeContext.writer.write(BcInstr.Opcode.CALL, locOffset, args);
    }
  }

  static class CallLinked extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcCallLocs callLocs;
    final StarlarkCallableLinked fn;
    final BcIrListArg listArg;
    final BcIrSlotOrNull starArg;
    final BcIrSlotOrNull starStarArg;
    final BcIrSlot.AnyLocal result;

    public CallLinked(
        BcWriter.LocOffset locOffset,
        BcCallLocs callLocs,
        StarlarkCallableLinked fn,
        BcIrListArg listArg,
        BcIrSlotOrNull starArg,
        BcIrSlotOrNull starStarArg,
        BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.callLocs = callLocs;
      this.fn = fn;
      this.listArg = listArg;
      this.starArg = starArg;
      this.starStarArg = starStarArg;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.CALL_LINKED, fn, listArg, starArg, starStarArg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] newArgs =
          BcWriter.args(
              new int[] {
                writeContext.writer.allocObject(callLocs), writeContext.writer.allocObject(fn)
              },
              listArg.encode(writeContext),
              new int[] {
                starArg.encode(writeContext),
                starStarArg.encode(writeContext),
                result.encode(writeContext),
              });
      writeContext.writer.write(BcInstr.Opcode.CALL_LINKED, locOffset, newArgs);
    }
  }

  static class CallCached extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcCallCached callCached;
    final BcIrSlot.AnyLocal result;

    CallCached(BcWriter.LocOffset locOffset, BcCallCached callCached, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.callCached = callCached;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.CALL_CACHED, callCached, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.CALL_CACHED,
          locOffset,
          writeContext.writer.allocObject(callCached),
          result.encode(writeContext));
    }
  }

  static class NewFunction extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final Resolver.Function rfn;
    final ImmutableList<BcIrSlot> defaults;
    final BcIrSlot.AnyLocal result;

    public NewFunction(
        BcWriter.LocOffset locOffset,
        Resolver.Function rfn,
        ImmutableList<BcIrSlot> defaults,
        BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.rfn = rfn;
      this.defaults = defaults;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.NEW_FUNCTION, rfn.getName(), result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] args = new int[3 + defaults.size()];
      int i = 0;
      args[i++] = writeContext.writer.allocObject(rfn);
      args[i++] = defaults.size();

      for (int p = 0; p < defaults.size(); ++p) {
        args[i++] = defaults.get(p).encode(writeContext);
      }

      args[i++] = result.encode(writeContext);
      Preconditions.checkState(i == args.length);

      writeContext.writer.write(BcInstr.Opcode.NEW_FUNCTION, locOffset, args);
    }
  }

  static class LoadStmt extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final LoadStatement loadStmt;

    public LoadStmt(BcWriter.LocOffset locOffset, LoadStatement loadStmt) {
      this.locOffset = locOffset;
      this.loadStmt = loadStmt;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {loadStmt};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstr.Opcode.LOAD_STMT, locOffset, writeContext.writer.allocObject(loadStmt));
    }
  }

  static class Unpack extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot rhs;
    final ImmutableList<BcIrSlot.AnyLocal> lhs;

    public Unpack(
        BcWriter.LocOffset locOffset, BcIrSlot rhs, ImmutableList<BcIrSlot.AnyLocal> lhs) {
      this.locOffset = locOffset;
      this.rhs = rhs;
      this.lhs = lhs;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstr.Opcode.UNPACK, rhs, lhs};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] args = new int[lhs.size() + 2];
      int i = 0;
      args[i++] = rhs.encode(writeContext);
      args[i++] = lhs.size();
      for (BcIrSlot.AnyLocal lhs : lhs) {
        args[i++] = lhs.encode(writeContext);
      }
      Preconditions.checkState(i == args.length);
      writeContext.writer.write(BcInstr.Opcode.UNPACK, locOffset, args);
    }
  }

  /**
   * An instruction to allocate a slot number. This instruction populates lazy slot object with slot
   * number, and generates no bytecode.
   */
  static class AllocSlot extends BcIrInstr {
    final BcIrSlot.LazyLocal lazyLocal;

    AllocSlot(BcIrSlot.LazyLocal lazyLocal, BcIr.Friend friend) {
      friend.markUsed();
      this.lazyLocal = lazyLocal;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {AllocSlot.class.getSimpleName(), lazyLocal};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      lazyLocal.init(writeContext);
    }
  }
}
