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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

/**
 * A build engine is responsible for building a given build rule, which includes all its transitive
 * dependencies.
 */
public interface BuildEngine {

  /**
   * Calculate the total number of transitive build rules processed from the given roots.
   */
  int getNumRulesToBuild(Iterable<BuildRule> rule);

  /**
   * Build the given build rule and return a future to the build rule success.
   */
  ListenableFuture<BuildResult> build(BuildContext context, BuildRule rule);

  /**
   * Returns the build result of the build rule associated with the given build target.
   * Returns {@code null} if the build rule has not yet been built.
   */
  BuildResult getBuildRuleResult(BuildTarget buildTarget)
      throws ExecutionException, InterruptedException;

  /**
   * Returns whether the build rule associated with the build target has been successfully built.
   */
  boolean isRuleBuilt(BuildTarget buildTarget) throws InterruptedException;

  /**
   * This is a temporary hack to expose a build rule's rule key to the associated buildable.
   */
  RuleKey getRuleKey(BuildTarget buildTarget);
}
