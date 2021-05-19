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

  /** Serialize this instruction. */
  abstract void write(BcIrWriteContext writeContext);

  abstract void visitSlots(BcIrSlotVisitor visitor);

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
      return new Object[] {BcInstrOpcode.CP, src, dest};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      BcInstrOpcode opcode;
      if (src instanceof BcIrSlot.AnyLocal) {
        opcode = BcInstrOpcode.CP_LOCAL;
      } else {
        opcode = BcInstrOpcode.CP;
      }
      writeContext.writer.write(
          opcode, locOffset, src.encode(writeContext), dest.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(src);
      visitor.visitSlot(dest);
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
      return new Object[] {BcInstrOpcode.SET_GLOBAL, rhs, globalIndex, name, postAssignHook};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.SET_GLOBAL,
          locOffset,
          rhs.encode(writeContext),
          globalIndex,
          writeContext.writer.allocString(name),
          postAssignHook ? 1 : 0);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(rhs);
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
      return new Object[] {BcInstrOpcode.SET_CELL, rhs, cellIndex};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.SET_CELL, locOffset, rhs.encode(writeContext), cellIndex);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(rhs);
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
      return new Object[] {BcInstrOpcode.LIST, arg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] args = BcWriter.args(arg.encode(writeContext), result.encode(writeContext));
      writeContext.writer.write(BcInstrOpcode.LIST, locOffset, args);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlots(arg);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.TUPLE, arg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      int[] args = BcWriter.args(arg.encode(writeContext), result.encode(writeContext));
      writeContext.writer.write(BcInstrOpcode.TUPLE, locOffset, args);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlots(arg);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.DICT, arg, result};
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
      writeContext.writer.write(BcInstrOpcode.DICT, locOffset, args);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlots(arg);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.SLICE, object, start, stop, step};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.SLICE,
          locOffset,
          object.encode(writeContext),
          start.encode(writeContext),
          stop.encode(writeContext),
          step.encode(writeContext),
          result.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(object);
      visitor.visitSlot(start);
      visitor.visitSlot(stop);
      visitor.visitSlot(step);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.INDEX, object, index, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.INDEX,
          locOffset,
          object.encode(writeContext),
          index.encode(writeContext),
          result.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(object);
      visitor.visitSlot(index);
      visitor.visitSlot(result);
    }
  }

  static class Dot extends BcIrInstr {
    final BcWriter.LocOffset locOffset;
    final BcIrSlot object;
    final BcDotSite field;
    final BcIrSlot.AnyLocal result;

    public Dot(
        BcWriter.LocOffset locOffset, BcIrSlot object, BcDotSite field, BcIrSlot.AnyLocal result) {
      this.locOffset = locOffset;
      this.object = object;
      this.field = field;
      this.result = result;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstrOpcode.DOT, object, field, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.DOT,
          locOffset,
          object.encode(writeContext),
          writeContext.writer.allocObject(field),
          result.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(object);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.SET_INDEX, lhs, index, rhs};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.SET_INDEX,
          locOffset,
          lhs.encode(writeContext),
          index.encode(writeContext),
          rhs.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(lhs);
      visitor.visitSlot(index);
      visitor.visitSlot(rhs);
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
      return new Object[] {BcInstrOpcode.LIST_APPEND, list, item};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.LIST_APPEND,
          locOffset,
          list.encode(writeContext),
          item.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(list);
      visitor.visitSlot(item);
    }
  }

  /** Binary operation operator. */
  enum BinOpOp {
    EQ(BcInstrOpcode.EQ, TokenKind.EQUALS_EQUALS),
    NOT_EQ(BcInstrOpcode.NOT_EQ, TokenKind.NOT_EQUALS),
    PLUS(BcInstrOpcode.PLUS, TokenKind.PLUS),
    PLUS_STRING(BcInstrOpcode.PLUS_STRING),
    PLUS_STRING_IN_PLACE(BcInstrOpcode.PLUS_STRING_IN_PLACE),
    PLUS_IN_PLACE(BcInstrOpcode.PLUS_IN_PLACE),
    IN(BcInstrOpcode.IN, TokenKind.IN),
    NOT_IN(BcInstrOpcode.NOT_IN, TokenKind.NOT_IN),
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
    final BcInstrOpcode opcode;
    @Nullable final TokenKind tokenKind;

    private static class MapFromTokenKind {
      private static final BinOpOp[] MAP = new BinOpOp[TokenKind.values().length];
    }

    BinOpOp(BcInstrOpcode opcode, TokenKind tokenKind) {
      Preconditions.checkState(MapFromTokenKind.MAP[tokenKind.ordinal()] == null);
      MapFromTokenKind.MAP[tokenKind.ordinal()] = this;
      this.opcode = opcode;
      this.tokenKind = tokenKind;
    }

    BinOpOp(TokenKind tokenKind) {
      this(BcInstrOpcode.BINARY, tokenKind);
    }

    BinOpOp(BcInstrOpcode opcode) {
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
      if (op.opcode == BcInstrOpcode.BINARY) {
        writeContext.writer.write(
            BcInstrOpcode.BINARY,
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

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(lhs);
      visitor.visitSlot(rhs);
      visitor.visitSlot(result);
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
              BcInstrOpcode.NOT, locOffset, arg.encode(writeContext), result.encode(writeContext));
          break;
        default:
          writeContext.writer.write(
              BcInstrOpcode.UNARY,
              locOffset,
              arg.encode(writeContext),
              op.tokenKind.ordinal(),
              result.encode(writeContext));
          break;
      }
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(arg);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.BR, jumpLabel};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writeForwardJump(locOffset, jumpLabel);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {}
  }

  /** Conditional forward jump. */
  static class IfBr extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;
    final BcIrIfCond cond;
    final JumpLabel jumpLabel;

    IfBr(BcWriter.LocOffset locOffset, BcIrIfCond cond, JumpLabel jumpLabel) {
      this.locOffset = locOffset;
      this.cond = cond;
      this.jumpLabel = jumpLabel;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {IfBr.class.getSimpleName(), "(" + cond + ")", jumpLabel};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writeForwardCondJump(locOffset, cond, jumpLabel);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      cond.visitSlots(visitor);
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

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {}
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
      return new Object[] {BcInstrOpcode.FOR_INIT, collection, item};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeForInit(
          locOffset, collection.encode(writeContext), item.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(collection);
      visitor.visitSlot(item);
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

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {}

    static final ForClose FOR_CLOSE = new ForClose();
  }

  static class Break extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;

    public Break(BcWriter.LocOffset locOffset) {
      this.locOffset = locOffset;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstrOpcode.BREAK};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeBreak(locOffset);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {}
  }

  static class Continue extends BcIrInstr implements Flow {
    final BcWriter.LocOffset locOffset;

    Continue(BcWriter.LocOffset locOffset) {
      this.locOffset = locOffset;
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {BcInstrOpcode.CONTINUE};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeContinue(locOffset);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {}
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
      return new Object[] {BcInstrOpcode.PLUS_LIST, lhs, rhs, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.PLUS_LIST,
          locOffset,
          BcWriter.args(
              new int[] {lhs.encode(writeContext)},
              rhs.encode(writeContext),
              new int[] {result.encode(writeContext)}));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(lhs);
      visitor.visitSlots(rhs);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.PLUS_LIST_IN_PLACE, lhs, rhs, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.PLUS_LIST_IN_PLACE,
          locOffset,
          BcWriter.args(
              new int[] {lhs.encode(writeContext)},
              rhs.encode(writeContext),
              new int[] {result.encode(writeContext)}));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(lhs);
      visitor.visitSlots(rhs);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.TYPE_IS, expr, type, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.TYPE_IS,
          locOffset,
          expr.encode(writeContext),
          writeContext.writer.allocString(type),
          result.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(expr);
      visitor.visitSlot(result);
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
      BcInstrOpcode opcode = opcode();
      writeContext.writer.write(
          opcode,
          locOffset,
          writeContext.writer.allocString(format),
          indexOfPercent,
          arg.encode(writeContext),
          result.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(arg);
      visitor.visitSlot(result);
    }

    private BcInstrOpcode opcode() {
      return tuple ? BcInstrOpcode.PERCENT_S_ONE_TUPLE : BcInstrOpcode.PERCENT_S_ONE;
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
      return new Object[] {BcInstrOpcode.RETURN, value};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      if (value instanceof BcIrSlot.Const && ((BcIrSlot.Const) value).value == Starlark.NONE) {
        writeContext.writer.write(BcInstrOpcode.RETURN, locOffset, BcSlot.NULL_FLAG);
      } else {
        writeContext.writer.write(BcInstrOpcode.RETURN, locOffset, value.encode(writeContext));
      }
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(value);
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
      return new Object[] {BcInstrOpcode.EVAL_EXCEPTION, message};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.writeEvalException(locOffset, message);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {}
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
      Preconditions.checkArgument(linkSig.fixedArgCount() == listArg.size());

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
      return new Object[] {BcInstrOpcode.CALL, fn, linkSig, listArg, starArg, starStarArg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      if (linkSig == StarlarkCallableLinkSig.positional(1)) {
        writeContext.writer.write(
            BcInstrOpcode.CALL_1,
            locOffset,
            fn.encode(writeContext),
            writeContext.writer.allocObject(new BcDynCallSite(callLocs, linkSig)),
            listArg.arg(0).encode(writeContext),
            result.encode(writeContext));
      } else if (linkSig == StarlarkCallableLinkSig.positional(2)) {
        writeContext.writer.write(
            BcInstrOpcode.CALL_2,
            locOffset,
            fn.encode(writeContext),
            writeContext.writer.allocObject(new BcDynCallSite(callLocs, linkSig)),
            listArg.arg(0).encode(writeContext),
            listArg.arg(1).encode(writeContext),
            result.encode(writeContext));
      } else {
        int[] args =
            BcWriter.args(
                new int[] {
                  fn.encode(writeContext),
                  writeContext.writer.allocObject(new BcDynCallSite(callLocs, linkSig)),
                },
                listArg.encode(writeContext),
                new int[] {
                  starArg.encode(writeContext),
                  starStarArg.encode(writeContext),
                  result.encode(writeContext),
                });

        writeContext.writer.write(BcInstrOpcode.CALL, locOffset, args);
      }
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(fn);
      visitor.visitSlots(listArg);
      visitor.visitSlot(starArg);
      visitor.visitSlot(starStarArg);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.CALL_LINKED, fn, listArg, starArg, starStarArg, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      if (fn.linkSig == StarlarkCallableLinkSig.positional(1)) {
        writeContext.writer.write(
            BcInstrOpcode.CALL_LINKED_1,
            locOffset,
            writeContext.writer.allocObject(callLocs),
            writeContext.writer.allocObject(fn),
            listArg.arg(0).encode(writeContext),
            result.encode(writeContext));
      } else if (fn.linkSig == StarlarkCallableLinkSig.positional(2)) {
        writeContext.writer.write(
            BcInstrOpcode.CALL_LINKED_2,
            locOffset,
            writeContext.writer.allocObject(callLocs),
            writeContext.writer.allocObject(fn),
            listArg.arg(0).encode(writeContext),
            listArg.arg(1).encode(writeContext),
            result.encode(writeContext));
      } else {
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
        writeContext.writer.write(BcInstrOpcode.CALL_LINKED, locOffset, newArgs);
      }
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlots(listArg);
      visitor.visitSlot(starArg);
      visitor.visitSlot(starStarArg);
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.CALL_CACHED, callCached, result};
    }

    @Override
    void write(BcIrWriteContext writeContext) {
      writeContext.writer.write(
          BcInstrOpcode.CALL_CACHED,
          locOffset,
          writeContext.writer.allocObject(callCached),
          result.encode(writeContext));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(result);
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
      return new Object[] {BcInstrOpcode.NEW_FUNCTION, rfn.getName(), result};
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

      writeContext.writer.write(BcInstrOpcode.NEW_FUNCTION, locOffset, args);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlots(defaults);
      visitor.visitSlot(result);
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
          BcInstrOpcode.LOAD_STMT, locOffset, writeContext.writer.allocObject(loadStmt));
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {}
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
      return new Object[] {BcInstrOpcode.UNPACK, rhs, lhs};
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
      writeContext.writer.write(BcInstrOpcode.UNPACK, locOffset, args);
    }

    @Override
    void visitSlots(BcIrSlotVisitor visitor) {
      visitor.visitSlot(rhs);
      visitor.visitSlots(lhs);
    }
  }
}
