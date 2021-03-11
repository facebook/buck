package net.starlark.java.eval;

/** Bytecode instruction slot operands. */
class BcSlot {
  /** Operand type mask. */
  static final int MASK = 0xf0_00_00_00;
  /** Local/temporary variable. */
  static final int LOCAL_FLAG = 0x00_00_00_00;
  /** Global variable. */
  static final int GLOBAL_FLAG = 0x10_00_00_00;
  /** Cell. */
  static final int CELL_FLAG = 0x20_00_00_00;
  /** Free variable. */
  static final int FREE_FLAG = 0x30_00_00_00;
  /** Constant reference. */
  static final int CONST_FLAG = 0x40_00_00_00;
  /** Null marker. */
  static final int NULL_FLAG = 0x50_00_00_00;

  /** Marker for any register, used in the compiler. */
  static final int ANY_FLAG = 0x60_00_00_00;

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

  static boolean isValidSourceSlot(int slot) {
    switch (slot & MASK) {
      case LOCAL_FLAG:
      case GLOBAL_FLAG:
      case CELL_FLAG:
      case FREE_FLAG:
      case CONST_FLAG:
        return true;
      default:
        return false;
    }
  }
}
