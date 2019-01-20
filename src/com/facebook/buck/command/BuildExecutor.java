/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.command;

import com.facebook.buck.core.build.engine.BuildEngineResult;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Interface to be followed by local/distributed builders. */
public interface BuildExecutor {

  /**
   * Builds the given targets synchronously. Failures are printed to the EventBus.
   *
   * @param targetsToBuild
   * @return exit code.
   */
  ExitCode buildLocallyAndReturnExitCode(
      Iterable<String> targetsToBuild, Optional<Path> pathToBuildReport) throws Exception;

  ExitCode buildTargets(Iterable<BuildTarget> targetsToBuild, Optional<Path> pathToBuildReport)
      throws Exception;

  /**
   * Starts building the given targets, but does not wait for them to finish
   *
   * @param targetsToBuild
   * @return Futures representing the targets that are building.
   * @throws IOException
   */
  List<BuildEngineResult> initializeBuild(Iterable<String> targetsToBuild) throws IOException;

  /**
   * Waits for all targets contained in resultFutures to finish.
   *
   * @param targetsToBuild - same as passed to initializeBuild call
   * @param resultFutures - result from initializeBuild call
   * @return exit code
   */
  ExitCode waitForBuildToFinish(
      Iterable<String> targetsToBuild,
      List<BuildEngineResult> resultFutures,
      Optional<Path> pathToBuildReport)
      throws Exception;

  /**
   * Accessor method for the {@link CachingBuildEngine} instance being used by this {@link
   * BuildExecutor}.
   */
  CachingBuildEngine getCachingBuildEngine();

  /**
   * Destroy any resources associated with this builder. Call this once only, when all
   * buildLocallyAndReturnExitCode calls have finished.
   */
  void shutdown();
}
