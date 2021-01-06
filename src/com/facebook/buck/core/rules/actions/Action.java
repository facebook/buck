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

package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSortedSet;

/**
 * An {@link Action} that forms the Action graph.
 *
 * <p>This is the actual operations necessary in the build to form the final {@link Artifact}.
 */
public interface Action extends BuildEngineAction {

  /** @return the build target of the rule analysis that created of this action */
  BuildTarget getOwner();

  /** @return the set of inputs required to complete this action */
  ImmutableSortedSet<Artifact> getInputs();

  /** @return the set of outputs this action generates */
  ImmutableSortedSet<OutputArtifact> getOutputs();

  @Override
  ImmutableSortedSet<SourcePath> getSourcePathOutputs();

  /**
   * @return a name for this action to be printed to console when executing and for logging purposes
   */
  String getShortName();

  /** @return the short name of this action as an ID */
  String getID();

  /**
   * Executes this action as part of the build
   *
   * @param executionContext a set of information the action can use for execution
   * @return {@link ActionExecutionResult} indicating the status of execution
   */
  ActionExecutionResult execute(ActionExecutionContext executionContext);

  /**
   * @return true if the output of this build rule is compatible with {@code buck build --out}. To
   *     be compatible, that means (1) {@link #getOutputs()} ()} cannot be empty, and (2) the output
   *     file works as intended when copied to an arbitrary path (i.e., does not have any
   *     dependencies on relative symlinks).
   */
  default boolean outputFileCanBeCopied() {
    return !getOutputs().isEmpty();
  }

  /**
   * TODO(bobyf): should we still have this or should we enforce everything to be cacheable
   *
   * @return whether the output {@link Artifact}s should be cached
   */
  @Override
  boolean isCacheable();

  /**
   * @return true if this rule should only be allowed to be executed via Remote Execution if it
   *     satisfies input size limits.
   */
  @Override
  default boolean shouldRespectInputSizeLimitForRemoteExecution() {
    return true;
  }
}
