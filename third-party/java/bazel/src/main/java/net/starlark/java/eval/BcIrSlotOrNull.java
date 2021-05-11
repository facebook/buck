package net.starlark.java.eval;

/** Either slot or a "null" register. */
abstract class BcIrSlotOrNull {
  private BcIrSlotOrNull() {}

  @Override
  public abstract String toString();

  abstract int encode(BcIrWriteContext writeContext);

  static class Null extends BcIrSlotOrNull {
    private Null() {}

    @Override
    public String toString() {
      return Null.class.getSimpleName();
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      return BcSlot.NULL_FLAG;
    }

    static final Null NULL = new Null();
  }

  static class Slot extends BcIrSlotOrNull {
    final BcIrSlot slot;

    Slot(BcIrSlot slot) {
      this.slot = slot;
    }

    @Override
    public String toString() {
      return slot.toString();
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      return slot.encode(writeContext);
    }
  }
}
