/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.core.build.engine;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * A build engine is responsible for building a given build rule, which includes all its transitive
 * dependencies.
 */
public interface BuildEngine {

  /** Calculate the total number of transitive build rules processed from the given roots. */
  int getNumRulesToBuild(Iterable<BuildRule> rule);

  /** Build the given build rule and return a future to the build rule success. */
  BuildEngineResult build(
      BuildEngineBuildContext buildContext, ExecutionContext executionContext, BuildRule rule);

  /**
   * Returns the build result of the build rule associated with the given build target. Returns
   * {@code null} if the build rule has not yet been built.
   */
  @Nullable
  BuildResult getBuildRuleResult(BuildTarget buildTarget)
      throws ExecutionException, InterruptedException;

  /**
   * Returns whether the build rule associated with the build target has been successfully built.
   */
  boolean isRuleBuilt(BuildTarget buildTarget) throws InterruptedException;

  /**
   * Marks build as failed with the given Throwable. If keepGoing == false, any pending steps will
   * be terminated before starting.
   *
   * @param failure
   */
  void terminateBuildWithFailure(Throwable failure);

  @Value.Immutable
  @BuckStyleImmutable
  abstract class AbstractBuildEngineResult {
    /** @return a future that will contain the result of running the rule */
    public abstract ListenableFuture<BuildResult> getResult();
  }
}
