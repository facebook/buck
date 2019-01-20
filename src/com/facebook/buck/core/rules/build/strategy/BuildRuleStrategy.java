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

package com.facebook.buck.core.rules.build.strategy;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.rules.BuildRule;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

/** Interface for injecting customized behavior into the CachingBuildEngine. */
public interface BuildRuleStrategy extends Closeable {
  @Override
  void close() throws IOException;

  /** Builds the rule. */
  StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext);

  /** A rule will be built by the custom strategy only if canBuild() returns true. */
  boolean canBuild(BuildRule instance);

  /** A simple interface for build results exposing an explicit cancellation. */
  interface StrategyBuildResult {
    /**
     * Indicates that the caller is no longer interested in the result and the strategy is free to
     * cancel pending work.
     */
    void cancel(Throwable cause);

    /**
     * Tries to cancel the execution if work has not yet begun.
     *
     * @return Whether cancellation was successful. If successful, the strategy might continue doing
     *     more work, but it must not make changes to any rule outputs. If cancellation is
     *     unsuccessful, the strategy should continue execution of the rule.
     */
    boolean cancelIfNotStarted(Throwable reason);

    /** A ListenableFuture for the build result. */
    ListenableFuture<Optional<BuildResult>> getBuildResult();

    /** A simple helper to make a StrategyBuildResult that can't be cancelled. */
    static StrategyBuildResult nonCancellable(ListenableFuture<Optional<BuildResult>> result) {
      return new StrategyBuildResult() {
        @Override
        public void cancel(Throwable cause) {
          // ignored.
        }

        @Override
        public boolean cancelIfNotStarted(Throwable reason) {
          return false;
        }

        @Override
        public ListenableFuture<Optional<BuildResult>> getBuildResult() {
          return result;
        }
      };
    }
  }
}
