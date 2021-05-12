package net.starlark.java.eval;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/** Condition for conditional jump. */
abstract class BcIrIfCond {
  private BcIrIfCond() {}

  public abstract int write(BcIrWriteContext writeContext, BcWriter.LocOffset locOffset);

  @Override
  public final String toString() {
    return this.getClass().getSimpleName()
        + " "
        + Arrays.stream(argsForToString()).map(Objects::toString).collect(Collectors.joining(" "));
  }

  /** To string for an instruction is space-separated arguments. */
  protected abstract Object[] argsForToString();

  static class Local extends BcIrIfCond {
    final BcIrSlot.AnyLocal cond;
    final BcWriter.JumpCond jumpCond;

    public Local(BcIrSlot.AnyLocal cond, BcWriter.JumpCond jumpCond) {
      this.cond = cond;
      this.jumpCond = jumpCond;
    }

    @Override
    public int write(BcIrWriteContext writeContext, BcWriter.LocOffset locOffset) {
      return writeContext.writer.writeForwardCondJump(
          jumpCond, locOffset, cond.encode(writeContext));
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {cond, jumpCond};
    }
  }

  static class TypeIs extends BcIrIfCond {
    final BcIrSlot expr;
    final String type;
    final BcWriter.JumpCond jumpCond;

    TypeIs(BcIrSlot expr, String type, BcWriter.JumpCond jumpCond) {
      this.expr = expr;
      this.type = type;
      this.jumpCond = jumpCond;
    }

    @Override
    public int write(BcIrWriteContext writeContext, BcWriter.LocOffset locOffset) {
      return writeContext.writer.writeForwardTypeIsJump(
          jumpCond, locOffset, expr.encode(writeContext), type);
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {expr, type, jumpCond};
    }
  }

  static class Bin extends BcIrIfCond {
    final BcIrSlot a;
    final BcIrSlot b;
    final BcWriter.JumpBindCond cond;

    Bin(BcIrSlot a, BcIrSlot b, BcWriter.JumpBindCond cond) {
      this.a = a;
      this.b = b;
      this.cond = cond;
    }

    @Override
    public int write(BcIrWriteContext writeContext, BcWriter.LocOffset locOffset) {
      return writeContext.writer.writeForwardBinCondJump(
          cond, locOffset, a.encode(writeContext), b.encode(writeContext));
    }

    @Override
    protected Object[] argsForToString() {
      return new Object[] {a, b, cond};
    }
  }
}
