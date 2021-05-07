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

package com.facebook.buck.core.rules.pipeline;

import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;

/**
 * Holds rule pipelining state.
 *
 * <p>State could be presented as an actual implementation of {@link RulePipelineState} interface in
 * case pipeline rule is running in the current process, or holds could be presented as {@link
 * CompilationDaemonStep} which interacts with compilation daemon process.
 *
 * <p>The rules in the pipeline share state holder through that object.
 */
public class StateHolder<State extends RulePipelineState> implements AutoCloseable {

  private final Either<State, CompilationDaemonStep> either;
  private boolean isFirstStage = false;

  private StateHolder(State state) {
    this.either = Either.ofLeft(state);
  }

  private StateHolder(CompilationDaemonStep compilationDaemonStep) {
    this.either = Either.ofRight(compilationDaemonStep);
  }

  public static <State extends RulePipelineState> StateHolder<State> fromState(State state) {
    return new StateHolder<>(state);
  }

  public static <State extends RulePipelineState> StateHolder<State> fromCompilationStep(
      CompilationDaemonStep compilationDaemonStep) {
    return new StateHolder<>(compilationDaemonStep);
  }

  public boolean supportsCompilationDaemon() {
    return either.isRight();
  }

  /** Returns build rule's pipelining state. */
  public State getState() {
    Preconditions.checkState(either.isLeft(), "State could not be created in the current process");
    return either.getLeft();
  }

  /** Returns compilation daemon step. */
  public CompilationDaemonStep getCompilationDaemonStep() {
    Preconditions.checkState(either.isRight());
    return either.getRight();
  }

  @Override
  public void close() {
    if (supportsCompilationDaemon()) {
      getCompilationDaemonStep().close();
    } else {
      getState().close();
    }
  }

  public boolean isFirstStage() {
    return isFirstStage;
  }

  public void setFirstStage(boolean isFirstStage) {
    this.isFirstStage = isFirstStage;
  }
}
