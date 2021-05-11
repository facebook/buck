package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.base.VerifyException;

/** Bytecode instruction slot operands. */
class BcSlot {
  /** Local/temporary variable. */
  static final int LOCAL_KIND = 0x00;
  /** Constant reference. */
  static final int CONST_KIND = 0x01;
  /** Global variable, index is a index in Module. */
  static final int GLOBAL_KIND = 0x02;
  /** Cell. */
  static final int CELL_KIND = 0x03;
  /** Free variable. */
  static final int FREE_KIND = 0x04;

  static final int INDEX_BITS = 24;

  /** Operand type mask. */
  static final int MASK = 0xff_00_00_00;
  static final int LOCAL_FLAG = LOCAL_KIND << INDEX_BITS;
  static final int CONST_FLAG = CONST_KIND << INDEX_BITS;
  static final int GLOBAL_FLAG = GLOBAL_KIND << INDEX_BITS;
  static final int CELL_FLAG = CELL_KIND << INDEX_BITS;
  static final int FREE_FLAG = FREE_KIND << INDEX_BITS;
  /** Null marker. */
  static final int NULL_FLAG = 0x05_00_00_00;

  static int local(int index) {
    return index | LOCAL_FLAG;
  }

  static int global(int index) {
    return index | GLOBAL_FLAG;
  }

  static int cell(int index) {
    return index | CELL_FLAG;
  }

  static int free(int index) {
    return index | FREE_FLAG;
  }

  static int constValue(int index) {
    return index | CONST_FLAG;
  }

  static boolean isLocal(int slot) {
    return (slot & MASK) == LOCAL_FLAG;
  }

  static int localFromSlot(int slot) {
    Preconditions.checkArgument(isLocal(slot));
    return slot & ~LOCAL_FLAG;
  }

  static void checkIndex(int index) {
    Preconditions.checkState((index & MASK) == 0);
  }

  static void checkLocal(int slot) {
    Verify.verify(isLocal(slot));
  }

  static void checkValidSourceSlot(int slot) {
    switch (slot & MASK) {
      case LOCAL_FLAG:
      case GLOBAL_FLAG:
      case CELL_FLAG:
      case FREE_FLAG:
      case CONST_FLAG:
        return;
      default:
        throw new VerifyException(String.format("invalid source slot: %s (mask %x)", slot, slot & MASK));
    }
  }

  static String slotToString(int slot) {
    int index = slot & ~MASK;
    switch (slot & MASK) {
      case LOCAL_FLAG: return "LOCAL:" + index;
      case GLOBAL_FLAG: return "GLOBAL:" + index;
      case FREE_FLAG: return "FREE:" + index;
      case CELL_FLAG: return "CELL:" + index;
      case CONST_FLAG: return "CONST:" + index;
      default: return "INCORRECT:" + slot;
    }
  }

  static int negativeSizeToObjectIndex(int size) {
    if (Bc.ASSERTIONS) {
      Preconditions.checkArgument(size < 0);
    }
    return -1 - size;
  }

  static int objectIndexToNegativeSize(int objectIndex) {
    Preconditions.checkArgument(objectIndex >= 0);
    return -1 - objectIndex;
  }
}
