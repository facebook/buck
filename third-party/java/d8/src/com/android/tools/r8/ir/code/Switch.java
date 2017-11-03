// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.code.Nop;
import com.android.tools.r8.code.PackedSwitch;
import com.android.tools.r8.code.PackedSwitchPayload;
import com.android.tools.r8.code.SparseSwitch;
import com.android.tools.r8.code.SparseSwitchPayload;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.utils.CfgPrinter;
import com.google.common.primitives.Ints;
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import java.util.List;

public class Switch extends JumpInstruction {

  private final int[] keys;
  private final int[] targetBlockIndices;
  private int fallthroughBlockIndex;

  public Switch(
      Value value,
      int[] keys,
      int[] targetBlockIndices,
      int fallthroughBlockIndex) {
    super(null, value);
    this.keys = keys;
    this.targetBlockIndices = targetBlockIndices;
    this.fallthroughBlockIndex = fallthroughBlockIndex;
    assert valid();
  }

  private boolean valid() {
    assert keys.length <= Constants.U16BIT_MAX;
    // Keys must be acceding, and cannot target the fallthrough.
    assert keys.length == targetBlockIndices.length;
    for (int i = 1; i < keys.length - 1; i++) {
      assert keys[i - 1] < keys[i];
      assert targetBlockIndices[i] != fallthroughBlockIndex;
    }
    assert targetBlockIndices[keys.length - 1] != fallthroughBlockIndex;
    return true;
  }

  public Value value() {
    return inValues.get(0);
  }

  // Number of targets if this switch is emitted as a packed switch.
  private static long numberOfTargetsIfPacked(int keys[]) {
    return ((long) keys[keys.length - 1]) - ((long) keys[0]) + 1;
  }

  public static boolean canBePacked(int keys[]) {
    // The size of a switch payload is stored in an ushort in the Dex file.
    return numberOfTargetsIfPacked(keys) <= Constants.U16BIT_MAX;
  }

  // Number of targets if this switch is emitted as a packed switch.
  public static int numberOfTargetsForPacked(int keys[]) {
    assert canBePacked(keys);
    return (int) numberOfTargetsIfPacked(keys);
  }

  // Size of the switch payload if emitted as packed (in code units).
  // This size can not exceed Constants.U16BIT_MAX * 2 + 4 and can be contained in an integer.
  private static int packedPayloadSize(int keys[]) {
    return (numberOfTargetsForPacked(keys) * 2) + 4;
  }

  // Size of the switch payload if emitted as sparse (in code units).
  // This size can not exceed Constants.U16BIT_MAX * 4 + 2 and can be contained in an integer.
  private static int sparsePayloadSize(int keys[]) {
    return (keys.length * 4) + 2;
  }

  /**
   * Size of the switch payload instruction for the given keys. This will be the payload
   * size for the smallest encoding of the provided keys.
   *
   * @param keys the switch keys
   * @return Size of the switch payload instruction in code units
   */
  public static int payloadSize(List<Integer> keys) {
    return payloadSize(Ints.toArray(keys));
  }

  /**
   * Size of the switch payload instruction for the given keys.
   *
   * @see #payloadSize(List)
   */
  public static int payloadSize(int keys[]) {
    int sparse = sparsePayloadSize(keys);
    if (canBePacked(keys)) {
      return Math.min(sparse, packedPayloadSize(keys));
    } else {
      return sparse;
    }
  }

  private boolean canBePacked() {
    return canBePacked(keys);
  }

  // Number of targets if this switch is emitted as a packed switch.
  private int numberOfTargetsForPacked() {
    return numberOfTargetsForPacked(keys);
  }

  // Size of the switch payload if emitted as packed (in code units).
  private int packedPayloadSize() {
    return packedPayloadSize(keys);
  }

  // Size of the switch payload if emitted as sparse (in code units).
  private int sparsePayloadSize() {
    return sparsePayloadSize(keys);
  }

  private boolean emitPacked() {
    return canBePacked() && packedPayloadSize() <= sparsePayloadSize();
  }

  public int getFirstKey() {
    return keys[0];
  }

  @Override
  public boolean isSwitch() {
    return true;
  }

  @Override
  public Switch asSwitch() {
    return this;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    assert other.isSwitch();
    return false;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other.isSwitch();
    return 0;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int value = builder.allocatedRegister(value(), getNumber());
    if (emitPacked()) {
      builder.addSwitch(this, new PackedSwitch(value));
    } else {
      builder.addSwitch(this, new SparseSwitch(value));
    }
  }

  // Estimated size of the resulting dex instruction in code units (excluding the payload).
  public static int estimatedDexSize() {
    return 3;
  }

  public int numberOfKeys() {
    return keys.length;
  }

  public int getKey(int index) {
    return keys[index];
  }

  public int[] getKeys() {
    return keys;
  }

  public int[] targetBlockIndices() {
    return targetBlockIndices;
  }

  public Int2ReferenceSortedMap<BasicBlock> getKeyToTargetMap() {
    Int2ReferenceSortedMap<BasicBlock> result = new Int2ReferenceAVLTreeMap<>();
    for (int i = 0; i < keys.length; i++) {
      result.put(getKey(i), targetBlock(i));
    }
    return result;
  }

  @Override
  public BasicBlock fallthroughBlock() {
    return getBlock().getSuccessors().get(fallthroughBlockIndex);
  }

  public int getFallthroughBlockIndex() {
    return fallthroughBlockIndex;
  }

  public void setFallthroughBlockIndex(int i) {
    fallthroughBlockIndex = i;
  }

  public BasicBlock targetBlock(int index) {
    return getBlock().getSuccessors().get(targetBlockIndices()[index]);
  }

  @Override
  public void setFallthroughBlock(BasicBlock block) {
    getBlock().getSuccessors().set(fallthroughBlockIndex, block);
  }

  public Nop buildPayload(int[] targets, int fallthroughTarget) {
    assert keys.length == targets.length;
    if (emitPacked()) {
      int targetsCount = numberOfTargetsForPacked();
      if (targets.length == targetsCount) {
        // All targets are already present.
        return new PackedSwitchPayload(getFirstKey(), targets);
      } else {
        // Generate the list of targets for all key values. Set the target for keys not present
        // to the fallthrough.
        int[] packedTargets = new int[targetsCount];
        int originalIndex = 0;
        for (int i = 0; i < targetsCount; i++) {
          int key = getFirstKey() + i;
          if (keys[originalIndex] == key) {
            packedTargets[i] = targets[originalIndex];
            originalIndex++;
          } else {
            packedTargets[i] = fallthroughTarget;
          }
        }
        assert originalIndex == keys.length;
        return new PackedSwitchPayload(getFirstKey(), packedTargets);
      }
    } else {
      assert numberOfKeys() == keys.length;
      return new SparseSwitchPayload(keys, targets);
    }
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder(super.toString()+ "\n");
    for (int i = 0; i < numberOfKeys(); i++) {
      builder.append("          ");
      builder.append(getKey(i));
      builder.append(" -> ");
      builder.append(targetBlock(i).getNumber());
      builder.append("\n");
    }
    builder.append("          F -> ");
    builder.append(fallthroughBlock().getNumber());
    return builder.toString();
  }

  @Override
  public void print(CfgPrinter printer) {
    super.print(printer);
    for (int index : targetBlockIndices) {
      BasicBlock target = getBlock().getSuccessors().get(index);
      printer.append(" B").append(target.getNumber());
    }
  }
}
