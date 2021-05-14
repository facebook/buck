package net.starlark.java.eval;

import javax.annotation.Nullable;

/** Slot in Starlark IR. */
abstract class BcIrSlot {

  abstract int encode(BcIrWriteContext writeContext);

  @Override
  public abstract String toString();

  /** Return a value if this slot stores a constant or null otherwise. */
  @Nullable
  public Object constValue() {
    return null;
  }

  /** Any local slot. */
  abstract static class AnyLocal extends BcIrSlot {}

  /** Local slot (parameter, local variable, or temporary). */
  static class Local extends AnyLocal {
    final int index;

    Local(int index) {
      BcSlot.checkIndex(index);
      this.index = index;
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      return BcSlot.local(index);
    }

    @Override
    public String toString() {
      return "LOCAL:" + index;
    }
  }

  /**
   * Lazily (on serialization) initialized local number. It can be used to reference the slot when
   * it is not yet known which local numbers are available.
   */
  static class LazyLocal extends AnyLocal {
    /** For debugging. */
    private final String label;

    LazyLocal(String label) {
      this.label = label;
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      int local = writeContext.lazyLocalSlot(this);
      return BcSlot.local(local);
    }

    @Override
    public String toString() {
      return String.format("LAZY:%s:%s", label, System.identityHashCode(this));
    }
  }

  /** Global variable reference. */
  static class Global extends BcIrSlot {
    final int index;

    Global(int index) {
      BcSlot.checkIndex(index);
      this.index = index;
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      return BcSlot.global(index);
    }

    @Override
    public String toString() {
      return "GLOBAL:" + index;
    }
  }

  /** Cell reference. */
  static class Cell extends BcIrSlot {
    final int index;

    Cell(int index) {
      BcSlot.checkIndex(index);
      this.index = index;
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      return BcSlot.cell(index);
    }

    @Override
    public String toString() {
      return "CELL:" + index;
    }
  }

  /** Free variable reference. */
  static class Free extends BcIrSlot {
    final int index;

    Free(int index) {
      BcSlot.checkIndex(index);
      this.index = index;
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      return BcSlot.free(index);
    }

    @Override
    public String toString() {
      return "FREE:" + index;
    }
  }

  /** Constant slot. */
  static class Const extends BcIrSlot {
    /**
     * Constant value. Note the constant is stored as object here, but it is written as index when
     * serialized.
     */
    final Object value;

    public Const(Object value) {
      Starlark.checkValid(value);
      this.value = value;
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      int index = writeContext.writer.allocConstSlot(value);
      return BcSlot.constValue(index);
    }

    @Override
    public String toString() {
      return "CONST_VALUE:" + Starlark.repr(value);
    }

    @Nullable
    @Override
    public Object constValue() {
      return value;
    }

    static final Const NONE = new Const(Starlark.NONE);
    static final Const MANDATORY = new Const(StarlarkFunction.MANDATORY);
  }
}
