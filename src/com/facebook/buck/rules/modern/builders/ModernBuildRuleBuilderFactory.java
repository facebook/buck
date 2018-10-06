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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.build.engine.BuildExecutorRunner;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.factory.RemoteExecutionClientsFactory;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Constructs various BuildRuleStrategies for ModernBuildRules based on the
 * modern_build_rule.strategy config option.
 */
public class ModernBuildRuleBuilderFactory {
  /** Creates a BuildRuleStrategy for ModernBuildRules based on the buck configuration. */
  public static Optional<BuildRuleStrategy> getBuildStrategy(
      ModernBuildRuleConfig config,
      RemoteExecutionConfig remoteExecutionConfig,
      BuildRuleResolver resolver,
      Cell rootCell,
      CellPathResolver cellResolver,
      FileHashLoader hashLoader,
      BuckEventBus eventBus,
      Console console,
      ExecutorService remoteExecutorService) {
    try {
      RemoteExecutionClientsFactory remoteExecutionFactory =
          new RemoteExecutionClientsFactory(remoteExecutionConfig);
      switch (config.getBuildStrategy()) {
        case NONE:
          return Optional.empty();

        case DEBUG_RECONSTRUCT:
          return Optional.of(
              createReconstructing(new SourcePathRuleFinder(resolver), cellResolver, rootCell));
        case DEBUG_PASSTHROUGH:
          return Optional.of(createPassthrough());
        case DEBUG_ISOLATED_IN_PROCESS:
          return Optional.of(
              createIsolatedInProcess(
                  new SourcePathRuleFinder(resolver),
                  cellResolver,
                  rootCell,
                  hashLoader::get,
                  eventBus,
                  console));

        case REMOTE:
        case GRPC_REMOTE:
        case DEBUG_GRPC_SERVICE_IN_PROCESS:
        case DEBUG_ISOLATED_OUT_OF_PROCESS_GRPC:
          return Optional.of(
              RemoteExecution.createRemoteExecutionStrategy(
                  eventBus,
                  remoteExecutionConfig.useWorkerThreadPool()
                      ? Optional.of(remoteExecutorService)
                      : Optional.empty(),
                  remoteExecutionFactory.create(eventBus),
                  new SourcePathRuleFinder(resolver),
                  cellResolver,
                  rootCell,
                  hashLoader::get));
      }
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(e, "When creating MBR build strategy.");
    }
    throw new IllegalStateException(
        "Unrecognized build strategy " + config.getBuildStrategy() + ".");
  }

  /** The passthrough strategy just forwards to executorRunner.runWithDefaultExecutor. */
  public static BuildRuleStrategy createPassthrough() {
    return new AbstractModernBuildRuleStrategy() {
      @Override
      public void build(
          ListeningExecutorService service, BuildRule rule, BuildExecutorRunner executorRunner) {
        service.execute(executorRunner::runWithDefaultExecutor);
      }
    };
  }

  /**
   * The reconstructing strategy serializes and deserializes the build rule in memory and builds the
   * deserialized version.
   */
  public static BuildRuleStrategy createReconstructing(
      SourcePathRuleFinder ruleFinder, CellPathResolver cellResolver, Cell rootCell) {
    return new ReconstructingStrategy(ruleFinder, cellResolver, rootCell);
  }

  /**
   * This strategy will construct a separate isolated build directory for each rule. The rule will
   * be serialized to data files in that directory, and all inputs required (including buck configs)
   * will be materialized there and then the rule will be deserialized within this process and run
   * within that directory. The outputs will be copied back to the real build directory.
   */
  public static BuildRuleStrategy createIsolatedInProcess(
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher,
      BuckEventBus eventBus,
      Console console)
      throws IOException {
    return IsolatedExecution.createIsolatedExecutionStrategy(
        new InProcessIsolatedExecution(eventBus, console),
        ruleFinder,
        cellResolver,
        rootCell,
        fileHasher,
        Optional.empty());
  }
}
