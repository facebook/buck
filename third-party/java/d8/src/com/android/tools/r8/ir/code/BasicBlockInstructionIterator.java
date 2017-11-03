// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.utils.IteratorUtils;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public class BasicBlockInstructionIterator implements InstructionIterator, InstructionListIterator {

  protected final BasicBlock block;
  protected final ListIterator<Instruction> listIterator;
  protected Instruction current;
  protected Position position = null;

  protected BasicBlockInstructionIterator(BasicBlock block) {
    this.block = block;
    this.listIterator = block.getInstructions().listIterator();
  }

  protected BasicBlockInstructionIterator(BasicBlock block, int index) {
    this.block = block;
    this.listIterator = block.getInstructions().listIterator(index);
  }

  protected BasicBlockInstructionIterator(BasicBlock block, Instruction instruction) {
    this(block);
    nextUntil((x) -> x == instruction);
  }

  @Override
  public boolean hasNext() {
    return listIterator.hasNext();
  }

  @Override
  public Instruction next() {
    current = listIterator.next();
    return current;
  }

  @Override
  public int nextIndex() {
    return listIterator.nextIndex();
  }

  @Override
  public boolean hasPrevious() {
    return listIterator.hasPrevious();
  }

  @Override
  public Instruction previous() {
    current = listIterator.previous();
    return current;
  }

  @Override
  public int previousIndex() {
    return listIterator.previousIndex();
  }

  @Override
  public void setInsertionPosition(Position position) {
    this.position = position;
  }

  /**
   * Adds an instruction to the block. The instruction will be added just before the current
   * cursor position.
   *
   * The instruction will be assigned to the block it is added to.
   *
   * @param instruction The instruction to add.
   */
  @Override
  public void add(Instruction instruction) {
    instruction.setBlock(block);
    assert instruction.getBlock() == block;
    if (position != null) {
      instruction.setPosition(position);
    }
    listIterator.add(instruction);
  }

  /**
   * Replaces the last instruction returned by {@link #next} or {@link #previous} with the
   * specified instruction.
   *
   * The instruction will be assigned to the block it is added to.
   *
   * @param instruction The instruction to replace with.
   */
  @Override
  public void set(Instruction instruction) {
    instruction.setBlock(block);
    assert instruction.getBlock() == block;
    listIterator.set(instruction);
  }

  /**
   * Remove the current instruction (aka the {@link Instruction} returned by the previous call to
   * {@link #next}.
   *
   * The current instruction will be completely detached from the instruction stream with uses
   * of its in-values removed.
   *
   * If the current instruction produces an out-value this out value must not have any users.
   */
  @Override
  public void remove() {
    if (current == null) {
      throw new IllegalStateException();
    }
    assert current.outValue() == null || !current.outValue().isUsed();
    assert current.getDebugValues().isEmpty();
    for (int i = 0; i < current.inValues().size(); i++) {
      Value value = current.inValues().get(i);
      value.removeUser(current);
    }
    for (Value value : current.getDebugValues()) {
      value.removeDebugUser(current);
    }
    if (current.getLocalInfo() != null) {
      for (Instruction user : current.outValue().debugUsers()) {
        user.removeDebugValue(current.outValue());
      }
    }
    listIterator.remove();
    current = null;
  }

  @Override
  public void removeOrReplaceByDebugLocalRead() {
    if (current == null) {
      throw new IllegalStateException();
    }
    if (current.getDebugValues().isEmpty()) {
      remove();
    } else {
      replaceCurrentInstruction(new DebugLocalRead());
    }
  }

  @Override
  public void detach() {
    if (current == null) {
      throw new IllegalStateException();
    }
    listIterator.remove();
    current = null;
  }

  @Override
  public void replaceCurrentInstruction(Instruction newInstruction) {
    if (current == null) {
      throw new IllegalStateException();
    }
    for (Value value : current.inValues()) {
      value.removeUser(current);
    }
    if (current.outValue() != null && current.outValue().isUsed()) {
      assert newInstruction.outValue() != null;
      current.outValue().replaceUsers(newInstruction.outValue());
    }
    current.moveDebugValues(newInstruction);
    newInstruction.setBlock(block);
    newInstruction.setPosition(current.getPosition());
    listIterator.remove();
    listIterator.add(newInstruction);
    current.clearBlock();
  }

  @Override
  public BasicBlock split(IRCode code, ListIterator<BasicBlock> blocksIterator) {
    List<BasicBlock> blocks = code.blocks;
    assert blocksIterator == null || IteratorUtils.peekPrevious(blocksIterator) == block;

    int blockNumber = code.getHighestBlockNumber() + 1;
    BasicBlock newBlock;

    // Don't allow splitting after the last instruction.
    assert hasNext();

    // Prepare the new block, placing the exception handlers on the block with the throwing
    // instruction.
    boolean keepCatchHandlers = hasPrevious() && peekPrevious().instructionTypeCanThrow();
    newBlock = block.createSplitBlock(blockNumber, keepCatchHandlers);

    // Add a goto instruction.
    Goto newGoto = new Goto(block);
    listIterator.add(newGoto);

    // Move all remaining instructions to the new block.
    while (listIterator.hasNext()) {
      Instruction instruction = listIterator.next();
      newBlock.getInstructions().addLast(instruction);
      instruction.setBlock(newBlock);
      listIterator.remove();
    }

    // Insert the new block in the block list right after the current block.
    if (blocksIterator == null) {
      blocks.add(blocks.indexOf(block) + 1, newBlock);
    } else {
      blocksIterator.add(newBlock);
      // Ensure that calling remove() will remove the block just added.
      blocksIterator.previous();
      blocksIterator.next();
    }

    return newBlock;
  }

  @Override
  public BasicBlock split(IRCode code, int instructions, ListIterator<BasicBlock> blocksIterator) {
    // Split at the current cursor position.
    BasicBlock newBlock = split(code, blocksIterator);
    assert blocksIterator == null || IteratorUtils.peekPrevious(blocksIterator) == newBlock;
    // Skip the requested number of instructions and split again.
    InstructionListIterator iterator = newBlock.listIterator();
    for (int i = 0; i < instructions; i++) {
      iterator.next();
    }
    iterator.split(code, blocksIterator);
    // Return the first split block.
    return newBlock;
  }

  private boolean canThrow(IRCode code) {
    Iterator<Instruction> iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      boolean throwing = iterator.next().instructionTypeCanThrow();
      if (throwing) {
        return true;
      }
    }
    return false;
  }

  private void splitBlockAndCopyCatchHandlers(IRCode code, BasicBlock invokeBlock,
      BasicBlock inlinedBlock, ListIterator<BasicBlock> blocksIterator) {
    // Iterate through the instructions in the inlined block and split into blocks with only
    // one throwing instruction in each block.
    // NOTE: This iterator is replaced in the loop below, so that the iteration continues in
    // the new block after the iterated block is split.
    InstructionListIterator instructionsIterator = inlinedBlock.listIterator();
    BasicBlock currentBlock = inlinedBlock;
    while (currentBlock != null && instructionsIterator.hasNext()) {
      assert !currentBlock.hasCatchHandlers();
      Instruction throwingInstruction =
          instructionsIterator.nextUntil(Instruction::instructionTypeCanThrow);
      BasicBlock nextBlock;
      if (throwingInstruction != null) {
        // If a throwing instruction was found split the block.
        if (instructionsIterator.hasNext()) {
          // TODO(sgjesse): No need to split if this is the last non-debug, non-jump
          // instruction in the block.
          nextBlock = instructionsIterator.split(code, blocksIterator);
          assert nextBlock.getPredecessors().size() == 1;
          assert currentBlock == nextBlock.getPredecessors().get(0);
          // Back up to before the split before inserting catch handlers.
          BasicBlock b = blocksIterator.previous();
          assert b == nextBlock;
        } else {
          nextBlock = null;
        }
        currentBlock.copyCatchHandlers(code, blocksIterator, invokeBlock);
        if (nextBlock != null) {
          BasicBlock b = blocksIterator.next();
          assert b == nextBlock;
          // Switch iteration to the split block.
          instructionsIterator = nextBlock.listIterator();
        } else {
          instructionsIterator = null;
        }
        currentBlock = nextBlock;
      } else {
        assert !instructionsIterator.hasNext();
        instructionsIterator = null;
        currentBlock = null;
      }
    }
  }

  private void appendCatchHandlers(IRCode code, BasicBlock invokeBlock,
      IRCode inlinee, ListIterator<BasicBlock> blocksIterator) {
    BasicBlock inlineeBlock = null;
    // Move back through the inlinee blocks added (they are now in the basic blocks list).
    for (int i = 0; i < inlinee.blocks.size(); i++) {
      inlineeBlock = blocksIterator.previous();
    }
    assert inlineeBlock == inlinee.blocks.getFirst();
    // Position right after the empty invoke block.
    inlineeBlock = blocksIterator.next();
    assert inlineeBlock == inlinee.blocks.getFirst();

    // Iterate through the inlined blocks (they are now in the basic blocks list).
    Iterator<BasicBlock> inlinedBlocksIterator = inlinee.blocks.iterator();
    while (inlinedBlocksIterator.hasNext()) {
      BasicBlock inlinedBlock = inlinedBlocksIterator.next();
      assert inlineeBlock == inlinedBlock;  // Iterators must be in sync.
      if (inlinedBlock.hasCatchHandlers()) {
        // The block already has catch handlers, so it has only one throwing instruction, and no
        // splitting is required.
        inlinedBlock.copyCatchHandlers(code, blocksIterator, invokeBlock);
      } else {
        // The block does not have catch handlers, so it can have several throwing instructions.
        // Therefore the block must be split after each throwing instruction, and the catch
        // handlers must be added to each of these blocks.
        splitBlockAndCopyCatchHandlers(code, invokeBlock, inlinedBlock, blocksIterator);
      }
      // Iterate to the next inlined block (if more inlined blocks).
      inlineeBlock = blocksIterator.next();
    }
  }

  private void removeArgumentInstructions(IRCode inlinee) {
    int index = 0;
    InstructionListIterator inlineeIterator = inlinee.blocks.getFirst().listIterator();
    List<Value> arguments = inlinee.collectArguments();
    while (inlineeIterator.hasNext()) {
      Instruction instruction = inlineeIterator.next();
      if (instruction.isArgument()) {
        assert !instruction.outValue().isUsed();
        assert instruction.outValue() == arguments.get(index++);
        inlineeIterator.remove();
      }
    }
  }

  @Override
  public BasicBlock inlineInvoke(
      IRCode code, IRCode inlinee, ListIterator<BasicBlock> blocksIterator,
      List<BasicBlock> blocksToRemove, DexType downcast) {
    assert blocksToRemove != null;
    boolean inlineeCanThrow = canThrow(inlinee);
    BasicBlock invokeBlock = split(code, 1, blocksIterator);
    assert invokeBlock.getInstructions().size() == 2;
    assert invokeBlock.getInstructions().getFirst().isInvoke();

    // Invalidate position-on-throwing-instructions property if it does not hold for the inlinee.
    if (!inlinee.doAllThrowingInstructionsHavePositions()) {
      code.setAllThrowingInstructionsHavePositions(false);
    }

    // Split the invoke instruction into a separate block.
    Invoke invoke = invokeBlock.getInstructions().getFirst().asInvoke();
    BasicBlock invokePredecessor = invokeBlock.getPredecessors().get(0);
    BasicBlock invokeSuccessor = invokeBlock.getSuccessors().get(0);

    CheckCast castInstruction = null;
    // Map all argument values, and remove the arguments instructions in the inlinee.
    List<Value> arguments = inlinee.collectArguments();
    assert invoke.inValues().size() == arguments.size();
    for (int i = 0; i < invoke.inValues().size(); i++) {
      // TODO(zerny): Support inlining in --debug mode.
      assert !arguments.get(i).hasLocalInfo();
      if ((i == 0) && (downcast != null)) {
        Value invokeValue = invoke.inValues().get(0);
        Value receiverValue = arguments.get(0);
        Value value = code.createValue(ValueType.OBJECT);
        castInstruction = new CheckCast(value, invokeValue, downcast);
        castInstruction.setPosition(invoke.getPosition());
        receiverValue.replaceUsers(value);
      } else {
        arguments.get(i).replaceUsers(invoke.inValues().get(i));
      }
    }
    removeArgumentInstructions(inlinee);
    if (castInstruction != null) {
      // Splice in the check cast operation.
      inlinee.blocks.getFirst().listIterator().split(inlinee);
      BasicBlock newBlock = inlinee.blocks.getFirst();
      assert newBlock.getInstructions().size() == 1;
      newBlock.getInstructions().addFirst(castInstruction);
      castInstruction.setBlock(newBlock);
    }

    // The inline entry is the first block now the argument instructions are gone.
    BasicBlock inlineEntry = inlinee.blocks.getFirst();

    BasicBlock inlineExit = null;
    ImmutableList<BasicBlock> normalExits = inlinee.computeNormalExitBlocks();
    if (normalExits.isEmpty()) {
      assert inlineeCanThrow;
      // TODO(sgjesse): Remove this restriction.
      assert !invokeBlock.hasCatchHandlers();
      blocksToRemove.addAll(
          invokePredecessor.unlink(invokeBlock, new DominatorTree(code, blocksToRemove)));
    } else {
      // Ensure and locate the single return instruction of the inlinee.
      InstructionListIterator inlineeIterator = ensureSingleReturnInstruction(inlinee, normalExits);

      // Replace the invoke value with the return value if non-void.
      assert inlineeIterator.peekNext().isReturn();
      if (invoke.outValue() != null) {
        Return returnInstruction = inlineeIterator.peekNext().asReturn();
        invoke.outValue().replaceUsers(returnInstruction.returnValue());
      }

      // Split before return and unlink return.
      BasicBlock returnBlock = inlineeIterator.split(inlinee);
      inlineExit = returnBlock.unlinkSinglePredecessor();
      InstructionListIterator returnBlockIterator = returnBlock.listIterator();
      returnBlockIterator.next();
      returnBlockIterator.remove();  // This clears out the users from the return.
      assert !returnBlockIterator.hasNext();
      inlinee.blocks.remove(returnBlock);

      // Leaving the invoke block in the graph as an empty block. Still unlink its predecessor as
      // the exit block of the inlinee will become its new predecessor.
      invokeBlock.unlinkSinglePredecessor();
      InstructionListIterator invokeBlockIterator = invokeBlock.listIterator();
      invokeBlockIterator.next();
      invokeBlockIterator.remove();
      invokeSuccessor = invokeBlock;
    }

    // Link the inlinee into the graph.
    invokePredecessor.link(inlineEntry);
    if (inlineExit != null) {
      inlineExit.link(invokeSuccessor);
    }

    // Position the block iterator cursor just after the invoke block.
    if (blocksIterator == null) {
      // If no block iterator was passed create one for the insertion of the inlinee blocks.
      blocksIterator = code.blocks.listIterator(code.blocks.indexOf(invokeBlock));
      blocksIterator.next();
    } else {
      // If a blocks iterator was passed, back up to the block with the invoke instruction and
      // remove it.
      blocksIterator.previous();
      blocksIterator.previous();
    }

    // Insert inlinee blocks into the IR code.
    int blockNumber = code.getHighestBlockNumber() + 1;
    for (BasicBlock bb : inlinee.blocks) {
      bb.setNumber(blockNumber++);
      blocksIterator.add(bb);
    }

    // If the invoke block had catch handlers copy those down to all inlined blocks.
    if (invokeBlock.hasCatchHandlers()) {
      appendCatchHandlers(code, invokeBlock, inlinee, blocksIterator);
    }

    return invokeSuccessor;
  }

  private InstructionListIterator ensureSingleReturnInstruction(
      IRCode code,
      ImmutableList<BasicBlock> normalExits) {
    if (normalExits.size() == 1) {
      InstructionListIterator it = normalExits.get(0).listIterator();
      it.nextUntil(Instruction::isReturn);
      it.previous();
      return it;
    }
    BasicBlock newExitBlock = new BasicBlock();
    newExitBlock.setNumber(code.getHighestBlockNumber() + 1);
    Return newReturn;
    if (normalExits.get(0).exit().asReturn().isReturnVoid()) {
      newReturn = new Return();
    } else {
      boolean same = true;
      List<Value> operands = new ArrayList<>(normalExits.size());
      for (BasicBlock exitBlock : normalExits) {
        Value retValue = exitBlock.exit().asReturn().returnValue();
        operands.add(retValue);
        same = same && retValue == operands.get(0);
      }
      Value value;
      if (same) {
        value = operands.get(0);
      } else {
        Phi phi =
            new Phi(
                code.valueNumberGenerator.next(),
                newExitBlock,
                operands.get(0).outType(),
                null);
        phi.addOperands(operands);
        value = phi;
      }
      newReturn = new Return(value, value.outType());
    }
    // The newly constructed return will be eliminated as part of inlining so we set position none.
    newReturn.setPosition(Position.none());
    newExitBlock.add(newReturn);
    for (BasicBlock exitBlock : normalExits) {
      InstructionListIterator it = exitBlock.listIterator(exitBlock.getInstructions().size());
      Instruction oldExit = it.previous();
      assert oldExit.isReturn();
      it.replaceCurrentInstruction(new Goto());
      exitBlock.link(newExitBlock);
    }
    newExitBlock.close(null);
    code.blocks.add(newExitBlock);
    assert code.isConsistentSSA();
    return newExitBlock.listIterator();
  }
}
