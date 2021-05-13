package net.starlark.java.eval;

/** Expression result slot or a marked for any slot. */
abstract class BcIrLocalOrAny {
  private BcIrLocalOrAny() {}

  /** Get a local or make one. */
  abstract BcIrSlot.AnyLocal makeLocal(String label);

  /** Pointer to a local slot. */
  static class Local extends BcIrLocalOrAny {
    final BcIrSlot.AnyLocal local;

    Local(BcIrSlot.AnyLocal local) {
      this.local = local;
    }

    @Override
    BcIrSlot.AnyLocal makeLocal(String label) {
      return local;
    }
  }

  /** Expression is responsible for allocating a slot. */
  static class Any extends BcIrLocalOrAny {
    private Any() {}

    @Override
    BcIrSlot.AnyLocal makeLocal(String label) {
      return new BcIrSlot.LazyLocal(label);
    }

    static final Any ANY = new Any();
  }
}
