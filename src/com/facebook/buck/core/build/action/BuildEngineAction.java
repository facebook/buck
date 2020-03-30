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

package com.facebook.buck.core.build.action;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSet;

/**
 * Interface for marking objects that the {@link com.facebook.buck.core.build.engine.BuildEngine}
 * can build. Eventually, this will become the {@link com.facebook.buck.core.rules.actions.Action}
 * interface itself. However, we keep this one around for compatibility between existing {@link
 * com.facebook.buck.core.rules.BuildRule}s and the new {@link
 * com.facebook.buck.core.rules.actions.Action}s.
 */
public interface BuildEngineAction {

  /** @return the {@link BuildTarget} of the rule corresponding to this action */
  BuildTarget getBuildTarget();

  /**
   * @return a set of dependencies required for this {@link BuildEngineAction} to build, as
   *     identified by the {@link BuildTarget}.
   */
  ImmutableSet<BuildTarget> getDependencies();

  /**
   * @return the set of outputs this {@link BuildEngineAction} builds. This is here for legacy as
   *     BuildRules deal with {@link SourcePath}
   */
  ImmutableSet<SourcePath> getSourcePathOutputs();

  /**
   * Whether this {@link BuildEngineAction} can be cached.
   *
   * <p>Uncached build rules are never written out to cache, never read from cache, and does not
   * count in cache statistics. This rule is useful for artifacts which cannot be easily normalized.
   */
  boolean isCacheable();

  /**
   * Executes this {@link BuildEngineAction}, called by the {@link
   * com.facebook.buck.core.build.engine.BuildEngine} to materialize the outputs declared in {@link
   * #getSourcePathOutputs()}
   *
   * <p>TODO(bobyf): uncomment the below and change the signature slightly once we move build engine
   * to actually use this.
   *
   * <pre>
   * void execute( ProjectFilesystem filesystem, ExecutionContext executionContext, BuildContext
   *    buildContext, BuildableContext buildableContext) throws StepFailedException,
   *    InterruptedException;
   * </pre>
   */

  /**
   * @return true if this rule should only be allowed to be executed via Remote Execution if it
   *     satisfies input size limits.
   */
  default boolean shouldRespectInputSizeLimitForRemoteExecution() {
    return true;
  }
}
