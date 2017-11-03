// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.code.MoveType;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Position;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A set of spill moves and functionality to schedule and insert them in the code.
 */
class SpillMoveSet {
  // Spill and restore moves on entry.
  private final Map<Integer, Set<SpillMove>> instructionToInMoves = new HashMap<>();
  // Spill and restore moves on exit.
  private final Map<Integer, Set<SpillMove>> instructionToOutMoves = new HashMap<>();
  // Phi moves.
  private final Map<Integer, Set<SpillMove>> instructionToPhiMoves = new HashMap<>();
  // The code into which to insert the moves.
  private final IRCode code;
  // The register allocator generating moves.
  private LinearScanRegisterAllocator allocator;
  // All registers below this number are arguments.
  private final int argumentRegisterLimit;
  // Mapping from instruction numbers to the block that start with that instruction if any.
  private final Map<Integer, BasicBlock> blockStartMap = new HashMap<>();
  // The number of temporary registers used for parallel moves when scheduling the moves.
  private int usedTempRegisters = 0;

  public SpillMoveSet(
      LinearScanRegisterAllocator allocator, IRCode code, int argumentRegisterLimit) {
    this.allocator = allocator;
    this.code = code;
    this.argumentRegisterLimit = argumentRegisterLimit;
    for (BasicBlock block : code.blocks) {
      blockStartMap.put(block.entry().getNumber(), block);
    }
  }

  /**
   * Add a spill or restore move.
   *
   * <p>This is used between all interval splits. The move is only inserted if it is restoring
   * from a spill slot at a position that is not at the start of a block. All block start
   * moves are handled by resolution.
   *
   * @param i instruction number (gap number) for which to insert the move
   * @param to interval representing the destination for the move
   * @param from interval representating the source for the move
   */
  public void addSpillOrRestoreMove(int i, LiveIntervals to, LiveIntervals from) {
    assert i % 2 == 1;
    assert to.getSplitParent() == from.getSplitParent();
    BasicBlock atEntryToBlock = blockStartMap.get(i + 1);
    if (atEntryToBlock == null) {
      addInMove(i, to, from);
    }
  }

  /**
   * Add a resolution move. This deals with moves in order to transfer an SSA value to another
   * register across basic block boundaries.
   *
   * @param i instruction number (gap number) for which to insert the move
   * @param to interval representing the destination for the move
   * @param from interval representing the source for the move
   */
  public void addInResolutionMove(int i, LiveIntervals to, LiveIntervals from) {
    assert to.getSplitParent() == from.getSplitParent();
    addInMove(i, to, from);
  }

  public void addOutResolutionMove(int i, LiveIntervals to, LiveIntervals from) {
    assert to.getSplitParent() == from.getSplitParent();
    addOutMove(i, to, from);
  }

  /**
   * Add a phi move to transfer an incoming SSA value to the SSA value in the destination block.
   *
   * @param i instruction number (gap number) for which to insert the move
   * @param to interval representing the destination for the move
   * @param from interval representing the source for the move
   */
  public void addPhiMove(int i, LiveIntervals to, LiveIntervals from) {
    assert i % 2 == 1;
    SpillMove move = new SpillMove(moveTypeForIntervals(to, from), to, from);
    move.updateMaxNonSpilled();
    instructionToPhiMoves.computeIfAbsent(i, (k) -> new LinkedHashSet<>()).add(move);
  }

  private void addInMove(int i, LiveIntervals to, LiveIntervals from) {
    assert i % 2 == 1;
    instructionToInMoves.computeIfAbsent(i, (k) -> new LinkedHashSet<>()).add(
        new SpillMove(moveTypeForIntervals(to, from), to, from));
  }

  private void addOutMove(int i, LiveIntervals to, LiveIntervals from) {
    assert i % 2 == 1;
    instructionToOutMoves.computeIfAbsent(i, (k) -> new LinkedHashSet<>()).add(
        new SpillMove(moveTypeForIntervals(to, from), to, from));
  }

  /**
   * Schedule the moves added to this SpillMoveSet and insert them into the code.
   *
   * <p>Scheduling requires parallel move semantics for some of the moves. That can require
   * the use of temporary registers to break cycles.
   *
   * @param tempRegister the first temporary register to use
   * @return the number of temporary registers used
   */
  public int scheduleAndInsertMoves(int tempRegister) {
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        Instruction instruction = it.next();
        int number = instruction.getNumber();
        if (needsMovesBeforeInstruction(number)) {
          // Move back so moves are inserted before the instruction.
          it.previous();
          scheduleMovesBeforeInstruction(tempRegister, number, it);
          // Move past the instruction again.
          it.next();
        }
      }
    }
    return usedTempRegisters;
  }

  private boolean isArgumentRegister(int register) {
    return register < argumentRegisterLimit;
  }

  private MoveType moveTypeForIntervals(LiveIntervals to, LiveIntervals from) {
    MoveType toType = to.getType();
    MoveType fromType = from.getType();
    if (toType == MoveType.OBJECT || fromType == MoveType.OBJECT) {
      assert fromType == MoveType.OBJECT || fromType == MoveType.SINGLE;
      assert toType == MoveType.OBJECT || toType == MoveType.SINGLE;
      return MoveType.OBJECT;
    }
    assert toType == fromType;
    return toType;
  }

  private boolean needsMovesBeforeInstruction(int i) {
    return instructionToOutMoves.containsKey(i - 1)
        || instructionToInMoves.containsKey(i - 1)
        || instructionToPhiMoves.containsKey(i - 1);
  }

  private SpillMove getMoveWithSource(LiveIntervals src, Collection<SpillMove> moves) {
    for (SpillMove move : moves) {
      if (move.from == src) {
        return move;
      }
    }
    return null;
  }

  private SpillMove getMoveWritingSourceRegister(SpillMove inMove, Collection<SpillMove> moves) {
    int srcRegister = inMove.from.getRegister();
    int srcRegisters = inMove.type.requiredRegisters();
    for (SpillMove move : moves) {
      int dstRegister = move.to.getRegister();
      int dstRegisters = move.type.requiredRegisters();
      for (int s = 0; s < srcRegisters; s++) {
        for (int d = 0; d < dstRegisters; d++) {
          if ((dstRegister + d) == (srcRegister + s)) {
            return move;
          }
        }
      }
    }
    return null;
  }

  // Shortcut move chains where we have a move in the in move set that moves to a
  // location that is then moved in the out move set to its final destination.
  //
  // r1 <- r0 (in move set)
  //
  // r2 <- r1 (out move set)
  //
  // is replaced with
  //
  // r2 <- r0 (out move set)
  //
  // Care must be taken when there are other moves in the in move set that can interfere
  // with the value. For example:
  //
  // r1 <- r0 (in move set)
  // r0 <- r1 (in move set)
  //
  // r2 <- r1 (out move set)
  //
  // Additionally, if a phi move uses the destination of the in move it needs to stay.
  //
  // If such interference exists we don't rewrite the moves and parallel moves are generated
  // to swap r1 and r0 on entry via a temporary register.
  private void pruneParallelMoveSets(
      Set<SpillMove> inMoves, Set<SpillMove> outMoves, Set<SpillMove> phiMoves) {
    Iterator<SpillMove> it = inMoves.iterator();
    while (it.hasNext()) {
      SpillMove inMove = it.next();
      SpillMove outMove = getMoveWithSource(inMove.to, outMoves);
      SpillMove blockingInMove = getMoveWritingSourceRegister(inMove, inMoves);
      SpillMove blockingPhiMove = getMoveWithSource(inMove.to, phiMoves);
      if (outMove != null && blockingInMove == null && blockingPhiMove == null) {
        it.remove();
        outMove.from = inMove.from;
      }
    }
  }

  private void scheduleMovesBeforeInstruction(
      int tempRegister, int instruction, InstructionListIterator insertAt) {

    Position position;
    if (insertAt.hasPrevious() && insertAt.peekPrevious().isMoveException()) {
      position = insertAt.peekPrevious().getPosition();
    } else {
      Instruction next = insertAt.peekNext();
      assert next.getNumber() == instruction;
      position = next.getPosition();
      if (position.isNone() && next.isGoto()) {
        position = next.asGoto().getTarget().getPosition();
      }
    }

    // Spill and restore moves for the incoming edge.
    Set<SpillMove> inMoves =
        instructionToInMoves.computeIfAbsent(instruction - 1, (k) -> new LinkedHashSet<>());
    removeArgumentRestores(inMoves);

    // Spill and restore moves for the outgoing edge.
    Set<SpillMove> outMoves =
        instructionToOutMoves.computeIfAbsent(instruction - 1, (k) -> new LinkedHashSet<>());
    removeArgumentRestores(outMoves);

    // Get the phi moves for this instruction and schedule them with the out going spill moves.
    Set<SpillMove> phiMoves =
        instructionToPhiMoves.computeIfAbsent(instruction - 1, (k) -> new LinkedHashSet<>());

    // Remove/rewrite moves that we can guarantee will not be needed.
    pruneParallelMoveSets(inMoves, outMoves, phiMoves);

    // Schedule out and phi moves together.
    outMoves.addAll(phiMoves);

    // Perform parallel move scheduling independently for the in and out moves.
    scheduleMoves(tempRegister, inMoves, insertAt, position);
    scheduleMoves(tempRegister, outMoves, insertAt, position);
  }

  // Remove restore moves that restore arguments. Since argument register reuse is
  // disallowed at this point we know that argument registers do not change value and
  // therefore we don't have to perform spill moves. Performing spill moves will also
  // make art reject the code because it loses type information for the argument.
  //
  // TODO(ager): We are dealing with some of these moves as rematerialization. However,
  // we are still generating actual moves back to the original argument register.
  // We should get rid of this method and avoid generating the moves in the first place.
  private void removeArgumentRestores(Set<SpillMove> moves) {
    Iterator<SpillMove> moveIterator = moves.iterator();
    while (moveIterator.hasNext()) {
      SpillMove move = moveIterator.next();
      if (isArgumentRegister(move.to.getRegister())) {
        moveIterator.remove();
      }
    }
  }

  private void scheduleMoves(
      int tempRegister, Set<SpillMove> moves, InstructionListIterator insertAt, Position position) {
    RegisterMoveScheduler scheduler = new RegisterMoveScheduler(insertAt, tempRegister, position);
    for (SpillMove move : moves) {
      // Do not generate moves to spill a value that can be rematerialized.
      if (move.to.isSpilledAndRematerializable(allocator)) {
        continue;
      }
      // Use rematerialization when possible and otherwise generate moves.
      if (move.from.isSpilledAndRematerializable(allocator)) {
        scheduler.addMove(
            new RegisterMove(move.to.getRegister(), move.type, move.from.getValue().definition));
      } else if (move.to.getRegister() != move.from.getRegister()) {
        scheduler.addMove(
            new RegisterMove(move.to.getRegister(), move.from.getRegister(), move.type));
      }
    }
    scheduler.schedule();
    usedTempRegisters = Math.max(usedTempRegisters, scheduler.getUsedTempRegisters());
  }
}
