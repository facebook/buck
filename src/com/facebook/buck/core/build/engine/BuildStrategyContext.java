/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.util.Scope;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Optional;

/**
 * Used for running a BuildExecutor within the context of the build engine such that the engine's
 * internal state/tracking is updated as expected. Provides ability to decide at runtime whether to
 * use custom BuildExecutor or just the default.
 */
public interface BuildStrategyContext {
  ListenableFuture<Optional<BuildResult>> runWithDefaultBehavior();

  ListeningExecutorService getExecutorService();

  BuildResult createBuildResult(BuildRuleSuccessType successType);

  BuildResult createCancelledResult(Throwable throwable);

  ExecutionContext getExecutionContext();

  Scope buildRuleScope();

  BuildContext getBuildRuleBuildContext();

  BuildableContext getBuildableContext();
}
