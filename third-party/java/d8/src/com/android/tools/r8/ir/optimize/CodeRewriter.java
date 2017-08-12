// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue.DexValueBoolean;
import com.android.tools.r8.graph.DexValue.DexValueByte;
import com.android.tools.r8.graph.DexValue.DexValueChar;
import com.android.tools.r8.graph.DexValue.DexValueDouble;
import com.android.tools.r8.graph.DexValue.DexValueFloat;
import com.android.tools.r8.graph.DexValue.DexValueInt;
import com.android.tools.r8.graph.DexValue.DexValueLong;
import com.android.tools.r8.graph.DexValue.DexValueNull;
import com.android.tools.r8.graph.DexValue.DexValueShort;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.ir.code.ArrayPut;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.Binop;
import com.android.tools.r8.ir.code.CatchHandlers;
import com.android.tools.r8.ir.code.CheckCast;
import com.android.tools.r8.ir.code.Cmp;
import com.android.tools.r8.ir.code.Cmp.Bias;
import com.android.tools.r8.ir.code.ConstInstruction;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.ConstString;
import com.android.tools.r8.ir.code.DebugLocalWrite;
import com.android.tools.r8.ir.code.DominatorTree;
import com.android.tools.r8.ir.code.Goto;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.If;
import com.android.tools.r8.ir.code.If.Type;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeNewArray;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.MemberType;
import com.android.tools.r8.ir.code.NewArrayEmpty;
import com.android.tools.r8.ir.code.NewArrayFilledData;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.code.Phi;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.Return;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.ir.code.Switch;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.code.Xor;
import com.android.tools.r8.ir.conversion.OptimizationFeedback;
import com.android.tools.r8.ir.optimize.SwitchUtils.EnumSwitchInfo;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.LongInterval;
import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class CodeRewriter {

  private static final int MAX_FILL_ARRAY_SIZE = 8 * Constants.KILOBYTE;
  // This constant was determined by experimentation.
  private static final int STOP_SHARED_CONSTANT_THRESHOLD = 50;

  private final AppInfo appInfo;
  private final DexItemFactory dexItemFactory;
  private final Set<DexMethod> libraryMethodsReturningReceiver;
  private final InternalOptions options;

  public CodeRewriter(
      AppInfo appInfo, Set<DexMethod> libraryMethodsReturningReceiver, InternalOptions options) {
    this.appInfo = appInfo;
    this.options = options;
    this.dexItemFactory = appInfo.dexItemFactory;
    this.libraryMethodsReturningReceiver = libraryMethodsReturningReceiver;
  }

  private static boolean removedTrivialGotos(IRCode code) {
    ListIterator<BasicBlock> iterator = code.listIterator();
    assert iterator.hasNext();
    BasicBlock block = iterator.next();
    BasicBlock nextBlock;
    do {
      nextBlock = iterator.hasNext() ? iterator.next() : null;
      // Trivial goto block are only kept if they are self-targeting or are targeted by
      // fallthroughs.
      BasicBlock blk = block;  // Additional local for lambda below.
      assert !block.isTrivialGoto()
          || block.exit().asGoto().getTarget() == block
          || block.getPredecessors().stream().anyMatch((b) -> b.exit().fallthroughBlock() == blk);
      // Trivial goto blocks never target the next block (in that case there should just be a
      // fallthrough).
      assert !block.isTrivialGoto() || block.exit().asGoto().getTarget() != nextBlock;
      block = nextBlock;
    } while (block != null);
    return true;
  }

  private static void collapsTrivialGoto(
      BasicBlock block, BasicBlock nextBlock, List<BasicBlock> blocksToRemove) {

    // This is the base case for GOTO loops.
    if (block.exit().asGoto().getTarget() == block) {
      return;
    }

    BasicBlock target = block.endOfGotoChain();

    boolean needed = false;
    if (target != nextBlock) {
      for (BasicBlock pred : block.getPredecessors()) {
        if (pred.exit().fallthroughBlock() == block) {
          needed = true;
          break;
        }
      }
    }

    // This implies we are in a loop of GOTOs. In that case, we will iteratively remove each trival
    // GOTO one-by-one until the above base case (one block targeting itself) is left.
    if (target == null) {
      target = block.exit().asGoto().getTarget();
    }

    if (!needed) {
      blocksToRemove.add(block);
      for (BasicBlock pred : block.getPredecessors()) {
        pred.replaceSuccessor(block, target);
      }
      for (BasicBlock succ : block.getSuccessors()) {
        succ.getPredecessors().remove(block);
      }
      for (BasicBlock pred : block.getPredecessors()) {
        if (!target.getPredecessors().contains(pred)) {
          target.getPredecessors().add(pred);
        }
      }
    }
  }

  private static void collapsIfTrueTarget(BasicBlock block) {
    If insn = block.exit().asIf();
    BasicBlock target = insn.getTrueTarget();
    BasicBlock newTarget = target.endOfGotoChain();
    BasicBlock fallthrough = insn.fallthroughBlock();
    BasicBlock newFallthrough = fallthrough.endOfGotoChain();
    if (newTarget != null && target != newTarget) {
      insn.getBlock().replaceSuccessor(target, newTarget);
      target.getPredecessors().remove(block);
      if (!newTarget.getPredecessors().contains(block)) {
        newTarget.getPredecessors().add(block);
      }
    }
    if (block.exit().isIf()) {
      insn = block.exit().asIf();
      if (insn.getTrueTarget() == newFallthrough) {
        // Replace if with the same true and fallthrough target with a goto to the fallthrough.
        block.replaceSuccessor(insn.getTrueTarget(), fallthrough);
        assert block.exit().isGoto();
        assert block.exit().asGoto().getTarget() == fallthrough;
      }
    }
  }

  private static void collapsNonFallthroughSwitchTargets(BasicBlock block) {
    Switch insn = block.exit().asSwitch();
    BasicBlock fallthroughBlock = insn.fallthroughBlock();
    Set<BasicBlock> replacedBlocks = new HashSet<>();
    for (int j = 0; j < insn.targetBlockIndices().length; j++) {
      BasicBlock target = insn.targetBlock(j);
      if (target != fallthroughBlock) {
        BasicBlock newTarget = target.endOfGotoChain();
        if (newTarget != null && target != newTarget && !replacedBlocks.contains(target)) {
          insn.getBlock().replaceSuccessor(target, newTarget);
          target.getPredecessors().remove(block);
          if (!newTarget.getPredecessors().contains(block)) {
            newTarget.getPredecessors().add(block);
          }
          replacedBlocks.add(target);
        }
      }
    }
  }

  // TODO(sgjesse); Move this somewhere else, and reuse it for some of the other switch rewritings.
  public abstract static class InstructionBuilder<T> {
    protected int blockNumber;
    protected final Position position;

    protected InstructionBuilder(Position position) {
      this.position = position;
    }

    public abstract T self();

    public T setBlockNumber(int blockNumber) {
      this.blockNumber = blockNumber;
      return self();
    }
  }

  public static class SwitchBuilder extends InstructionBuilder<SwitchBuilder> {
    private Value value;
    private Int2ObjectSortedMap<BasicBlock> keyToTarget = new Int2ObjectAVLTreeMap<>();
    private BasicBlock fallthrough;

    public SwitchBuilder(Position position) {
      super(position);
    }

    @Override
    public SwitchBuilder self() {
      return this;
    }

    public SwitchBuilder setValue(Value value) {
      this.value = value;
      return  this;
    }

    public SwitchBuilder addKeyAndTarget(int key, BasicBlock target) {
      keyToTarget.put(key, target);
      return this;
    }

    public SwitchBuilder setFallthrough(BasicBlock fallthrough) {
      this.fallthrough = fallthrough;
      return this;
    }

    public BasicBlock build() {
      final int NOT_FOUND = -1;
      Object2IntMap<BasicBlock> targetToSuccessorIndex = new Object2IntLinkedOpenHashMap<>();
      targetToSuccessorIndex.defaultReturnValue(NOT_FOUND);

      int[] keys = new int[keyToTarget.size()];
      int[] targetBlockIndices = new int[keyToTarget.size()];
      // Sort keys descending.
      int count = 0;
      IntIterator iter = keyToTarget.keySet().iterator();
      while (iter.hasNext()) {
        int key = iter.nextInt();
        BasicBlock target = keyToTarget.get(key);
        Integer targetIndex =
            targetToSuccessorIndex.computeIfAbsent(target, b -> targetToSuccessorIndex.size());
        keys[count] = key;
        targetBlockIndices[count] = targetIndex;
        count++;
      }
      Integer fallthroughIndex =
          targetToSuccessorIndex.computeIfAbsent(fallthrough, b -> targetToSuccessorIndex.size());
      Switch newSwitch = new Switch(value, keys, targetBlockIndices, fallthroughIndex);
      newSwitch.setPosition(position);
      BasicBlock newSwitchBlock = BasicBlock.createSwitchBlock(blockNumber, newSwitch);
      for (BasicBlock successor : targetToSuccessorIndex.keySet()) {
        newSwitchBlock.link(successor);
      }
      return newSwitchBlock;
    }
  }

  public static class IfBuilder extends InstructionBuilder<IfBuilder> {
    private final IRCode code;
    private Value left;
    private int right;
    private BasicBlock target;
    private BasicBlock fallthrough;

    public IfBuilder(Position position, IRCode code) {
      super(position);
      this.code = code;
    }

    @Override
    public IfBuilder self() {
      return this;
    }

    public IfBuilder setLeft(Value left) {
      this.left = left;
      return  this;
    }

    public IfBuilder setRight(int right) {
      this.right = right;
      return  this;
    }

    public IfBuilder setTarget(BasicBlock target) {
      this.target = target;
      return this;
    }

    public IfBuilder setFallthrough(BasicBlock fallthrough) {
      this.fallthrough = fallthrough;
      return this;
    }

    public BasicBlock build() {
      assert target != null;
      assert fallthrough != null;
      If newIf;
      BasicBlock ifBlock;
      if (right != 0) {
        ConstNumber rightConst = code.createIntConstant(right);
        rightConst.setPosition(position);
        newIf = new If(Type.EQ, ImmutableList.of(left, rightConst.dest()));
        ifBlock = BasicBlock.createIfBlock(blockNumber, newIf, rightConst);
      } else {
        newIf = new If(Type.EQ, left);
        ifBlock = BasicBlock.createIfBlock(blockNumber, newIf);
      }
      newIf.setPosition(position);
      ifBlock.link(target);
      ifBlock.link(fallthrough);
      return ifBlock;
    }
  }

  /**
   * Covert the switch instruction to a sequence of if instructions checking for a specified
   * set of keys, followed by a new switch with the remaining keys.
   */
  private void convertSwitchToSwitchAndIfs(
      IRCode code, ListIterator<BasicBlock> blocksIterator, BasicBlock originalBlock,
      InstructionListIterator iterator, Switch theSwitch,
      List<IntList> switches, IntList keysToRemove) {

    Position position = theSwitch.getPosition();

    // Extract the information from the switch before removing it.
    Int2ReferenceSortedMap<BasicBlock> keyToTarget = theSwitch.getKeyToTargetMap();

    // Keep track of the current fallthrough, starting with the original.
    BasicBlock fallthroughBlock = theSwitch.fallthroughBlock();

    // Split the switch instruction into its own block and remove it.
    iterator.previous();
    BasicBlock originalSwitchBlock = iterator.split(code, blocksIterator);
    assert !originalSwitchBlock.hasCatchHandlers();
    assert originalSwitchBlock.getInstructions().size() == 1;
    assert originalBlock.exit().isGoto();
    theSwitch.moveDebugValues(originalBlock.exit());
    blocksIterator.remove();
    theSwitch.getBlock().detachAllSuccessors();
    BasicBlock block = theSwitch.getBlock().unlinkSinglePredecessor();
    assert theSwitch.getBlock().getPredecessors().size() == 0;
    assert theSwitch.getBlock().getSuccessors().size() == 0;
    assert block == originalBlock;

    // Collect the new blocks for adding to the block list.
    int nextBlockNumber = code.getHighestBlockNumber() + 1;
    LinkedList<BasicBlock> newBlocks = new LinkedList<>();

    // Build the switch-blocks backwards, to always have the fallthrough block in hand.
    for (int i = switches.size() - 1; i >= 0; i--) {
      SwitchBuilder switchBuilder = new SwitchBuilder(position);
      switchBuilder.setValue(theSwitch.value());
      IntList keys = switches.get(i);
      for (int j = 0; j < keys.size(); j++) {
        int key = keys.getInt(j);
        switchBuilder.addKeyAndTarget(key, keyToTarget.get(key));
      }
      switchBuilder
          .setFallthrough(fallthroughBlock)
          .setBlockNumber(nextBlockNumber++);
      BasicBlock newSwitchBlock = switchBuilder.build();
      newBlocks.addFirst(newSwitchBlock);
      fallthroughBlock = newSwitchBlock;
    }

    // Build the if-blocks backwards, to always have the fallthrough block in hand.
    for (int i = keysToRemove.size() - 1; i >= 0; i--) {
      int key = keysToRemove.getInt(i);
      BasicBlock peeledOffTarget = keyToTarget.get(key);
      IfBuilder ifBuilder = new IfBuilder(position, code);
      ifBuilder
          .setLeft(theSwitch.value())
          .setRight(key)
          .setTarget(peeledOffTarget)
          .setFallthrough(fallthroughBlock)
          .setBlockNumber(nextBlockNumber++);
      BasicBlock ifBlock = ifBuilder.build();
      newBlocks.addFirst(ifBlock);
      fallthroughBlock = ifBlock;
    }

    // Finally link the block before the original switch to the new block sequence.
    originalBlock.link(fallthroughBlock);

    // Finally add the blocks.
    newBlocks.forEach(blocksIterator::add);
  }

  public void rewriteSwitch(IRCode code) {
    ListIterator<BasicBlock> blocksIterator = code.listIterator();
    while (blocksIterator.hasNext()) {
      BasicBlock block = blocksIterator.next();
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        Instruction instruction = iterator.next();
        if (instruction.isSwitch()) {
          Switch theSwitch = instruction.asSwitch();
          if (theSwitch.numberOfKeys() == 1) {
            // Rewrite the switch to an if.
            int fallthroughBlockIndex = theSwitch.getFallthroughBlockIndex();
            int caseBlockIndex = theSwitch.targetBlockIndices()[0];
            if (fallthroughBlockIndex < caseBlockIndex) {
              block.swapSuccessorsByIndex(fallthroughBlockIndex, caseBlockIndex);
            }
            if (theSwitch.getFirstKey() == 0) {
              iterator.replaceCurrentInstruction(new If(Type.EQ, theSwitch.value()));
            } else {
              ConstNumber labelConst = code.createIntConstant(theSwitch.getFirstKey());
              labelConst.setPosition(theSwitch.getPosition());
              iterator.previous();
              iterator.add(labelConst);
              Instruction dummy = iterator.next();
              assert dummy == theSwitch;
              If theIf = new If(Type.EQ, ImmutableList.of(theSwitch.value(), labelConst.dest()));
              iterator.replaceCurrentInstruction(theIf);
            }
          } else {
            // Split keys into outliers and sequences.
            List<IntList> sequences = new ArrayList<>();
            IntList outliers = new IntArrayList();

            IntList current = new IntArrayList();
            int[] keys = theSwitch.getKeys();
            int previousKey = keys[0];
            current.add(previousKey);
            for (int i = 1; i < keys.length; i++) {
              assert current.size() > 0;
              assert current.getInt(current.size() - 1) == previousKey;
              int key = keys[i];
              if (((long) key - (long) previousKey) > 1) {
                if (current.size() == 1) {
                  outliers.add(previousKey);
                } else {
                  sequences.add(current);
                }
                current = new IntArrayList();
              }
              current.add(key);
              previousKey = key;
            }
            if (current.size() == 1) {
              outliers.add(previousKey);
            } else {
              sequences.add(current);
            }

            // Get the existing dex size for the payload and switch.
            int currentSize = Switch.payloadSize(keys) + Switch.estimatedDexSize();

            // Never replace with more than 10 if/switch instructions.
            if (outliers.size() + sequences.size() <= 10) {
              // Calculate estimated size for splitting into ifs and switches.
              long rewrittenSize = 0;
              for (Integer outlier : outliers) {
                if (outlier != 0) {
                  rewrittenSize += ConstNumber.estimatedDexSize(
                      theSwitch.value().outType(), outlier);
                }
                rewrittenSize += If.estimatedDexSize();
              }
              for (List<Integer> sequence : sequences) {
                rewrittenSize += Switch.payloadSize(sequence);
              }
              rewrittenSize += Switch.estimatedDexSize() * sequences.size();

              if (rewrittenSize < currentSize) {
                convertSwitchToSwitchAndIfs(
                    code, blocksIterator, block, iterator, theSwitch, sequences, outliers);
              }
            } else if (outliers.size() > 1) {
              // Calculate estimated size for splitting into switches (packed for the sequences
              // and sparse for the outliers).
              long rewrittenSize = 0;
              for (List<Integer> sequence : sequences) {
                rewrittenSize += Switch.payloadSize(sequence);
              }
              rewrittenSize += Switch.payloadSize(outliers);
              rewrittenSize += Switch.estimatedDexSize() * (sequences.size() + 1);

              if (rewrittenSize < currentSize) {
                // Create a copy to not modify sequences.
                List<IntList> seqs = new ArrayList<>(sequences);
                seqs.add(outliers);
                convertSwitchToSwitchAndIfs(
                    code, blocksIterator, block, iterator, theSwitch, seqs, IntLists.EMPTY_LIST);
              }
            }
          }
        }
      }
    }
    // Rewriting of switches introduces new branching structure. It relies on critical edges
    // being split on the way in but does not maintain this property. We therefore split
    // critical edges at exit.
    code.splitCriticalEdges();
    code.traceBlocks();
    assert code.isConsistentSSA();
  }

  /**
   * Inline the indirection of switch maps into the switch statement.
   * <p>
   * To ensure binary compatibility, javac generated code does not use ordinal values of enums
   * directly in switch statements but instead generates a companion class that computes a mapping
   * from switch branches to ordinals at runtime. As we have whole-program knowledge, we can
   * analyze these maps and inline the indirection into the switch map again.
   * <p>
   * In particular, we look for code of the form
   *
   * <blockquote><pre>
   * switch(CompanionClass.$switchmap$field[enumValue.ordinal()]) {
   *   ...
   * }
   * </pre></blockquote>
   */
  public void removeSwitchMaps(IRCode code) {
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        Instruction insn = it.next();
        // Pattern match a switch on a switch map as input.
        if (insn.isSwitch()) {
          Switch switchInsn = insn.asSwitch();
          EnumSwitchInfo info = SwitchUtils
              .analyzeSwitchOverEnum(switchInsn, appInfo.withLiveness());
          if (info != null) {
            Int2IntMap targetMap = new Int2IntArrayMap();
            IntList keys = new IntArrayList(switchInsn.numberOfKeys());
            for (int i = 0; i < switchInsn.numberOfKeys(); i++) {
              assert switchInsn.targetBlockIndices()[i] != switchInsn.getFallthroughBlockIndex();
              int key = info.ordinalsMap.getInt(info.indexMap.get(switchInsn.getKey(i)));
              keys.add(key);
              targetMap.put(key, switchInsn.targetBlockIndices()[i]);
            }
            keys.sort(Comparator.naturalOrder());
            int[] targets = new int[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
              targets[i] = targetMap.get(keys.getInt(i));
            }

            Switch newSwitch = new Switch(info.ordinalInvoke.outValue(), keys.toIntArray(),
                targets, switchInsn.getFallthroughBlockIndex());
            // Replace the switch itself.
            it.replaceCurrentInstruction(newSwitch);
            // If the original input to the switch is now unused, remove it too. It is not dead
            // as it might have side-effects but we ignore these here.
            Instruction arrayGet = info.arrayGet;
            if (arrayGet.outValue().numberOfUsers() == 0) {
              arrayGet.inValues().forEach(v -> v.removeUser(arrayGet));
              arrayGet.getBlock().removeInstruction(arrayGet);
            }
            Instruction staticGet = info.staticGet;
            if (staticGet.outValue().numberOfUsers() == 0) {
              assert staticGet.inValues().isEmpty();
              staticGet.getBlock().removeInstruction(staticGet);
            }
          }
        }
      }
    }
  }

  /**
   * Rewrite all branch targets to the destination of trivial goto chains when possible.
   * Does not rewrite fallthrough targets as that would require block reordering and the
   * transformation only makes sense after SSA destruction where there are no phis.
   */
  public static void collapsTrivialGotos(DexEncodedMethod method, IRCode code) {
    assert code.isConsistentGraph();
    List<BasicBlock> blocksToRemove = new ArrayList<>();
    // Rewrite all non-fallthrough targets to the end of trivial goto chains and remove
    // first round of trivial goto blocks.
    ListIterator<BasicBlock> iterator = code.listIterator();
    assert iterator.hasNext();
    BasicBlock block = iterator.next();
    BasicBlock nextBlock;

    // The marks will be used for cycle detection.
    code.clearMarks();
    do {
      nextBlock = iterator.hasNext() ? iterator.next() : null;
      if (block.isTrivialGoto()) {
        collapsTrivialGoto(block, nextBlock, blocksToRemove);
      }
      if (block.exit().isIf()) {
        collapsIfTrueTarget(block);
      }
      if (block.exit().isSwitch()) {
        collapsNonFallthroughSwitchTargets(block);
      }
      block = nextBlock;
    } while (nextBlock != null);
    code.removeBlocks(blocksToRemove);
    // Get rid of gotos to the next block.
    while (!blocksToRemove.isEmpty()) {
      blocksToRemove = new ArrayList<>();
      iterator = code.listIterator();
      block = iterator.next();
      do {
        nextBlock = iterator.hasNext() ? iterator.next() : null;
        if (block.isTrivialGoto()) {
          collapsTrivialGoto(block, nextBlock, blocksToRemove);
        }
        block = nextBlock;
      } while (block != null);
      code.removeBlocks(blocksToRemove);
    }
    assert removedTrivialGotos(code);
    assert code.isConsistentGraph();
  }

  public void identifyReturnsArgument(
      DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    List<BasicBlock> normalExits = code.computeNormalExitBlocks();
    if (normalExits.isEmpty()) {
      return;
    }
    Return firstExit = normalExits.get(0).exit().asReturn();
    if (firstExit.isReturnVoid()) {
      return;
    }
    Value returnValue = firstExit.returnValue();
    boolean isNeverNull = returnValue.isNeverNull();
    for (int i = 1; i < normalExits.size(); i++) {
      Return exit = normalExits.get(i).exit().asReturn();
      Value value = exit.returnValue();
      if (value != returnValue) {
        returnValue = null;
      }
      isNeverNull = isNeverNull && value.isNeverNull();
    }
    if (returnValue != null) {
      if (returnValue.isArgument()) {
        // Find the argument number.
        int index = code.collectArguments().indexOf(returnValue);
        assert index != -1;
        feedback.methodReturnsArgument(method, index);
      }
      if (returnValue.isConstant() && returnValue.definition.isConstNumber()) {
        long value = returnValue.definition.asConstNumber().getRawValue();
        feedback.methodReturnsConstant(method, value);
      }
    }
    if (isNeverNull) {
      feedback.methodNeverReturnsNull(method);
    }
  }

  private boolean checkArgumentType(InvokeMethod invoke, DexMethod target, int argumentIndex) {
    DexType returnType = invoke.getInvokedMethod().proto.returnType;
    // TODO(sgjesse): Insert cast if required.
    if (invoke.isInvokeStatic()) {
      return invoke.getInvokedMethod().proto.parameters.values[argumentIndex] == returnType;
    } else {
      if (argumentIndex == 0) {
        return invoke.getInvokedMethod().getHolder() == returnType;
      } else {
        return invoke.getInvokedMethod().proto.parameters.values[argumentIndex - 1] == returnType;
      }
    }
  }

  // Replace result uses for methods where something is known about what is returned.
  public void rewriteMoveResult(IRCode code) {
    AppInfoWithSubtyping appInfoWithSubtyping = appInfo.withSubtyping();
    InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      Instruction current = iterator.next();
      if (current.isInvokeMethod()) {
        InvokeMethod invoke = current.asInvokeMethod();
        if (invoke.outValue() != null && !invoke.outValue().hasLocalInfo()) {
          boolean isLibraryMethodReturningReceiver =
              libraryMethodsReturningReceiver.contains(invoke.getInvokedMethod());
          if (isLibraryMethodReturningReceiver) {
            if (checkArgumentType(invoke, invoke.getInvokedMethod(), 0)) {
              invoke.outValue().replaceUsers(invoke.arguments().get(0));
              invoke.setOutValue(null);
            }
          } else if (appInfoWithSubtyping != null) {
            DexEncodedMethod target = invoke.computeSingleTarget(appInfoWithSubtyping);
            if (target != null) {
              DexMethod invokedMethod = target.method;
              // Check if the invoked method is known to return one of its arguments.
              DexEncodedMethod definition = appInfo.definitionFor(invokedMethod);
              if (definition != null && definition.getOptimizationInfo().returnsArgument()) {
                int argumentIndex = definition.getOptimizationInfo().getReturnedArgument();
                // Replace the out value of the invoke with the argument and ignore the out value.
                if (argumentIndex != -1 && checkArgumentType(invoke, target.method,
                    argumentIndex)) {
                  Value argument = invoke.arguments().get(argumentIndex);
                  assert invoke.outType().compatible(argument.outType())
                      || (!options.outputClassFiles
                            && invoke.outType() == ValueType.OBJECT
                            && argument.outType().isSingle()
                            && argument.isZero());
                  invoke.outValue().replaceUsers(argument);
                  invoke.setOutValue(null);
                }
              }
            }
          }
        }
      }
    }
    assert code.isConsistentGraph();
  }


  /**
   * For supporting assert javac adds the static field $assertionsDisabled to all classes which
   * have methods with assertions. This is used to support the Java VM -ea flag.
   *
   * The class:
   * <pre>
   * class A {
   *   void m() {
   *     assert xxx;
   *   }
   * }
   * </pre>
   * Is compiled into:
   * <pre>
   * class A {
   *   static boolean $assertionsDisabled;
   *   static {
   *     $assertionsDisabled = A.class.desiredAssertionStatus();
   *   }
   *
   *   // method with "assert xxx";
   *   void m() {
   *     if (!$assertionsDisabled) {
   *       if (xxx) {
   *         throw new AssertionError(...);
   *       }
   *     }
   *   }
   * }
   * </pre>
   * With the rewriting below (and other rewritings) the resulting code is:
   * <pre>
   * class A {
   *   void m() {
   *   }
   * }
   * </pre>
   */
  public void disableAssertions(IRCode code) {
    InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      Instruction current = iterator.next();
      if (current.isInvokeMethod()) {
        InvokeMethod invoke = current.asInvokeMethod();
        if (invoke.getInvokedMethod() == dexItemFactory.classMethods.desiredAssertionStatus) {
          iterator.replaceCurrentInstruction(code.createFalse());
        }
      } else if (current.isStaticPut()) {
        StaticPut staticPut = current.asStaticPut();
        if (staticPut.getField().name == dexItemFactory.assertionsDisabled) {
          iterator.remove();
        }
      } else if (current.isStaticGet()) {
        StaticGet staticGet = current.asStaticGet();
        if (staticGet.getField().name == dexItemFactory.assertionsDisabled) {
          iterator.replaceCurrentInstruction(code.createTrue());
        }
      }
    }
  }

  // Check if the static put is a constant derived from the class holding the method.
  // This checks for java.lang.Class.getName and java.lang.Class.getSimpleName.
  private boolean isClassNameConstant(DexEncodedMethod method, StaticPut put) {
    if (put.getField().type != dexItemFactory.stringType) {
      return false;
    }
    if (put.inValue().definition != null && put.inValue().definition.isInvokeVirtual()) {
      InvokeVirtual invoke = put.inValue().definition.asInvokeVirtual();
      if ((invoke.getInvokedMethod() == dexItemFactory.classMethods.getSimpleName
          || invoke.getInvokedMethod() == dexItemFactory.classMethods.getName)
          && !invoke.inValues().get(0).isPhi()
          && invoke.inValues().get(0).definition.isConstClass()
          && invoke.inValues().get(0).definition.asConstClass().getValue()
          == method.method.getHolder()) {
        return true;
      }
    }
    return false;
  }

  public void collectClassInitializerDefaults(DexEncodedMethod method, IRCode code) {
    if (!method.isClassInitializer()) {
      return;
    }

    // Collect relevant static put which are dominated by the exit block, and not dominated by a
    // static get.
    // This does not check for instructions that can throw. However, as classes which throw in the
    // class initializer are never visible to the program (see Java Virtual Machine Specification
    // section 5.5, https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.5), this
    // does not matter (except maybe for removal of const-string instructions, but that is
    // acceptable).
    if (code.computeNormalExitBlocks().isEmpty()) {
      return;
    }
    DexClass clazz = appInfo.definitionFor(method.method.getHolder());
    if (clazz == null) {
      // TODO(67672280): Synthesized lambda classes are also optimized. However, they are not
      // added to the AppInfo.
      assert method.accessFlags.isSynthetic();
      return;
    }
    DominatorTree dominatorTree = new DominatorTree(code);
    Set<StaticPut> puts = Sets.newIdentityHashSet();
    Map<DexField, StaticPut> dominatingPuts = Maps.newIdentityHashMap();
    for (BasicBlock block : dominatorTree.normalExitDominatorBlocks()) {
      InstructionListIterator iterator = block.listIterator(block.getInstructions().size());
      while (iterator.hasPrevious()) {
        Instruction current = iterator.previous();
        if (current.isStaticPut()) {
          StaticPut put = current.asStaticPut();
          DexField field = put.getField();
          if (clazz.definesStaticField(field)) {
            if (put.inValue().isConstant()) {
              if ((field.type.isClassType() || field.type.isArrayType())
                  && put.inValue().isZero()) {
                // Collect put of zero as a potential default value.
                dominatingPuts.putIfAbsent(put.getField(), put);
                puts.add(put);
              } else if (field.type.isPrimitiveType() || field.type == dexItemFactory.stringType) {
                // Collect put as a potential default value.
                dominatingPuts.putIfAbsent(put.getField(), put);
                puts.add(put);
              }
            } else if (isClassNameConstant(method, put)) {
              // Collect put of class name constant as a potential default value.
              dominatingPuts.putIfAbsent(put.getField(), put);
              puts.add(put);
            }
          }
        }
        if (current.isStaticGet()) {
          // If a static field is read, any collected potential default value cannot be a
          // default value.
          DexField field = current.asStaticGet().getField();
          if (dominatingPuts.containsKey(field)) {
            dominatingPuts.remove(field);
            Iterables.removeIf(puts, put -> put.getField() == field);
          }
        }
      }
    }

    if (!puts.isEmpty()) {
      // Set initial values for static fields from the definitive static put instructions collected.
      for (StaticPut put : dominatingPuts.values()) {
        DexField field = put.getField();
        DexEncodedField encodedField = appInfo.definitionFor(field);
        if (field.type == dexItemFactory.stringType) {
          if (put.inValue().isConstant()) {
            if (put.inValue().isConstNumber()) {
              assert put.inValue().isZero();
              encodedField.staticValue = DexValueNull.NULL;
            } else {
              ConstString cnst = put.inValue().getConstInstruction().asConstString();
              encodedField.staticValue = new DexValueString(cnst.getValue());
            }
          } else {
            InvokeVirtual invoke = put.inValue().definition.asInvokeVirtual();
            String name = method.method.getHolder().toSourceString();
            if (invoke.getInvokedMethod() == dexItemFactory.classMethods.getSimpleName) {
              String simpleName = name.substring(name.lastIndexOf('.') + 1);
              encodedField.staticValue =
                  new DexValueString(dexItemFactory.createString(simpleName));
            } else {
              assert invoke.getInvokedMethod() == dexItemFactory.classMethods.getName;
              encodedField.staticValue = new DexValueString(dexItemFactory.createString(name));
            }
          }
        } else if (field.type.isClassType() || field.type.isArrayType()) {
          if (put.inValue().isZero()) {
            encodedField.staticValue = DexValueNull.NULL;
          } else {
            throw new Unreachable("Unexpected default value for field type " + field.type + ".");
          }
        } else {
          ConstNumber cnst = put.inValue().getConstInstruction().asConstNumber();
          if (field.type == dexItemFactory.booleanType) {
            encodedField.staticValue = DexValueBoolean.create(cnst.getBooleanValue());
          } else if (field.type == dexItemFactory.byteType) {
            encodedField.staticValue = DexValueByte.create((byte) cnst.getIntValue());
          } else if (field.type == dexItemFactory.shortType) {
            encodedField.staticValue = DexValueShort.create((short) cnst.getIntValue());
          } else if (field.type == dexItemFactory.intType) {
            encodedField.staticValue = DexValueInt.create(cnst.getIntValue());
          } else if (field.type == dexItemFactory.longType) {
            encodedField.staticValue = DexValueLong.create(cnst.getLongValue());
          } else if (field.type == dexItemFactory.floatType) {
            encodedField.staticValue = DexValueFloat.create(cnst.getFloatValue());
          } else if (field.type == dexItemFactory.doubleType) {
            encodedField.staticValue = DexValueDouble.create(cnst.getDoubleValue());
          } else if (field.type == dexItemFactory.charType) {
            encodedField.staticValue = DexValueChar.create((char) cnst.getIntValue());
          } else {
            throw new Unreachable("Unexpected field type " + field.type + ".");
          }
        }
      }

      // Remove the static put instructions now replaced by static filed initial values.
      List<Instruction> toRemove = new ArrayList<>();
      for (BasicBlock block : dominatorTree.normalExitDominatorBlocks()) {
        InstructionListIterator iterator = block.listIterator();
        while (iterator.hasNext()) {
          Instruction current = iterator.next();
          if (current.isStaticPut() && puts.contains(current.asStaticPut())) {
            iterator.remove();
            // Collect, for removal, the instruction that created the value for the static put,
            // if all users are gone. This is done even if these instructions can throw as for
            // the current patterns matched these exceptions are not detectable.
            StaticPut put = current.asStaticPut();
            if (put.inValue().uniqueUsers().size() == 0) {
              if (put.inValue().isConstString()) {
                toRemove.add(put.inValue().definition);
              } else if (put.inValue().definition.isInvokeVirtual()) {
                toRemove.add(put.inValue().definition);
              }
            }
          }
        }
      }

      // Remove the instructions collected for removal.
      if (toRemove.size() > 0) {
        for (BasicBlock block : dominatorTree.normalExitDominatorBlocks()) {
          InstructionListIterator iterator = block.listIterator();
          while (iterator.hasNext()) {
            if (toRemove.contains(iterator.next())) {
              iterator.remove();
            }
          }
        }
      }
    }
  }

  /**
   * Due to inlining, we might see chains of casts on subtypes. It suffices to cast to the lowest
   * subtype, as that will fail if a cast on a supertype would have failed.
   */
  public void removeCastChains(IRCode code) {
    InstructionIterator it = code.instructionIterator();
    while (it.hasNext()) {
      Instruction current = it.next();
      if (current.isCheckCast()
          && current.getLocalInfo() == null
          && current.outValue() != null && current.outValue().isUsed()
          && current.outValue().numberOfPhiUsers() == 0) {
        CheckCast checkCast = current.asCheckCast();
        if (checkCast.outValue().uniqueUsers().stream().allMatch(
            user -> user.isCheckCast()
                && user.asCheckCast().getType().isSubtypeOf(checkCast.getType(), appInfo))) {
          checkCast.outValue().replaceUsers(checkCast.inValues().get(0));
          it.removeOrReplaceByDebugLocalRead();
        }
      }
    }
  }

  private boolean canBeFolded(Instruction instruction) {
    return (instruction.isBinop() && instruction.asBinop().canBeFolded()) ||
        (instruction.isUnop() && instruction.asUnop().canBeFolded());
  }

  public void foldConstants(IRCode code) {
    Queue<BasicBlock> worklist = new LinkedList<>();
    worklist.addAll(code.blocks);
    for (BasicBlock block = worklist.poll(); block != null; block = worklist.poll()) {
      InstructionIterator iterator = block.iterator();
      while (iterator.hasNext()) {
        Instruction current = iterator.next();
        Instruction folded;
        if (canBeFolded(current)) {
          folded = current.fold(code);
          iterator.replaceCurrentInstruction(folded);
          folded.outValue().uniqueUsers()
              .forEach(instruction -> worklist.add(instruction.getBlock()));
        }
      }
    }
    assert code.isConsistentSSA();
  }

  // Split constants that flow into ranged invokes. This gives the register allocator more
  // freedom in assigning register to ranged invokes which can greatly reduce the number
  // of register needed (and thereby code size as well).
  public void splitRangeInvokeConstants(IRCode code) {
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        Instruction current = it.next();
        if (current.isInvoke() && current.asInvoke().requiredArgumentRegisters() > 5) {
          Invoke invoke = current.asInvoke();
          it.previous();
          Map<ConstNumber, ConstNumber> oldToNew = new IdentityHashMap<>();
          for (int i = 0; i < invoke.inValues().size(); i++) {
            Value value = invoke.inValues().get(i);
            if (value.isConstNumber() && value.numberOfUsers() > 1) {
              ConstNumber definition = value.getConstInstruction().asConstNumber();
              Value originalValue = definition.outValue();
              ConstNumber newNumber = oldToNew.get(definition);
              if (newNumber == null) {
                newNumber = ConstNumber.copyOf(code, definition);
                it.add(newNumber);
                newNumber.setPosition(current.getPosition());
                oldToNew.put(definition, newNumber);
              }
              invoke.inValues().set(i, newNumber.outValue());
              originalValue.removeUser(invoke);
              newNumber.outValue().addUser(invoke);
            }
          }
          it.next();
        }
      }
    }
  }

  public void shortenLiveRanges(IRCode code) {
    // Currently, we are only shortening the live range of constants in the entry block.
    // TODO(ager): Generalize this to shorten live ranges for more instructions? Currently
    // doing so seems to make things worse.
    Map<BasicBlock, List<Instruction>> addConstantInBlock = new HashMap<>();
    DominatorTree dominatorTree = new DominatorTree(code);
    BasicBlock block = code.blocks.get(0);
    InstructionListIterator it = block.listIterator();
    List<Instruction> toInsertInThisBlock = new ArrayList<>();
    while (it.hasNext()) {
      Instruction instruction = it.next();
      if (instruction.isConstNumber() &&
          instruction.outValue().numberOfAllUsers() != 0 &&
          !instruction.outValue().hasLocalInfo()) {
        // Collect the blocks for all users of the constant.
        List<BasicBlock> userBlocks = new LinkedList<>();
        for (Instruction user : instruction.outValue().uniqueUsers()) {
          userBlocks.add(user.getBlock());
        }
        for (Phi phi : instruction.outValue().uniquePhiUsers()) {
          userBlocks.add(phi.getBlock());
        }
        // Locate the closest dominator block for all user blocks.
        BasicBlock dominator = dominatorTree.closestDominator(userBlocks);
        // If the closest dominator block is a block that uses the constant for a phi the constant
        // needs to go in the immediate dominator block so that it is available for phi moves.
        for (Phi phi : instruction.outValue().uniquePhiUsers()) {
          if (phi.getBlock() == dominator) {
            if (instruction.outValue().numberOfAllUsers() == 1 &&
                  phi.usesValueOneTime(instruction.outValue())) {
              // Out value is used only one time, move the constant directly to the corresponding
              // branch rather than into the dominator to avoid to generate a const on paths which
              // does not required it.
              int predIndex = phi.getOperands().indexOf(instruction.outValue());
              dominator = dominator.getPredecessors().get(predIndex);
            } else {
              dominator = dominatorTree.immediateDominator(dominator);
            }
            break;
          }
        }
        // Move the const instruction as close to its uses as possible.
        it.detach();
        if (dominator != block) {
          // Post-pone constant insertion in order to use a global heuristics.
          List<Instruction> csts = addConstantInBlock.get(dominator);
          if (csts == null) {
            csts = new ArrayList<>();
            addConstantInBlock.put(dominator, csts);
          }
          csts.add(instruction);
        } else {
          toInsertInThisBlock.add(instruction);
        }
      }
    }

    // Heuristic to decide if constant instructions are shared in dominator block of usages or move
    // to the usages.
    for (Map.Entry<BasicBlock, List<Instruction>> entry : addConstantInBlock.entrySet()) {
      if (entry.getValue().size() > STOP_SHARED_CONSTANT_THRESHOLD) {
        // Too much constants in the same block, do not longer shared them except if they are used
        // by phi instructions.
        for (Instruction instruction : entry.getValue()) {
          if (instruction.outValue().numberOfPhiUsers() != 0) {
            // Add constant into the dominator block of usages.
            insertConstantInBlock(instruction, entry.getKey());
          } else {
            ConstNumber constNumber = instruction.asConstNumber();
            Value constantValue = instruction.outValue();
            assert constantValue.numberOfUsers() != 0;
            assert constantValue.numberOfUsers() == constantValue.numberOfAllUsers();
            for (Instruction user : constantValue.uniqueUsers()) {
              ConstNumber newCstNum = ConstNumber.copyOf(code, constNumber);
              newCstNum.setPosition(user.getPosition());
              InstructionListIterator iterator = user.getBlock().listIterator(user);
              iterator.previous();
              iterator.add(newCstNum);
              user.replaceValue(constantValue, newCstNum.outValue());
            }
            constantValue.clearUsers();
          }
        }
      } else {
        // Add constant into the dominator block of usages.
        for (Instruction inst : entry.getValue()) {
          insertConstantInBlock(inst, entry.getKey());
        }
      }
    }

    for (Instruction toInsert : toInsertInThisBlock) {
      insertConstantInBlock(toInsert, block);
    }
    assert code.isConsistentSSA();
  }

  private void insertConstantInBlock(Instruction instruction, BasicBlock block) {
    boolean hasCatchHandlers = block.hasCatchHandlers();
    InstructionListIterator insertAt = block.listIterator();
    // Place the instruction as late in the block as we can. It needs to go before users
    // and if we have catch handlers it needs to be placed before the throwing instruction.
    insertAt.nextUntil(i ->
        i.inValues().contains(instruction.outValue())
        || i.isJumpInstruction()
        || (hasCatchHandlers && i.instructionTypeCanThrow()));
    Instruction next = insertAt.previous();
    instruction.forceSetPosition(
        next.isGoto() ? next.asGoto().getTarget().getPosition() : next.getPosition());
    insertAt.add(instruction);
  }

  private short[] computeArrayFilledData(ConstInstruction[] values, int size, int elementSize) {
    if (values == null) {
      return null;
    }
    if (elementSize == 1) {
      short[] result = new short[(size + 1) / 2];
      for (int i = 0; i < size; i += 2) {
        short value = (short) (values[i].asConstNumber().getIntValue() & 0xFF);
        if (i + 1 < size) {
          value |= (short) ((values[i + 1].asConstNumber().getIntValue() & 0xFF) << 8);
        }
        result[i / 2] = value;
      }
      return result;
    }
    assert elementSize == 2 || elementSize == 4 || elementSize == 8;
    int shortsPerConstant = elementSize / 2;
    short[] result = new short[size * shortsPerConstant];
    for (int i = 0; i < size; i++) {
      long value = values[i].asConstNumber().getRawValue();
      for (int part = 0; part < shortsPerConstant; part++) {
        result[i * shortsPerConstant + part] = (short) ((value >> (16 * part)) & 0xFFFFL);
      }
    }
    return result;
  }

  private ConstInstruction[] computeConstantArrayValues(
      NewArrayEmpty newArray, BasicBlock block, int size) {
    if (size > MAX_FILL_ARRAY_SIZE) {
      return null;
    }
    ConstInstruction[] values = new ConstInstruction[size];
    int remaining = size;
    Set<Instruction> users = newArray.outValue().uniqueUsers();
    // We allow the array instantiations to cross block boundaries as long as it hasn't encountered
    // an instruction instance that can throw an exception.
    InstructionListIterator it = block.listIterator();
    it.nextUntil(i -> i == newArray);
    do {
      while (it.hasNext()) {
        Instruction instruction = it.next();
        // If we encounter an instruction that can throw an exception we need to bail out of the
        // optimization so that we do not transform half-initialized arrays into fully initialized
        // arrays on exceptional edges. If the block has no handlers it is not observable so
        // we perform the rewriting.
        if (block.hasCatchHandlers() && instruction.instructionInstanceCanThrow()) {
          return null;
        }
        if (!users.contains(instruction)) {
          continue;
        }
        // If the initialization sequence is broken by another use we cannot use a
        // fill-array-data instruction.
        if (!instruction.isArrayPut()) {
          return null;
        }
        ArrayPut arrayPut = instruction.asArrayPut();
        if (!(arrayPut.source().isConstant() && arrayPut.index().isConstNumber())) {
          return null;
        }
        int index = arrayPut.index().getConstInstruction().asConstNumber().getIntValue();
        assert index >= 0 && index < values.length;
        if (values[index] != null) {
          return null;
        }
        ConstInstruction value = arrayPut.source().getConstInstruction();
        values[index] = value;
        --remaining;
        if (remaining == 0) {
          return values;
        }
      }
      block = block.exit().isGoto() ? block.exit().asGoto().getTarget() : null;
      it = block != null ? block.listIterator() : null;
    } while (it != null);
    return null;
  }

  private boolean allowNewFilledArrayConstruction(Instruction instruction) {
    if (!(instruction instanceof NewArrayEmpty)) {
      return false;
    }
    NewArrayEmpty newArray = instruction.asNewArrayEmpty();
    if (!newArray.size().isConstant()) {
      return false;
    }
    assert newArray.size().isConstNumber();
    int size = newArray.size().getConstInstruction().asConstNumber().getIntValue();
    if (size < 1) {
      return false;
    }
    if (newArray.type.isPrimitiveArrayType()) {
      return true;
    }
    return newArray.type == dexItemFactory.stringArrayType
        && options.canUseFilledNewArrayOfObjects();
  }

  /**
   * Replace new-array followed by stores of constants to all entries with new-array
   * and fill-array-data / filled-new-array.
   */
  public void simplifyArrayConstruction(IRCode code) {
    for (BasicBlock block : code.blocks) {
      // Map from the array value to the number of array put instruction to remove for that value.
      Map<Value, Instruction> instructionToInsertForArray = new HashMap<>();
      Map<Value, Integer> storesToRemoveForArray = new HashMap<>();
      // First pass: identify candidates and insert fill array data instruction.
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        Instruction instruction = it.next();
        if (instruction.getLocalInfo() != null
            || !allowNewFilledArrayConstruction(instruction)) {
          continue;
        }
        NewArrayEmpty newArray = instruction.asNewArrayEmpty();
        int size = newArray.size().getConstInstruction().asConstNumber().getIntValue();
        ConstInstruction[] values = computeConstantArrayValues(newArray, block, size);
        if (values == null) {
          continue;
        }
        if (newArray.type == dexItemFactory.stringArrayType) {
          // Don't replace with filled-new-array if it requires more than 200 consecutive registers.
          if (size > 200) {
            continue;
          }
          List<Value> stringValues = new ArrayList<>(size);
          for (ConstInstruction value : values) {
            stringValues.add(value.outValue());
          }
          InvokeNewArray invoke = new InvokeNewArray(
              dexItemFactory.stringArrayType, newArray.outValue(), stringValues);
          invoke.setPosition(newArray.getPosition());
          it.detach();
          for (Value value : newArray.inValues()) {
            value.removeUser(newArray);
          }
          instructionToInsertForArray.put(newArray.outValue(), invoke);
        } else {
          // If there is only one element it is typically smaller to generate the array put
          // instruction instead of fill array data.
          if (size == 1) {
            continue;
          }
          int elementSize = newArray.type.elementSizeForPrimitiveArrayType();
          short[] contents = computeArrayFilledData(values, size, elementSize);
          if (contents == null) {
            continue;
          }
          int arraySize = newArray.size().getConstInstruction().asConstNumber().getIntValue();
          NewArrayFilledData fillArray = new NewArrayFilledData(
              newArray.outValue(), elementSize, arraySize, contents);
          fillArray.setPosition(newArray.getPosition());
          it.add(fillArray);
        }
        storesToRemoveForArray.put(newArray.outValue(), size);
      }
      // Second pass: remove all the array put instructions for the array for which we have
      // inserted a fill array data instruction instead.
      if (!storesToRemoveForArray.isEmpty()) {
        do {
          it = block.listIterator();
          while (it.hasNext()) {
            Instruction instruction = it.next();
            if (instruction.isArrayPut()) {
              Value array = instruction.asArrayPut().array();
              Integer toRemoveCount = storesToRemoveForArray.get(array);
              if (toRemoveCount != null) {
                if (toRemoveCount > 0) {
                  storesToRemoveForArray.put(array, --toRemoveCount);
                  it.remove();
                }
                if (toRemoveCount == 0) {
                  storesToRemoveForArray.put(array, --toRemoveCount);
                  Instruction construction = instructionToInsertForArray.get(array);
                  if (construction != null) {
                    it.add(construction);
                  }
                }
              }
            }
          }
          block = block.exit().isGoto() ? block.exit().asGoto().getTarget() : null;
        } while (block != null);
      }
    }
  }

  // TODO(mikaelpeltier) Manage that from and to instruction do not belong to the same block.
  private static boolean hasLineChangeBetween(Instruction from, Instruction to) {
    if (from.getBlock() != to.getBlock()) {
      return true;
    }
    if (from.getPosition().isSome()
        && to.getPosition().isSome()
        && !from.getPosition().equals(to.getPosition())) {
      return true;
    }
    InstructionListIterator iterator = from.getBlock().listIterator(from);
    Position position = null;
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      if (position == null) {
        if (instruction.getPosition().isSome()) {
          position = instruction.getPosition();
        }
      } else if (instruction.getPosition().isSome()
          && !position.equals(instruction.getPosition())) {
        return true;
      }
      if (instruction == to) {
        return false;
      }
    }
    throw new Unreachable();
  }

  public void simplifyDebugLocals(IRCode code) {
    for (BasicBlock block : code.blocks) {
      for (Phi phi : block.getPhis()) {
        if (!phi.hasLocalInfo() && phi.numberOfUsers() == 1 && phi.numberOfAllUsers() == 1) {
          Instruction instruction = phi.uniqueUsers().iterator().next();
          if (instruction.isDebugLocalWrite()) {
            removeDebugWriteOfPhi(phi, instruction.asDebugLocalWrite());
          }
        }
      }

      InstructionIterator iterator = block.iterator();
      while (iterator.hasNext()) {
        Instruction instruction = iterator.next();
        if (instruction.isDebugLocalWrite()) {
          assert instruction.inValues().size() == 1;
          Value inValue = instruction.inValues().get(0);
          if (!inValue.hasLocalInfo() &&
              inValue.numberOfAllUsers() == 1 &&
              inValue.definition != null &&
              !hasLineChangeBetween(inValue.definition, instruction)) {
            inValue.setLocalInfo(instruction.outValue().getLocalInfo());
            instruction.moveDebugValues(inValue.definition);
            instruction.outValue().replaceUsers(inValue);
            instruction.clearDebugValues();
            iterator.remove();
          }
        }
      }
    }
  }

  private void removeDebugWriteOfPhi(Phi phi, DebugLocalWrite write) {
    assert write.src() == phi;
    for (InstructionListIterator iterator = phi.getBlock().listIterator(); iterator.hasNext(); ) {
      Instruction next = iterator.next();
      if (!next.isDebugLocalWrite()) {
        // If the debug write is not in the block header bail out.
        return;
      }
      if (next == write) {
        // Associate the phi with the local.
        phi.setLocalInfo(write.getLocalInfo());
        // Replace uses of the write with the phi.
        write.outValue().replaceUsers(phi);
        // Safely remove the write.
        // TODO(zerny): Once phis become instructions, move debug values there instead of a nop.
        iterator.removeOrReplaceByDebugLocalRead();
        return;
      }
    }
  }

  private static class CSEExpressionEquivalence extends Equivalence<Instruction> {

    @Override
    protected boolean doEquivalent(Instruction a, Instruction b) {
      // Note that we don't consider positions because CSE can at most remove an instruction.
      if (a.getClass() != b.getClass() || !a.identicalNonValueNonPositionParts(b)) {
        return false;
      }
      // For commutative binary operations any order of in-values are equal.
      if (a.isBinop() && a.asBinop().isCommutative()) {
        Value a0 = a.inValues().get(0);
        Value a1 = a.inValues().get(1);
        Value b0 = b.inValues().get(0);
        Value b1 = b.inValues().get(1);
        return (a0.equals(b0) && a1.equals(b1)) || (a0.equals(b1) && a1.equals(b0));
      } else {
        // Compare all in-values.
        assert a.inValues().size() == b.inValues().size();
        for (int i = 0; i < a.inValues().size(); i++) {
          if (!a.inValues().get(i).equals(b.inValues().get(i))) {
            return false;
          }
        }
        return true;
      }
    }

    @Override
    protected int doHash(Instruction instruction) {
      final int prime = 29;
      int hash = instruction.getClass().hashCode();
      if (instruction.isBinop()) {
        Binop binop = instruction.asBinop();
        Value in0 = instruction.inValues().get(0);
        Value in1 = instruction.inValues().get(1);
        if (binop.isCommutative()) {
          hash += hash * prime + in0.hashCode() * in1.hashCode();
        } else {
          hash += hash * prime + in0.hashCode();
          hash += hash * prime + in1.hashCode();
        }
        return hash;
      } else {
        for (Value value : instruction.inValues()) {
          hash += hash * prime + value.hashCode();
        }
      }
      return hash;
    }
  }

  private boolean shareCatchHandlers(Instruction i0, Instruction i1) {
    if (!i0.instructionTypeCanThrow()) {
      assert !i1.instructionTypeCanThrow();
      return true;
    }
    assert i1.instructionTypeCanThrow();
    // TODO(sgjesse): This could be even better by checking for the exceptions thrown, e.g. div
    // and rem only ever throw ArithmeticException.
    CatchHandlers<BasicBlock> ch0 = i0.getBlock().getCatchHandlers();
    CatchHandlers<BasicBlock> ch1 = i1.getBlock().getCatchHandlers();
    return ch0.equals(ch1);
  }

  public void commonSubexpressionElimination(IRCode code) {
    final ListMultimap<Wrapper<Instruction>, Value> instructionToValue = ArrayListMultimap.create();
    final DominatorTree dominatorTree = new DominatorTree(code);
    final CSEExpressionEquivalence equivalence = new CSEExpressionEquivalence();

    for (int i = 0; i < dominatorTree.getSortedBlocks().length; i++) {
      BasicBlock block = dominatorTree.getSortedBlocks()[i];
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        Instruction instruction = iterator.next();
        if (instruction.isBinop()
            || instruction.isUnop()
            || instruction.isInstanceOf()
            || instruction.isCheckCast()) {
          if (instruction.getLocalInfo() != null || instruction.hasInValueWithLocalInfo()) {
            // If the instruction has input or output values then it is not safe to share it.
            continue;
          }
          List<Value> candidates = instructionToValue.get(equivalence.wrap(instruction));
          boolean eliminated = false;
          if (candidates.size() > 0) {
            for (Value candidate : candidates) {
              if (dominatorTree.dominatedBy(block, candidate.definition.getBlock()) &&
                  shareCatchHandlers(instruction, candidate.definition)) {
                instruction.outValue().replaceUsers(candidate);
                eliminated = true;
                iterator.removeOrReplaceByDebugLocalRead();
                break;  // Don't try any more candidates.
              }
            }
          }
          if (!eliminated) {
            instructionToValue.put(equivalence.wrap(instruction), instruction.outValue());
          }
        }
      }
    }
    assert code.isConsistentSSA();
  }

  public void simplifyIf(IRCode code) {
    DominatorTree dominator = new DominatorTree(code);
    code.clearMarks();
    for (BasicBlock block : code.blocks) {
      if (block.isMarked()) {
        continue;
      }
      if (block.exit().isIf()) {
        // First rewrite zero comparison.
        rewriteIfWithConstZero(block);

        if (simplifyKnownBooleanCondition(code, dominator, block)) {
          continue;
        }

        // Simplify if conditions when possible.
        If theIf = block.exit().asIf();
        List<Value> inValues = theIf.inValues();

        int cond;

        if (inValues.get(0).isConstNumber()
            && (theIf.isZeroTest() || inValues.get(1).isConstNumber())) {
          // Zero test with a constant of comparison between between two constants.
          if (theIf.isZeroTest()) {
            cond = inValues.get(0).getConstInstruction().asConstNumber().getIntValue();
          } else {
            int left = inValues.get(0).getConstInstruction().asConstNumber().getIntValue();
            int right = inValues.get(1).getConstInstruction().asConstNumber().getIntValue();
            cond = left - right;
          }
        } else if (inValues.get(0).hasValueRange()
            && (theIf.isZeroTest() || inValues.get(1).hasValueRange())) {
          // Zero test with a value range, or comparison between between two values,
          // each with a value ranges.
          if (theIf.isZeroTest()) {
            if (inValues.get(0).isValueInRange(0)) {
              // Zero in in the range - can't determine the comparison.
              continue;
            }
            cond = Long.signum(inValues.get(0).getValueRange().getMin());
          } else {
            LongInterval leftRange = inValues.get(0).getValueRange();
            LongInterval rightRange = inValues.get(1).getValueRange();
            if (leftRange.overlapsWith(rightRange)) {
              // Ranges overlap - can't determine the comparison.
              continue;
            }
            // There is no overlap.
            cond = Long.signum(leftRange.getMin() - rightRange.getMin());
          }
        } else {
          continue;
        }
        BasicBlock target = theIf.targetFromCondition(cond);
        BasicBlock deadTarget =
            target == theIf.getTrueTarget() ? theIf.fallthroughBlock() : theIf.getTrueTarget();
        rewriteIfToGoto(dominator, block, theIf, target, deadTarget);
      }
    }
    code.removeMarkedBlocks();
    assert code.isConsistentSSA();
  }


  /* Identify simple diamond shapes converting boolean true/false to 1/0. We consider the forms:
   *
   *   ifeqz booleanValue       ifnez booleanValue
   *      /        \              /        \
   *      \        /              \        /
   *      phi(0, 1)                phi(1, 0)
   *
   * which can be replaced by a fallthrough and the phi value can be replaced
   * with the boolean value itself.
   *
   * We also consider the forms:
   *
   *    ifeqz booleanValue       ifnez booleanValue
   *      /        \              /        \
   *      \        /              \        /
   *      phi(1, 0)                phi(0, 1)
   *
   *  which can be replaced by a fallthrough and the phi value can be replaced
   * by an xor instruction which is smaller.
   */
  private boolean simplifyKnownBooleanCondition(IRCode code, DominatorTree dominator,
      BasicBlock block) {
    If theIf = block.exit().asIf();
    Value testValue = theIf.inValues().get(0);
    if (theIf.isZeroTest() && testValue.knownToBeBoolean()) {
      BasicBlock trueBlock = theIf.getTrueTarget();
      BasicBlock falseBlock = theIf.fallthroughBlock();
      if (trueBlock.isTrivialGoto() &&
          falseBlock.isTrivialGoto() &&
          trueBlock.getSuccessors().get(0) == falseBlock.getSuccessors().get(0)) {
        BasicBlock targetBlock = trueBlock.getSuccessors().get(0);
        if (targetBlock.getPredecessors().size() == 2) {
          int trueIndex = targetBlock.getPredecessors().indexOf(trueBlock);
          int falseIndex = trueIndex == 0 ? 1 : 0;
          int deadPhis = 0;
          // Locate the phis that have the same value as the boolean and replace them
          // by the boolean in all users.
          for (Phi phi : targetBlock.getPhis()) {
            Value trueValue = phi.getOperand(trueIndex);
            Value falseValue = phi.getOperand(falseIndex);
            if (trueValue.isConstNumber() && falseValue.isConstNumber()) {
              ConstNumber trueNumber = trueValue.getConstInstruction().asConstNumber();
              ConstNumber falseNumber = falseValue.getConstInstruction().asConstNumber();
              if ((theIf.getType() == Type.EQ &&
                  trueNumber.isIntegerZero() &&
                  falseNumber.isIntegerOne()) ||
                  (theIf.getType() == Type.NE &&
                      trueNumber.isIntegerOne() &&
                      falseNumber.isIntegerZero())) {
                phi.replaceUsers(testValue);
                deadPhis++;
              } else if ((theIf.getType() == Type.NE &&
                           trueNumber.isIntegerZero() &&
                           falseNumber.isIntegerOne()) ||
                         (theIf.getType() == Type.EQ &&
                           trueNumber.isIntegerOne() &&
                           falseNumber.isIntegerZero())) {
                Value newOutValue = code.createValue(phi.outType(), phi.getLocalInfo());
                phi.replaceUsers(newOutValue);
                Instruction newInstruction = new Xor(NumericType.INT, newOutValue, testValue,
                    trueNumber.isIntegerOne() ? trueValue : falseValue);
                newInstruction.setBlock(phi.getBlock());
                // The xor is replacing a phi so it does not have an actual position.
                newInstruction.setPosition(phi.getBlock().getPosition());
                phi.getBlock().getInstructions().add(0, newInstruction);
                deadPhis++;
              }
            }
          }
          // If all phis were removed, there is no need for the diamond shape anymore
          // and it can be rewritten to a goto to one of the branches.
          if (deadPhis == targetBlock.getPhis().size()) {
            rewriteIfToGoto(dominator, block, theIf, trueBlock, falseBlock);
            return true;
          }
        }
      }
    }
    return false;
  }

  private void rewriteIfToGoto(DominatorTree dominator, BasicBlock block, If theIf,
      BasicBlock target, BasicBlock deadTarget) {
    List<BasicBlock> removedBlocks = block.unlink(deadTarget, dominator);
    for (BasicBlock removedBlock : removedBlocks) {
      if (!removedBlock.isMarked()) {
        removedBlock.mark();
      }
    }
    assert theIf == block.exit();
    block.replaceLastInstruction(new Goto());
    assert block.exit().isGoto();
    assert block.exit().asGoto().getTarget() == target;
  }

  private void rewriteIfWithConstZero(BasicBlock block) {
    If theIf = block.exit().asIf();
    if (theIf.isZeroTest()) {
      return;
    }

    List<Value> inValues = theIf.inValues();
    Value leftValue = inValues.get(0);
    Value rightValue = inValues.get(1);
    if (leftValue.isConstNumber() || rightValue.isConstNumber()) {
      if (leftValue.isConstNumber()) {
        int left = leftValue.getConstInstruction().asConstNumber().getIntValue();
        if (left == 0) {
          If ifz = new If(theIf.getType().forSwappedOperands(), rightValue);
          block.replaceLastInstruction(ifz);
          assert block.exit() == ifz;
        }
      } else {
        int right = rightValue.getConstInstruction().asConstNumber().getIntValue();
        if (right == 0) {
          If ifz = new If(theIf.getType(), leftValue);
          block.replaceLastInstruction(ifz);
          assert block.exit() == ifz;
        }
      }
    }
  }

  public void rewriteLongCompareAndRequireNonNull(IRCode code, InternalOptions options) {
    if (options.canUseLongCompareAndObjectsNonNull()) {
      return;
    }

    InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      Instruction current = iterator.next();
      if (current.isInvokeMethod()) {
        DexMethod invokedMethod = current.asInvokeMethod().getInvokedMethod();
        if (invokedMethod == dexItemFactory.longMethods.compare) {
          // Rewrite calls to Long.compare for sdk versions that do not have that method.
          List<Value> inValues = current.inValues();
          assert inValues.size() == 2;
          iterator.replaceCurrentInstruction(
              new Cmp(NumericType.LONG, Bias.NONE, current.outValue(), inValues.get(0),
                  inValues.get(1)));
        } else if (invokedMethod == dexItemFactory.objectsMethods.requireNonNull) {
          // Rewrite calls to Objects.requireNonNull(Object) because Javac 9 start to use it for
          // synthesized null checks.
          InvokeVirtual callToGetClass = new InvokeVirtual(dexItemFactory.objectMethods.getClass,
              null, current.inValues());
          if (current.outValue() != null) {
            current.outValue().replaceUsers(current.inValues().get(0));
            current.setOutValue(null);
          }
          iterator.replaceCurrentInstruction(callToGetClass);
        }
      }
    }
    assert code.isConsistentSSA();
  }

  // Removes calls to Throwable.addSuppressed(Throwable) and rewrites
  // Throwable.getSuppressed() into new Throwable[0].
  //
  // Note that addSuppressed() and getSuppressed() methods are final in
  // Throwable, so these changes don't have to worry about overrides.
  public void rewriteThrowableAddAndGetSuppressed(IRCode code) {
    DexItemFactory.ThrowableMethods throwableMethods = dexItemFactory.throwableMethods;

    for (BasicBlock block : code.blocks) {
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        Instruction current = iterator.next();
        if (current.isInvokeMethod()) {
          DexMethod invokedMethod = current.asInvokeMethod().getInvokedMethod();
          if (matchesMethodOfThrowable(invokedMethod, throwableMethods.addSuppressed)) {
            // Remove Throwable::addSuppressed(Throwable) call.
            iterator.removeOrReplaceByDebugLocalRead();
          } else if (matchesMethodOfThrowable(invokedMethod, throwableMethods.getSuppressed)) {
            Value destValue = current.outValue();
            if (destValue == null) {
              // If the result of the call was not used we don't create
              // an empty array and just remove the call.
              iterator.removeOrReplaceByDebugLocalRead();
              continue;
            }

            // Replace call to Throwable::getSuppressed() with new Throwable[0].

            // First insert the constant value *before* the current instruction.
            ConstNumber zero = code.createIntConstant(0);
            zero.setPosition(current.getPosition());
            assert iterator.hasPrevious();
            iterator.previous();
            iterator.add(zero);

            // Then replace the invoke instruction with new-array instruction.
            Instruction next = iterator.next();
            assert current == next;
            NewArrayEmpty newArray = new NewArrayEmpty(destValue, zero.outValue(),
                dexItemFactory.createType(dexItemFactory.throwableArrayDescriptor));
            iterator.replaceCurrentInstruction(newArray);
          }
        }
      }
    }
    assert code.isConsistentSSA();
  }

  private boolean matchesMethodOfThrowable(DexMethod invoked, DexMethod expected) {
    return invoked.name == expected.name
        && invoked.proto == expected.proto
        && isSubtypeOfThrowable(invoked.holder);
  }

  private boolean isSubtypeOfThrowable(DexType type) {
    while (type != null && type != dexItemFactory.objectType) {
      if (type == dexItemFactory.throwableType) {
        return true;
      }
      DexClass dexClass = appInfo.definitionFor(type);
      if (dexClass == null) {
        throw new CompilationError("Class or interface " + type.toSourceString() +
            " required for desugaring of try-with-resources is not found.");
      }
      type = dexClass.superType;
    }
    return false;
  }

  private Value addConstString(IRCode code, InstructionListIterator iterator, String s) {
    Value value = code.createValue(ValueType.OBJECT);
    iterator.add(new ConstString(value, dexItemFactory.createString(s)));
    return value;
  }

  /**
   * Insert code into <code>method</code> to log the argument types to System.out.
   *
   * The type is determined by calling getClass() on the argument.
   */
  public void logArgumentTypes(DexEncodedMethod method, IRCode code) {
    List<Value> arguments = code.collectArguments();
    BasicBlock block = code.blocks.getFirst();
    InstructionListIterator iterator = block.listIterator();

    // Attach some synthetic position to all inserted code.
    Position position = Position.synthetic(1);
    iterator.setInsertionPosition(position);

    // Split arguments into their own block.
    iterator.nextUntil(instruction -> !instruction.isArgument());
    iterator.previous();
    iterator.split(code);
    iterator.previous();

    // Now that the block is split there should not be any catch handlers in the block.
    assert !block.hasCatchHandlers();
    Value out = code.createValue(ValueType.OBJECT);
    DexType javaLangSystemType = dexItemFactory.createType("Ljava/lang/System;");
    DexType javaIoPrintStreamType = dexItemFactory.createType("Ljava/io/PrintStream;");

    DexProto proto = dexItemFactory.createProto(dexItemFactory.voidType, dexItemFactory.objectType);
    DexMethod print = dexItemFactory.createMethod(javaIoPrintStreamType, proto, "print");
    DexMethod printLn = dexItemFactory.createMethod(javaIoPrintStreamType, proto, "println");

    iterator.add(
        new StaticGet(MemberType.OBJECT, out,
            dexItemFactory.createField(javaLangSystemType, javaIoPrintStreamType, "out")));

    Value value = code.createValue(ValueType.OBJECT);
    iterator.add(new ConstString(value, dexItemFactory.createString("INVOKE ")));
    iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, value)));

    value = code.createValue(ValueType.OBJECT);
    iterator.add(
        new ConstString(value, dexItemFactory.createString(method.method.qualifiedName())));
    iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, value)));

    Value openParenthesis = addConstString(code, iterator, "(");
    Value comma = addConstString(code, iterator, ",");
    Value closeParenthesis = addConstString(code, iterator, ")");
    Value indent = addConstString(code, iterator, "  ");
    Value nul = addConstString(code, iterator, "(null)");
    Value primitive = addConstString(code, iterator, "(primitive)");
    Value empty = addConstString(code, iterator, "");

    iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, openParenthesis)));
    for (int i = 0; i < arguments.size(); i++) {
      iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, indent)));

      // Add a block for end-of-line printing.
      BasicBlock eol = BasicBlock.createGotoBlock(code.blocks.size());
      code.blocks.add(eol);

      BasicBlock successor = block.unlinkSingleSuccessor();
      block.link(eol);
      eol.link(successor);

      Value argument = arguments.get(i);
      if (argument.outType() != ValueType.OBJECT) {
        iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, primitive)));
      } else {
        // Insert "if (argument != null) ...".
        successor = block.unlinkSingleSuccessor();
        If theIf = new If(If.Type.NE, argument);
        theIf.setPosition(position);
        BasicBlock ifBlock = BasicBlock.createIfBlock(code.blocks.size(), theIf);
        code.blocks.add(ifBlock);
        // Fallthrough block must be added right after the if.
        BasicBlock isNullBlock = BasicBlock.createGotoBlock(code.blocks.size());
        code.blocks.add(isNullBlock);
        BasicBlock isNotNullBlock = BasicBlock.createGotoBlock(code.blocks.size());
        code.blocks.add(isNotNullBlock);

        // Link the added blocks together.
        block.link(ifBlock);
        ifBlock.link(isNotNullBlock);
        ifBlock.link(isNullBlock);
        isNotNullBlock.link(successor);
        isNullBlock.link(successor);

        // Fill code into the blocks.
        iterator = isNullBlock.listIterator();
        iterator.setInsertionPosition(position);
        iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, nul)));
        iterator = isNotNullBlock.listIterator();
        iterator.setInsertionPosition(position);
        value = code.createValue(ValueType.OBJECT);
        iterator.add(new InvokeVirtual(dexItemFactory.objectMethods.getClass, value,
            ImmutableList.of(arguments.get(i))));
        iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, value)));
      }

      iterator = eol.listIterator();
      iterator.setInsertionPosition(position);
      if (i == arguments.size() - 1) {
        iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, closeParenthesis)));
      } else {
        iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, comma)));
      }
      block = eol;
    }
    // When we fall out of the loop the iterator is in the last eol block.
    iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, empty)));
  }
}
