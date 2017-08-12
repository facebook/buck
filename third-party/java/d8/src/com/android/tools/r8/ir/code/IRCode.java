// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.regalloc.LinearScanRegisterAllocator;
import com.android.tools.r8.utils.CfgPrinter;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.stream.Collectors;

public class IRCode {

  public final DexEncodedMethod method;

  public LinkedList<BasicBlock> blocks;
  public final ValueNumberGenerator valueNumberGenerator;

  private boolean numbered = false;
  private int nextInstructionNumber = 0;

  // Initial value indicating if the code does have actual positions on all throwing instructions.
  // If this is the case, which holds for javac code, then we want to ensure that it remains so.
  private boolean allThrowingInstructionsHavePositions;

  public final boolean hasDebugPositions;

  public IRCode(
      DexEncodedMethod method,
      LinkedList<BasicBlock> blocks,
      ValueNumberGenerator valueNumberGenerator,
      boolean hasDebugPositions) {
    this.method = method;
    this.blocks = blocks;
    this.valueNumberGenerator = valueNumberGenerator;
    this.hasDebugPositions = hasDebugPositions;
    allThrowingInstructionsHavePositions = computeAllThrowingInstructionsHavePositions();
  }

  public void splitCriticalEdges() {
    List<BasicBlock> newBlocks = new ArrayList<>();
    int nextBlockNumber = getHighestBlockNumber() + 1;
    for (BasicBlock block : blocks) {
      // We are using a spilling register allocator that might need to insert moves at
      // all critical edges, so we always split them all.
      List<BasicBlock> predecessors = block.getPredecessors();
      if (predecessors.size() <= 1) {
        continue;
      }
      // If any of the edges to the block are critical, we need to insert new blocks on each
      // containing the move-exception instruction which must remain the first instruction.
      if (block.entry() instanceof MoveException) {
        nextBlockNumber = block.splitCriticalExceptionEdges(
            nextBlockNumber, valueNumberGenerator, newBlocks::add);
        continue;
      }
      for (int predIndex = 0; predIndex < predecessors.size(); predIndex++) {
        BasicBlock pred = predecessors.get(predIndex);
        if (!pred.hasOneNormalExit()) {
          // Critical edge: split it and inject a new block into which the
          // phi moves can be inserted. The new block is created with the
          // correct predecessor and successor structure. It is inserted
          // at the end of the list of blocks disregarding branching
          // structure.
          BasicBlock newBlock = BasicBlock.createGotoBlock(nextBlockNumber++, block);
          newBlocks.add(newBlock);
          pred.replaceSuccessor(block, newBlock);
          newBlock.getPredecessors().add(pred);
          predecessors.set(predIndex, newBlock);
        }
      }
    }
    blocks.addAll(newBlocks);
  }

  /**
   * Trace blocks and attempt to put fallthrough blocks immediately after the block that
   * falls through. When we fail to do that we create a new fallthrough block with an explicit
   * goto to the actual fallthrough block.
   */
  public void traceBlocks() {
    BasicBlock[] sorted = topologicallySortedBlocks();
    clearMarks();
    int nextBlockNumber = blocks.size();
    LinkedList<BasicBlock> tracedBlocks = new LinkedList<>();
    for (BasicBlock block : sorted) {
      if (!block.isMarked()) {
        block.mark();
        tracedBlocks.add(block);
        BasicBlock current = block;
        BasicBlock fallthrough = block.exit().fallthroughBlock();
        while (fallthrough != null && !fallthrough.isMarked()) {
          fallthrough.mark();
          tracedBlocks.add(fallthrough);
          current = fallthrough;
          fallthrough = fallthrough.exit().fallthroughBlock();
        }
        if (fallthrough != null) {
          BasicBlock newFallthrough = BasicBlock.createGotoBlock(nextBlockNumber++, fallthrough);
          current.exit().setFallthroughBlock(newFallthrough);
          newFallthrough.getPredecessors().add(current);
          fallthrough.replacePredecessor(current, newFallthrough);
          newFallthrough.mark();
          tracedBlocks.add(newFallthrough);
        }
      }
    }
    blocks = tracedBlocks;
  }

  private void ensureBlockNumbering() {
    if (!numbered) {
      numbered = true;
      BasicBlock[] sorted = topologicallySortedBlocks();
      for (int i = 0; i < sorted.length; i++) {
        sorted[i].setNumber(i);
      }
    }
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("blocks:\n");
    for (BasicBlock block : blocks) {
      builder.append(block.toDetailedString());
      builder.append("\n");
    }
    return builder.toString();
  }

  public void clearMarks() {
    for (BasicBlock block : blocks) {
      block.clearMark();
    }
  }

  public void removeMarkedBlocks() {
    ListIterator<BasicBlock> blockIterator = listIterator();
    while (blockIterator.hasNext()) {
      BasicBlock block = blockIterator.next();
      if (block.isMarked()) {
        blockIterator.remove();
      }
    }
  }

  public void removeBlocks(List<BasicBlock> blocksToRemove) {
    blocks.removeAll(blocksToRemove);
  }

  /**
   * Compute quasi topologically sorted list of the basic blocks using depth first search.
   *
   * TODO(ager): We probably want to compute strongly connected components and topologically
   * sort strongly connected components instead. However, this is much better than having
   * no sorting.
   */
  public BasicBlock[] topologicallySortedBlocks() {
    return topologicallySortedBlocks(Collections.emptyList());
  }

  public BasicBlock[] topologicallySortedBlocks(List<BasicBlock> blocksToIgnore) {
    clearMarks();
    int reachableBlocks = blocks.size() - blocksToIgnore.size();
    BasicBlock[] sorted = new BasicBlock[reachableBlocks];
    BasicBlock entryBlock = blocks.getFirst();
    int index = depthFirstSorting(entryBlock, sorted, reachableBlocks - 1);
    assert index == -1;
    return sorted;
  }

  private int depthFirstSorting(BasicBlock block, BasicBlock[] sorted, int index) {
    if (!block.isMarked()) {
      block.mark();
      for (BasicBlock succ : block.getSuccessors()) {
        index = depthFirstSorting(succ, sorted, index);
      }
      assert sorted[index] == null;
      sorted[index] = block;
      return index - 1;
    }
    return index;
  }

  public void print(CfgPrinter printer) {
    ensureBlockNumbering();
    for (BasicBlock block : blocks) {
      block.print(printer);
    }
  }

  public boolean isConsistentSSA() {
    assert isConsistentGraph();
    assert consistentDefUseChains();
    assert validThrowingInstructions();
    assert noCriticalEdges();
    return true;
  }

  public boolean isConsistentGraph() {
    assert consistentBlockNumbering();
    assert consistentPredecessorSuccessors();
    assert consistentCatchHandlers();
    assert consistentBlockInstructions();
    assert !allThrowingInstructionsHavePositions || computeAllThrowingInstructionsHavePositions();
    return true;
  }

  private boolean noCriticalEdges() {
    for (BasicBlock block : blocks) {
      List<BasicBlock> predecessors = block.getPredecessors();
      if (predecessors.size() <= 1) {
        continue;
      }
      if (block.entry() instanceof MoveException) {
        assert false;
        return false;
      }
      for (int predIndex = 0; predIndex < predecessors.size(); predIndex++) {
        if (!predecessors.get(predIndex).hasOneNormalExit()) {
          assert false;
          return false;
        }
      }
    }
    return true;
  }

  private boolean consistentDefUseChains() {
    Set<Value> values = new HashSet<>();

    for (BasicBlock block : blocks) {
      int predecessorCount = block.getPredecessors().size();
      // Check that all phi uses are consistent.
      for (Phi phi : block.getPhis()) {
        assert phi.getOperands().size() == predecessorCount;
        values.add(phi);
        for (Value value : phi.getOperands()) {
          values.add(value);
          assert value.uniquePhiUsers().contains(phi);
        }
        for (Value value : phi.getDebugValues()) {
          values.add(value);
          assert value.debugPhiUsers().contains(phi);
        }
      }
      for (Instruction instruction : block.getInstructions()) {
        assert instruction.getBlock() == block;
        Value outValue = instruction.outValue();
        if (outValue != null) {
          values.add(outValue);
          assert outValue.definition == instruction;
        }
        for (Value value : instruction.inValues()) {
          values.add(value);
          assert value.uniqueUsers().contains(instruction);
        }
        for (Value value : instruction.getDebugValues()) {
          values.add(value);
          assert value.debugUsers().contains(instruction);
        }
      }
    }

    for (Value value : values) {
      assert verifyValue(value);
      assert consistentValueUses(value);
    }

    return true;
  }

  private boolean verifyValue(Value value) {
    assert value.isPhi() ? verifyPhi(value.asPhi()) : verifyDefinition(value);
    return true;
  }

  private boolean verifyPhi(Phi phi) {
    assert phi.getBlock().getPhis().contains(phi);
    return true;
  }

  private boolean verifyDefinition(Value value) {
    assert value.definition.outValue() == value;
    return true;
  }

  private boolean consistentValueUses(Value value) {
    for (Instruction user : value.uniqueUsers()) {
      assert user.inValues().contains(value);
    }
    for (Phi phiUser : value.uniquePhiUsers()) {
      assert phiUser.getOperands().contains(value);
      assert phiUser.getBlock().getPhis().contains(phiUser);
    }
    if (value.hasLocalInfo()) {
      for (Instruction debugUser : value.debugUsers()) {
        assert debugUser.getDebugValues().contains(value);
      }
      for (Phi phiUser : value.debugPhiUsers()) {
        assert verifyPhi(phiUser);
        assert phiUser.getDebugValues().contains(value);
      }
    }
    return true;
  }

  private boolean consistentPredecessorSuccessors() {
    for (BasicBlock block : blocks) {
      // Check that all successors are distinct.
      assert new HashSet<>(block.getSuccessors()).size() == block.getSuccessors().size();
      for (BasicBlock succ : block.getSuccessors()) {
        // Check that successors are in the block list.
        assert blocks.contains(succ);
        // Check that successors have this block as a predecessor.
        assert succ.getPredecessors().contains(block);
      }
      // Check that all predecessors are distinct.
      assert new HashSet<>(block.getPredecessors()).size() == block.getPredecessors().size();
      for (BasicBlock pred : block.getPredecessors()) {
        // Check that predecessors are in the block list.
        assert blocks.contains(pred);
        // Check that predecessors have this block as a successor.
        assert pred.getSuccessors().contains(block);
      }
    }
    return true;
  }

  private boolean consistentCatchHandlers() {
    for (BasicBlock block : blocks) {
      // Check that catch handlers are always the first successors of a block.
      if (block.hasCatchHandlers()) {
        assert block.exit().isGoto() || block.exit().isThrow();
        CatchHandlers<Integer> catchHandlers = block.getCatchHandlersWithSuccessorIndexes();
        // If there is a catch-all guard it must be the last.
        List<DexType> guards = catchHandlers.getGuards();
        int lastGuardIndex = guards.size() - 1;
        for (int i = 0; i < guards.size(); i++) {
          assert guards.get(i) != DexItemFactory.catchAllType || i == lastGuardIndex;
        }
        // Check that all successors except maybe the last are catch successors.
        List<Integer> sortedHandlerIndices = new ArrayList<>(catchHandlers.getAllTargets());
        sortedHandlerIndices.sort(Comparator.naturalOrder());
        int firstIndex = sortedHandlerIndices.get(0);
        int lastIndex = sortedHandlerIndices.get(sortedHandlerIndices.size() - 1);
        assert firstIndex == 0;
        assert lastIndex < sortedHandlerIndices.size();
        int lastSuccessorIndex = block.getSuccessors().size() - 1;
        assert lastIndex == lastSuccessorIndex  // All successors are catch successors.
            || lastIndex == lastSuccessorIndex - 1; // All but one successors are catch successors.
        assert lastIndex == lastSuccessorIndex || !block.exit().isThrow();
      }
    }
    return true;
  }

  public boolean consistentBlockNumbering() {
    return blocks.stream()
        .collect(Collectors.groupingBy(BasicBlock::getNumber, Collectors.counting()))
        .entrySet().stream().noneMatch((bb2count) -> bb2count.getValue() > 1);
  }

  private boolean consistentBlockInstructions() {
    for (BasicBlock block : blocks) {
      for (Instruction instruction : block.getInstructions()) {
        assert instruction.getPosition() != null;
        assert instruction.getBlock() == block;
      }
    }
    return true;
  }

  private boolean validThrowingInstructions() {
    for (BasicBlock block : blocks) {
      if (block.hasCatchHandlers()) {
        boolean seenThrowing = false;
        for (Instruction instruction : block.getInstructions()) {
          if (instruction.instructionTypeCanThrow()) {
            assert !seenThrowing;
            seenThrowing = true;
            continue;
          }
          // After the throwing instruction only debug instructions an the final jump
          // instruction is allowed.
          // TODO(ager): For now allow const instructions due to the way consts are pushed
          // towards their use
          if (seenThrowing) {
            assert instruction.isDebugInstruction()
                || instruction.isJumpInstruction()
                || instruction.isConstInstruction()
                || instruction.isNewArrayFilledData();
          }
        }
      }
    }
    return true;
  }

  public InstructionIterator instructionIterator() {
    return new IRCodeInstructionsIterator(this);
  }

  public ImmutableList<BasicBlock> computeNormalExitBlocks() {
    ImmutableList.Builder<BasicBlock> builder = ImmutableList.builder();
    for (BasicBlock block : blocks) {
      if (block.exit().isReturn()) {
        builder.add(block);
      }
    }
    return builder.build();
  }

  public ListIterator<BasicBlock> listIterator() {
    return new BasicBlockIterator(this);
  }

  public ListIterator<BasicBlock> listIterator(int index) {
    return new BasicBlockIterator(this, index);
  }

  public BasicBlock[] numberInstructions() {
    BasicBlock[] blocks = topologicallySortedBlocks();
    for (BasicBlock block : blocks) {
      for (Instruction instruction : block.getInstructions()) {
        instruction.setNumber(nextInstructionNumber);
        nextInstructionNumber += LinearScanRegisterAllocator.INSTRUCTION_NUMBER_DELTA;
      }
    }
    return blocks;
  }

  public int numberRemainingInstructions() {
    InstructionIterator it = instructionIterator();
    while (it.hasNext()) {
      Instruction i = it.next();
      if (i.getNumber() == -1) {
        i.setNumber(nextInstructionNumber);
        nextInstructionNumber += LinearScanRegisterAllocator.INSTRUCTION_NUMBER_DELTA;
      }
    }
    return nextInstructionNumber;
  }

  public int getNextInstructionNumber() {
    return nextInstructionNumber;
  }

  public List<Value> collectArguments() {
    final List<Value> arguments = new ArrayList<>();
    Iterator<Instruction> iterator = blocks.get(0).iterator();
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      if (instruction.isArgument()) {
        arguments.add(instruction.asArgument().outValue());
      }
    }
    assert arguments.size()
        == method.method.getArity() + (method.accessFlags.isStatic() ? 0 : 1);
    return arguments;
  }

  public Value createValue(ValueType valueType, DebugLocalInfo local) {
    return new Value(valueNumberGenerator.next(), valueType, local);
  }

  public Value createValue(ValueType valueType) {
    return createValue(valueType, null);
  }

  public ConstNumber createIntConstant(int value) {
    return new ConstNumber(createValue(ValueType.INT), value);
  }

  public ConstNumber createTrue() {
    return new ConstNumber(createValue(ValueType.INT), 1);
  }

  public ConstNumber createFalse() {
    return new ConstNumber(createValue(ValueType.INT), 0);
  }

  public final int getHighestBlockNumber() {
    return blocks.stream().max(Comparator.comparingInt(BasicBlock::getNumber)).get().getNumber();
  }

  public Instruction createConstNull(Instruction from) {
    return new ConstNumber(createValue(from.outType()), 0);
  }

  public boolean doAllThrowingInstructionsHavePositions() {
    return allThrowingInstructionsHavePositions;
  }

  public void setAllThrowingInstructionsHavePositions(boolean value) {
    this.allThrowingInstructionsHavePositions = value;
  }

  private boolean computeAllThrowingInstructionsHavePositions() {
    InstructionIterator it = instructionIterator();
    while (it.hasNext()) {
      Instruction instruction = it.next();
      if (instruction.instructionTypeCanThrow() && instruction.getPosition().isNone()) {
        return false;
      }
    }
    return true;
  }
}
