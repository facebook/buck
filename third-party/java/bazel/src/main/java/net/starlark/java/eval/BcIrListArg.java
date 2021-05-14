package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/** List arg (sequence of slots or an array of constants) in IR. */
class BcIrListArg {
  final ImmutableList<BcIrSlot> slots;
  /** Nonnull if all slots are constants. */
  @Nullable private final Object[] consts;

  private BcIrListArg(ImmutableList<BcIrSlot> slots) {
    this.slots = slots;
    Object[] consts = ArraysForStarlark.newObjectArray(slots.size());
    for (int i = 0; i < slots.size(); i++) {
      BcIrSlot slot = slots.get(i);
      if (slot instanceof BcIrSlot.Const) {
        consts[i] = ((BcIrSlot.Const) slot).value;
      } else {
        consts = null;
        break;
      }
    }
    this.consts = consts;
  }

  int[] encode(BcIrWriteContext writeContext) {
    if (consts != null && consts.length != 0) {
      return new int[] {BcSlot.objectIndexToNegativeSize(writeContext.writer.allocObject(consts))};
    } else {
      int[] r = new int[1 + slots.size()];
      int i = 0;
      r[i++] = slots.size();
      for (BcIrSlot slot : slots) {
        r[i++] = slot.encode(writeContext);
      }
      Preconditions.checkState(i == r.length);
      return r;
    }
  }

  /** All values are constants and all are immutable. */
  boolean allConstantsImmutable() {
    if (consts == null) {
      return false;
    }
    for (Object constValue : consts) {
      if (!Starlark.isImmutable(constValue)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    return slots.toString();
  }

  /** Constants or null if at least one value is not a constant. */
  @Nullable
  Object[] data() {
    return consts;
  }

  int size() {
    return slots.size();
  }

  /** Single list item, null if empty or more than one. */
  @Nullable
  BcIrSlot singleArg() {
    if (slots.size() == 1) {
      return slots.get(0);
    } else {
      return null;
    }
  }

  /** Arg i as slot. */
  BcIrSlot arg(int i) {
    return slots.get(i);
  }

  static final BcIrListArg EMPTY = new BcIrListArg(ImmutableList.of());

  static BcIrListArg of(ImmutableList<BcIrSlot> slots) {
    if (slots.isEmpty()) {
      return EMPTY;
    } else {
      return new BcIrListArg(slots);
    }
  }
}
