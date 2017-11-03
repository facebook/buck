// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.graph.DexType;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;

public interface InstructionListIterator extends ListIterator<Instruction>,
    NextUntilIterator<Instruction> {

  /**
   * Peek the previous instruction.
   *
   * @return what will be returned by calling {@link #previous}. If there is no previous instruction
   * <code>null</code> is returned.
   */
  default Instruction peekPrevious() {
    Instruction previous = null;
    if (hasPrevious()) {
      previous = previous();
      next();
    }
    return previous;
  }

  /**
   * Peek the next instruction.
   *
   * @return what will be returned by calling {@link #next}. If there is no next instruction
   * <code>null</code> is returned.
   */
  default Instruction peekNext() {
    Instruction next = null;
    if (hasNext()) {
      next = next();
      previous();
    }
    return next;
  }

  /**
   * Continue to call {@link #next} while {@code predicate} tests {@code false}.
   *
   * @return the instruction that matched the predicate or {@code null} if all instructions fails
   * the predicate test
   */
  @Override
  default Instruction nextUntil(Predicate<Instruction> predicate) {
    while (hasNext()) {
      Instruction instruction = next();
      if (predicate.test(instruction)) {
        return instruction;
      }
    }
    return null;
  }

  default void setInsertionPosition(Position position) {
    // Intentionally empty.
  }

  /**
   * Safe removal function that will insert a DebugLocalRead to take over the debug values if any
   * are associated with the current instruction.
   */
  void removeOrReplaceByDebugLocalRead();

  /**
   * Remove the current instruction (aka the {@link Instruction} returned by the previous call to
   * {@link #next}) without updating its def/use chains.
   * <p>
   * This is useful for instance when moving an instruction to another block that still dominates
   * all its uses. In order to do that you would detach the instruction from the original
   * block and add it to the new block.
   */
  void detach();

  /**
   * Replace the current instruction (aka the {@link Instruction} returned by the previous call to
   * {@link #next} with the passed in <code>newInstruction</code>.
   *
   * The current instruction will be completely detached from the instruction stream with uses
   * of its in-values removed.
   *
   * If the current instruction produces an out-value the new instruction must also produce
   * an out-value, and all uses of the current instructions out-value will be replaced by the
   * new instructions out-value.
   *
   * @param newInstruction the instruction to insert instead of the current.
   */
  void replaceCurrentInstruction(Instruction newInstruction);

  /**
   * Split the block into two blocks at the point of the {@link ListIterator} cursor. The existing
   * block will have all the instructions before the cursor, and the new block all the
   * instructions after the cursor.
   *
   * If the current block has catch handlers these catch handlers will be attached to the block
   * containing the throwing instruction after the split.
   *
   * @param code the IR code for the block this iterator originates from.
   * @param blockIterator basic block iterator used to iterate the blocks. This must be positioned
   * just after the block for which this is the instruction iterator. After this method returns it
   * will be positioned just after the basic block returned. Calling {@link #remove} without
   * further navigation will remove that block.
   * @return Returns the new block with the instructions after the cursor.
   */
  BasicBlock split(IRCode code, ListIterator<BasicBlock> blockIterator);


  default BasicBlock split(IRCode code) {
    return split(code, null);
  }

  /**
   * Split the block into three blocks. The first split is at the point of the {@link ListIterator}
   * cursor and the second split is <code>instructions</code> after the cursor. The existing
   * block will have all the instructions before the cursor, and the two new blocks all the
   * instructions after the cursor.
   *
   * If the current block have catch handlers these catch handlers will be attached to the block
   * containing the throwing instruction after the split.
   *
   * @param code the IR code for the block this iterator originates from.
   * @param instructions the number of instructions to include in the second block.
   * @param blockIterator basic block iterator used to iterate the blocks. This must be positioned
   * just after the block for this is the instruction iterator. After this method returns it will be
   * positioned just after the second block inserted. Calling {@link #remove} without further
   * navigation will remove that block.
   * @return Returns the new block with the instructions after the cursor.
   */
  // TODO(sgjesse): Refactor to avoid the need for passing code and blockIterator.
  BasicBlock split(IRCode code, int instructions, ListIterator<BasicBlock> blockIterator);

  /**
   * See {@link #split(IRCode, int, ListIterator)}.
   */
  default BasicBlock split(IRCode code, int instructions) {
    return split(code, instructions, null);
  }

  /**
   * Inline the code in {@code inlinee} into {@code code}, replacing the invoke instruction at the
   * position after the cursor.
   *
   * The instruction at the position after cursor must be an invoke that matches the signature for
   * the code in {@code inlinee}.
   *
   * With one exception (see below) both the calling code and the inlinee can have catch handlers.
   *
   * <strong>EXCEPTION:</strong> If the invoke instruction is covered by catch handlers, and the
   * code for {@code inlinee} always throws (does not have a normal return) inlining is currently
   * <strong>NOT</strong> supported.
   *
   * @param code the IR code for the block this iterator originates from.
   * @param inlinee the IR code for the block this iterator originates from.
   * @param blockIterator basic block iterator used to iterate the blocks. This must be positioned
   * just after the block for which this is the instruction iterator. After this method returns it
   * will be positioned just after the basic block returned.
   * @param blocksToRemove list passed where blocks that where detached from the graph, but not
   * removed are added. When inlining an inlinee that always throws blocks in the <code>code</code>
   * can be detached, and not simply removed unsing the passed <code>blockIterator</code>. When
   * iterating using <code>blockIterator</code> after then method returns the blocks in this list
   * must be skipped when iterating with the active <code>blockIterator</code> and ultimately
   * removed.
   * @param downcast tells the inliner to issue a check cast opertion.
   * @return the basic block with the instructions right after the inlining. This can be a block
   * which can also be in the <code>blocksToRemove</code> list.
   */
  // TODO(sgjesse): Refactor to avoid the need for passing code.
  // TODO(sgjesse): Refactor to avoid the need for passing blocksToRemove.
  // TODO(sgjesse): Maybe don't return a BasicBlock, as it can be in blocksToRemove.
  // TODO(sgjesse): Maybe find a better place for this method.
  // TODO(sgjesse): Support inlinee with throwing instructions for invokes with existing handlers.
  BasicBlock inlineInvoke(IRCode code, IRCode inlinee, ListIterator<BasicBlock> blockIterator,
      List<BasicBlock> blocksToRemove, DexType downcast);

  /**
   * See {@link #inlineInvoke(IRCode, IRCode, ListIterator, List, DexType)}.
   */
  default BasicBlock inlineInvoke(IRCode code, IRCode inlinee) {
    List<BasicBlock> blocksToRemove = new ArrayList<>();
    BasicBlock result = inlineInvoke(code, inlinee, null, blocksToRemove, null);
    code.removeBlocks(blocksToRemove);
    return result;
  }
}
