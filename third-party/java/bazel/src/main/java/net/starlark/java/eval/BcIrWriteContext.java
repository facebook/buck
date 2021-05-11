package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import java.util.HashMap;

/** IR-specific context while writing bytecode. */
class BcIrWriteContext {
  final BcWriter writer;

  BcIrWriteContext(BcWriter writer) {
    this.writer = writer;
  }

  /** {@link BcIrSlot.LazyLocal} during serialization. */
  static class LazyLocalState {
    final int local;
    int useCount = 0;

    public LazyLocalState(int local) {
      Preconditions.checkArgument(local >= 0);
      this.local = local;
    }
  }

  HashMap<BcIrSlot.LazyLocal, LazyLocalState> lazyLocals = new HashMap<>();
  HashMap<BcIrInstr.JumpLabel, IntArrayBuilder> forwardJumpAddrsToPatch = new HashMap<>();

  void writeForwardJump(BcWriter.LocOffset locOffset, BcIrInstr.JumpLabel jumpLabel) {
    int patchAddr = writer.writeForwardJump(locOffset);
    forwardJumpAddrsToPatch.computeIfAbsent(jumpLabel, k -> new IntArrayBuilder()).add(patchAddr);
  }

  void writeForwardCondJump(
      BcWriter.LocOffset locOffset,
      BcWriter.JumpCond jumpCond,
      BcIrSlot.AnyLocal cond,
      BcIrInstr.JumpLabel jumpLabel) {
    int patchAddr = writer.writeForwardCondJump(jumpCond, locOffset, cond.encode(this));
    forwardJumpAddrsToPatch.computeIfAbsent(jumpLabel, k -> new IntArrayBuilder()).add(patchAddr);
  }

  void patchForwardJump(BcIrInstr.JumpLabel jumpLabel) {
    IntArrayBuilder addrsToPatch = this.forwardJumpAddrsToPatch.remove(jumpLabel);
    // It is OK to add jump addrs nobody references
    if (addrsToPatch != null) {
      this.writer.patchForwardJumps(addrsToPatch);
    }
  }

  void assertWrittenCorrectly() {
    Preconditions.checkState(forwardJumpAddrsToPatch.isEmpty(), "all jump addrs were patched");
    writer.assertAllSlotsReleased();
  }
}
