/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** IR-specific context while writing bytecode. */
class BcIrWriteContext {
  final BcWriter writer;

  private final ArrayList<BcIrInstr> instructions;

  /** Allocate these slots before instruction. */
  private final ArrayList<LazyLocalState>[] allocSlotsByInstruction;
  /** Release these slots after instruction. */
  private final ArrayList<LazyLocalState>[] releaseSlotsByInstruction;

  /** Map lazy locals to slot numbers. */
  private final HashMap<BcIrSlot.LazyLocal, LazyLocalState> lazyLocals = new HashMap<>();

  /** Jump label map to addresses to patch when the label is encountered. */
  private final HashMap<BcIrInstr.JumpLabel, IntArrayBuilder> forwardJumpAddrsToPatch =
      new HashMap<>();

  @SuppressWarnings("unchecked")
  BcIrWriteContext(BcWriter writer, ArrayList<BcIrInstr> instructions) {
    this.writer = writer;
    this.instructions = instructions;

    // Now detect the scope of lazy locals: first the first and last instructions
    // where this lazy local was referenced, so it can be allocated at the first reference
    // and deallocated on the last reference.

    for (int i = 0; i < instructions.size(); i++) {
      BcIrInstr instr = instructions.get(i);
      int finalI = i;
      instr.visitSlots(
          new BcIrSlotVisitor() {
            @Override
            void visitSlot(BcIrSlot slot) {
              if (slot instanceof BcIrSlot.LazyLocal) {
                BcIrSlot.LazyLocal lazyLocal = (BcIrSlot.LazyLocal) slot;
                LazyLocalState state =
                    lazyLocals.computeIfAbsent(lazyLocal, k -> new LazyLocalState(finalI));
                state.lastInstruction = finalI;
              }
            }
          });
    }

    allocSlotsByInstruction = (ArrayList<LazyLocalState>[]) new ArrayList<?>[instructions.size()];
    releaseSlotsByInstruction = (ArrayList<LazyLocalState>[]) new ArrayList<?>[instructions.size()];

    for (Map.Entry<BcIrSlot.LazyLocal, LazyLocalState> entry : lazyLocals.entrySet()) {
      LazyLocalState state = entry.getValue();

      if (allocSlotsByInstruction[state.firstInstruction] == null) {
        allocSlotsByInstruction[state.firstInstruction] = new ArrayList<>();
      }
      allocSlotsByInstruction[state.firstInstruction].add(state);

      if (releaseSlotsByInstruction[state.lastInstruction] == null) {
        releaseSlotsByInstruction[state.lastInstruction] = new ArrayList<>();
      }
      releaseSlotsByInstruction[state.lastInstruction].add(state);
    }
  }

  void write() {
    for (int i = 0; i < this.instructions.size(); i++) {
      BcIrInstr instr = this.instructions.get(i);
      try {
        ArrayList<LazyLocalState> alloc = allocSlotsByInstruction[i];
        if (alloc != null) {
          for (LazyLocalState lazyLocal : alloc) {
            Preconditions.checkState(lazyLocal.local == LazyLocalState.LOCAL_NOT_INITIALIZED);
            lazyLocal.local = writer.allocSlot();
          }
        }

        instr.write(this);

        ArrayList<LazyLocalState> release = releaseSlotsByInstruction[i];
        if (release != null) {
          for (LazyLocalState lazyLocal : release) {
            Preconditions.checkState(lazyLocal.local >= 0);
            writer.releaseSlot(lazyLocal.local);
            lazyLocal.local = LazyLocalState.LOCAL_RELEASED;
          }
        }
      } catch (Exception e) {
        throw new IllegalStateException(
            String.format("failed to write instruction %s of %s", i, instructions), e);
      }
    }
    try {
      assertWrittenCorrectly();
    } catch (Exception e) {
      throw new IllegalStateException(String.format("failed to write %s", this), e);
    }
  }

  /** {@link BcIrSlot.LazyLocal} during serialization. */
  private static class LazyLocalState {
    private static final int LOCAL_NOT_INITIALIZED = -1;
    private static final int LOCAL_RELEASED = -2;

    /** First instruction referencing this lazy local. */
    private final int firstInstruction;
    /** Last instruction referencing this lazy local. */
    private int lastInstruction = -1;

    /** Local slot number while in scope of this lazy local. */
    private int local = LOCAL_NOT_INITIALIZED;

    LazyLocalState(int firstInstruction) {
      this.firstInstruction = firstInstruction;
    }
  }

  /**
   * Obtain current lazy local real slot number or crash if called outside of lazy local detected
   * scope.
   */
  int lazyLocalSlot(BcIrSlot.LazyLocal lazyLocal) {
    LazyLocalState state = lazyLocals.get(lazyLocal);
    Preconditions.checkState(state != null, "lazy local was not referenced: %s", lazyLocal);
    int local = state.local;
    if (local < 0) {
      switch (local) {
        case LazyLocalState.LOCAL_RELEASED:
          throw new IllegalStateException("local was released: " + lazyLocal);
        case LazyLocalState.LOCAL_NOT_INITIALIZED:
          throw new IllegalStateException("local was not initialized: " + lazyLocal);
        default:
          throw new IllegalStateException("impossible");
      }
    }
    return local;
  }

  void writeForwardJump(BcWriter.LocOffset locOffset, BcIrInstr.JumpLabel jumpLabel) {
    int patchAddr = writer.writeForwardJump(locOffset);
    forwardJumpAddrsToPatch.computeIfAbsent(jumpLabel, k -> new IntArrayBuilder()).add(patchAddr);
  }

  void writeForwardCondJump(
      BcWriter.LocOffset locOffset, BcIrIfCond cond, BcIrInstr.JumpLabel jumpLabel) {
    int patchAddr = cond.write(this, locOffset);
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
