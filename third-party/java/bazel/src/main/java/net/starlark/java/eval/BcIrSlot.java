package net.starlark.java.eval;

import com.google.common.base.Preconditions;
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

  /**
   * Inc allowed use count for this slot.
   *
   * <p>This is used for precise temporaries allocation: temporary is created with refcount zero for
   * typical use case: allocate, write, read. This can be incremented explicitly if temporary need
   * to be read (or written) with then one instruction.
   *
   * <p>Incorrect refcounter inc/dec causes exception at IR serialization time.
   *
   * <p>This method is no-op except for temporary slots.
   */
  void incRef() {}

  void decRef() {}

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
    /** Number of time this local will be referenced in instructions. */
    private int refCount = 2;
    /** No incRef/decRef allowed when frozen. */
    private boolean frozen = false;

    LazyLocal(BcIr.Friend friend, String label) {
      this.label = label;
      friend.markUsed();
    }

    /** Fill the slot number during serialization. */
    void init(BcIrWriteContext writeContext) {
      int local = writeContext.writer.allocSlot();

      BcIrWriteContext.LazyLocalState prev =
          writeContext.lazyLocals.put(this, new BcIrWriteContext.LazyLocalState(local));
      Preconditions.checkState(prev == null, "slot can be allocated only once");

      this.frozen = true;
    }

    @Override
    void incRef() {
      Preconditions.checkState(!frozen, "must not incRef after freeze");
      ++refCount;
    }

    @Override
    void decRef() {
      Preconditions.checkState(!frozen, "must not decRef after freeze");
      Preconditions.checkState(refCount > 0, "refcount is already zero");
      --refCount;
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      BcIrWriteContext.LazyLocalState localState = writeContext.lazyLocals.get(this);
      Preconditions.checkState(localState != null, "Lazy slot was not initialized, %s", this);

      Preconditions.checkState(
          localState.useCount < refCount, "use counter reached ref counter, %s", this);
      if (++localState.useCount == refCount) {
        writeContext.writer.releaseSlot(localState.local);
      }
      return BcSlot.local(localState.local);
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
      return "CONST_VALUE:" + value;
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
