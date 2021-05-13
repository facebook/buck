package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import java.util.List;

/** Visitor for BC IR instruction slots. */
abstract class BcIrSlotVisitor {
  /** Callback. */
  abstract void visitSlot(BcIrSlot slot);

  public void visitSlot(BcIrSlotOrNull slot) {
    if (slot instanceof BcIrSlotOrNull.Slot) {
      visitSlot(((BcIrSlotOrNull.Slot) slot).slot);
    } else {
      Preconditions.checkState(slot == BcIrSlotOrNull.Null.NULL);
    }
  }

  final void visitSlots(BcIrListArg arg) {
    if (arg instanceof BcIrListArg.Slots) {
      visitSlots(((BcIrListArg.Slots) arg).slots);
    }
  }

  final void visitSlots(List<? extends BcIrSlot> slots) {
    for (BcIrSlot slot : slots) {
      visitSlot(slot);
    }
  }
}
