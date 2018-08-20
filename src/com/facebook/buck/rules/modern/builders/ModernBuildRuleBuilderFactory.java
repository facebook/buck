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
import com.facebook.buck.rules.modern.builders.grpc.GrpcExecutionFactory;
import com.facebook.buck.rules.modern.builders.grpc.GrpcProtocol;
import com.facebook.buck.rules.modern.builders.thrift.ThriftProtocol;
import com.facebook.buck.rules.modern.builders.thrift.ThriftRemoteExecutionFactory;
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

/**
 * Constructs various BuildRuleStrategies for ModernBuildRules based on the
 * modern_build_rule.strategy config option.
 */
public class ModernBuildRuleBuilderFactory {
  /** Creates a BuildRuleStrategy for ModernBuildRules based on the buck configuration. */
  public static Optional<BuildRuleStrategy> getBuildStrategy(
      ModernBuildRuleConfig config,
      BuildRuleResolver resolver,
      Cell rootCell,
      CellPathResolver cellResolver,
      FileHashLoader hashLoader,
      BuckEventBus eventBus,
      Console console) {
    try {
      switch (config.getBuildStrategy()) {
        case NONE:
          return Optional.empty();
        case GRPC_REMOTE:
          return Optional.of(
              createGrpcRemote(
                  eventBus,
                  new SourcePathRuleFinder(resolver),
                  cellResolver,
                  rootCell,
                  hashLoader::get,
                  config.getRemoteHost(),
                  config.getRemotePort()));
        case THRIFT_REMOTE:
          return Optional.of(
              createThriftRemote(
                  eventBus,
                  new SourcePathRuleFinder(resolver),
                  cellResolver,
                  rootCell,
                  hashLoader::get,
                  config.getRemoteHost(),
                  config.getRemotePort(),
                  config.getCasHost(),
                  config.getCasPort()));
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
        case DEBUG_ISOLATED_OUT_OF_PROCESS:
          return Optional.of(
              createIsolatedOutOfProcess(
                  eventBus,
                  new SourcePathRuleFinder(resolver),
                  cellResolver,
                  rootCell,
                  hashLoader::get,
                  new ThriftProtocol()));
        case DEBUG_ISOLATED_OUT_OF_PROCESS_GRPC:
          return Optional.of(
              createIsolatedOutOfProcess(
                  eventBus,
                  new SourcePathRuleFinder(resolver),
                  cellResolver,
                  rootCell,
                  hashLoader::get,
                  new GrpcProtocol()));
        case DEBUG_GRPC_SERVICE_IN_PROCESS:
          return Optional.of(
              createGrpcInProcess(
                  eventBus,
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
   * will be materialized there. Buck's own classpath will also be materialized there and then a
   * separate subprocess will be created to deserialize and run the rule. Outputs will then be
   * copied back to the real build directory.
   */
  public static BuildRuleStrategy createIsolatedOutOfProcess(
      BuckEventBus eventBus,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher,
      Protocol protocol)
      throws IOException {
    return IsolatedExecution.createIsolatedExecutionStrategy(
        OutOfProcessIsolatedExecution.create(protocol, eventBus),
        ruleFinder,
        cellResolver,
        rootCell,
        fileHasher);
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
        fileHasher);
  }

  private static BuildRuleStrategy createGrpcRemote(
      BuckEventBus eventBus,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher,
      String host,
      int port)
      throws IOException {
    return IsolatedExecution.createIsolatedExecutionStrategy(
        GrpcExecutionFactory.createRemote(host, port, eventBus),
        ruleFinder,
        cellResolver,
        rootCell,
        fileHasher);
  }

  private static BuildRuleStrategy createThriftRemote(
      BuckEventBus eventBus,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher,
      String remoteExecutionEngineHost,
      int remoteExecutionEnginePort,
      String casHost,
      int casPort)
      throws IOException {
    return IsolatedExecution.createIsolatedExecutionStrategy(
        ThriftRemoteExecutionFactory.createRemote(
            remoteExecutionEngineHost, remoteExecutionEnginePort, casHost, casPort, eventBus),
        ruleFinder,
        cellResolver,
        rootCell,
        fileHasher);
  }

  public static BuildRuleStrategy createGrpcInProcess(
      BuckEventBus eventBus,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher)
      throws IOException {
    return IsolatedExecution.createIsolatedExecutionStrategy(
        GrpcExecutionFactory.createInProcess(eventBus),
        ruleFinder,
        cellResolver,
        rootCell,
        fileHasher);
  }
}
