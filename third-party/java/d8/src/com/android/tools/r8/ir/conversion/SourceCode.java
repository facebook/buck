// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.ir.code.CatchHandlers;
import com.android.tools.r8.ir.code.Position;

/**
 * Abstraction of the input/source code for the IRBuilder.
 *
 * Implementations of the abstraction need to compute/provide the block-structure of the source and
 * delegate building of the actual instruction stream.
 */
public interface SourceCode {

  // Accessors.
  int instructionCount();
  int instructionIndex(int instructionOffset);
  int instructionOffset(int instructionIndex);

  DebugLocalInfo getCurrentLocal(int register);

  Position getCurrentPosition();

  Position getDebugPositionAtOffset(int offset);

  /**
   * Trace block structure of the source-program.
   *
   * <p>The instruction at {@code index} is traced and its target blocks are marked by using
   * {@code IRBuilder.ensureSuccessorBlock} (and {@code ensureBlockWithoutEnqueuing}).
   *
   * @return If the instruction closes the block, the last index of the block,
   * otherwise -1.
   */
  int traceInstruction(int instructionIndex, IRBuilder builder);

  void closingCurrentBlockWithFallthrough(int fallthroughInstructionIndex, IRBuilder builder);

  // Setup and release resources used temporarily during trace/build.
  void setUp();
  void clear();

  // Delegates for IR building.
  void buildPrelude(IRBuilder builder);
  void buildInstruction(IRBuilder builder, int instructionIndex) throws ApiLevelException;
  void buildPostlude(IRBuilder builder);

  // Helper to resolve switch payloads and build switch instructions (dex code only).
  void resolveAndBuildSwitch(int value, int fallthroughOffset, int payloadOffset,
      IRBuilder builder);

  // Helper to resolve fill-array data and build new-array instructions (dex code only).
  void resolveAndBuildNewArrayFilledData(int arrayRef, int payloadOffset, IRBuilder builder);

  CatchHandlers<Integer> getCurrentCatchHandlers();
  int getMoveExceptionRegister();

  // For debugging/verification purpose.
  boolean verifyRegister(int register);
  boolean verifyCurrentInstructionCanThrow();
  boolean verifyLocalInScope(DebugLocalInfo local);
}
