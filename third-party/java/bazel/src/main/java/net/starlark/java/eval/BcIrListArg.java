package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import javax.annotation.Nullable;

/** List arg (sequence of slots or an array of constants) in IR. */
abstract class BcIrListArg {
  private BcIrListArg() {}

  /** Serialize to bytecode. */
  abstract int[] encode(BcIrWriteContext writeContext);

  /** All values are constants and all are immutable. */
  abstract boolean allConstantsImmutable();

  @Override
  public abstract String toString();

  /** Constants or null if at least one value is not a constant. */
  @Nullable
  abstract Object[] data();

  /** Single list item, null if empty or more than one. */
  @Nullable
  public abstract BcIrSlot singleArg();

  /** List of slots. */
  static class Slots extends BcIrListArg {
    final ImmutableList<BcIrSlot> slots;

    Slots(ImmutableList<BcIrSlot> slots) {
      this.slots = slots;
    }

    @Override
    int[] encode(BcIrWriteContext writeContext) {
      int[] r = new int[1 + slots.size()];
      int i = 0;
      r[i++] = slots.size();
      for (BcIrSlot slot : slots) {
        r[i++] = slot.encode(writeContext);
      }
      Preconditions.checkState(i == r.length);
      return r;
    }

    @Override
    boolean allConstantsImmutable() {
      return slots.isEmpty();
    }

    @Nullable
    @Override
    Object[] data() {
      return slots.isEmpty() ? ArraysForStarlark.EMPTY_OBJECT_ARRAY : null;
    }

    @Nullable
    @Override
    public BcIrSlot singleArg() {
      if (slots.size() == 1) {
        return slots.get(0);
      } else {
        return null;
      }
    }

    @Override
    public String toString() {
      return slots.toString();
    }

    static final Slots EMPTY = new Slots(ImmutableList.of());
  }

  static class ListData extends BcIrListArg {
    final Object[] data;

    ListData(Object[] data) {
      Preconditions.checkArgument(data.length > 0, "for empty arg list Slots.EMPTY should be used");
      this.data = data;
    }

    @Override
    int[] encode(BcIrWriteContext writeContext) {
      return new int[] {BcSlot.objectIndexToNegativeSize(writeContext.writer.allocObject(data))};
    }

    @Override
    boolean allConstantsImmutable() {
      for (Object datum : data) {
        if (!Starlark.isImmutable(datum)) {
          return false;
        }
      }
      return true;
    }

    @Override
    Object[] data() {
      return data;
    }

    @Nullable
    @Override
    public BcIrSlot singleArg() {
      if (data.length == 1) {
        return new BcIrSlot.Const(data[0]);
      } else {
        return null;
      }
    }

    @Override
    public String toString() {
      return Arrays.toString(data);
    }
  }
}
