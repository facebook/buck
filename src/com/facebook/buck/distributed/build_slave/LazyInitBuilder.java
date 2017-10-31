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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.rules.BuildEngineResult;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Decorates Builder to do lazy initialisation. */
public class LazyInitBuilder implements BuildExecutor {

  private final Supplier<BuildExecutor> builderSupplier;

  public LazyInitBuilder(Supplier<BuildExecutor> builderSupplier) {
    this.builderSupplier = Suppliers.memoize(builderSupplier);
  }

  private BuildExecutor getBuilderNow() {
    return Preconditions.checkNotNull(builderSupplier.get());
  }

  @Override
  public int buildLocallyAndReturnExitCode(
      Iterable<String> targetsToBuild, Optional<Path> pathToBuildReport)
      throws IOException, InterruptedException {
    return getBuilderNow().buildLocallyAndReturnExitCode(targetsToBuild, pathToBuildReport);
  }

  @Override
  public List<BuildEngineResult> initializeBuild(Iterable<String> targetsToBuild)
      throws IOException {
    // TODO(ruibm): BuildEngineResult is a future in disguise so we should be able to do
    // something smarter here instead of of sync blocking the resolution of the graphs.
    return getBuilderNow().initializeBuild(targetsToBuild);
  }

  @Override
  public int waitForBuildToFinish(
      Iterable<String> targetsToBuild,
      List<BuildEngineResult> resultFutures,
      Optional<Path> pathToBuildReport) {
    return getBuilderNow().waitForBuildToFinish(targetsToBuild, resultFutures, pathToBuildReport);
  }

  @Override
  public void shutdown() throws IOException {
    getBuilderNow().shutdown();
  }
}
