/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.distributed.synchronization.impl.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.engine.BuildEngineBuildContext;
import com.facebook.buck.core.build.engine.config.CachingBuildEngineBuckConfig;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.impl.CachingBuildEngine;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.SingleThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.jvm.java.autodeps.JavaDepsFinder;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.EventPostingRuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.TrackedRuleKeyCache;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Utility that aids in creating the objects necessary to traverse the target graph with special
 * knowledge of Java-based rules. This is needed by commands such as {@code buck suggest}.
 */
final class JavaBuildGraphProcessor {

  /** Utility class: do not instantiate. */
  private JavaBuildGraphProcessor() {}

  /**
   * Can be thrown by {@link Processor#process(TargetGraph, JavaDepsFinder,
   * ListeningExecutorService)} to indicate the way in which processing has failed. The exit code
   * value may be useful if the failure is bubbled up to a Buck command.
   */
  static final class ExitCodeException extends Exception {
    public final int exitCode;

    ExitCodeException(int exitCode) {
      this.exitCode = exitCode;
    }
  }

  /**
   * Does the user-defined processing on the objects built up by {@link #run(CommandRunnerParams,
   * AbstractCommand, Processor)}.
   */
  interface Processor {
    void process(
        TargetGraph graph, JavaDepsFinder javaDepsFinder, ListeningExecutorService executorService);
  }

  /**
   * Creates the appropriate target graph and other resources needed for the {@link Processor} and
   * runs it. This method will take responsibility for cleaning up the executor service after it
   * runs.
   */
  static void run(CommandRunnerParams params, AbstractCommand command, Processor processor)
      throws ExitCodeException, InterruptedException, IOException {
    ConcurrencyLimit concurrencyLimit = command.getConcurrencyLimit(params.getBuckConfig());
    try (CommandThreadManager pool =
        new CommandThreadManager(command.getClass().getName(), concurrencyLimit)) {
      Cell cell = params.getCell();

      TargetGraph graph;
      try {
        graph =
            params
                .getParser()
                .buildTargetGraphForTargetNodeSpecs(
                    cell,
                    command.getEnableParserProfiling(),
                    pool.getListeningExecutorService(),
                    ImmutableList.of(
                        TargetNodePredicateSpec.of(
                            BuildFileSpec.fromRecursivePath(Paths.get(""), cell.getRoot()))))
                .getTargetGraph();
      } catch (BuildFileParseException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        throw new ExitCodeException(1);
      }

      BuildRuleResolver buildRuleResolver =
          new SingleThreadedActionGraphBuilder(
              graph,
              new DefaultTargetNodeToBuildRuleTransformer(),
              params.getCell().getCellProvider());
      SourcePathRuleFinder sourcePathRuleFinder = new SourcePathRuleFinder(buildRuleResolver);
      CachingBuildEngineBuckConfig cachingBuildEngineBuckConfig =
          params.getBuckConfig().getView(CachingBuildEngineBuckConfig.class);
      LocalCachingBuildEngineDelegate cachingBuildEngineDelegate =
          new LocalCachingBuildEngineDelegate(params.getFileHashCache());
      try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
              new EventPostingRuleKeyCacheScope<>(
                  params.getBuckEventBus(),
                  new TrackedRuleKeyCache<>(
                      new DefaultRuleKeyCache<>(), new InstrumentingCacheStatsTracker()));
          CachingBuildEngine buildEngine =
              new CachingBuildEngine(
                  cachingBuildEngineDelegate,
                  Optional.empty(),
                  pool.getWeightedListeningExecutorService(),
                  new DefaultStepRunner(),
                  BuildType.SHALLOW,
                  cachingBuildEngineBuckConfig.getBuildMetadataStorage(),
                  cachingBuildEngineBuckConfig.getBuildDepFiles(),
                  cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
                  cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
                  buildRuleResolver,
                  sourcePathRuleFinder,
                  DefaultSourcePathResolver.from(sourcePathRuleFinder),
                  params.getBuildInfoStoreManager(),
                  cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo(),
                  cachingBuildEngineBuckConfig.getConsoleLogBuildRuleFailuresInline(),
                  RuleKeyFactories.of(
                      params.getRuleKeyConfiguration(),
                      cachingBuildEngineDelegate.getFileHashCache(),
                      buildRuleResolver,
                      params.getBuckConfig().getBuildInputRuleKeyFileSizeLimit(),
                      ruleKeyCacheScope.getCache()),
                  new NoOpRemoteBuildRuleCompletionWaiter(),
                  cachingBuildEngineBuckConfig.getManifestServiceIfEnabled(
                      params.getManifestServiceSupplier()))) {
        // Create a BuildEngine because we store symbol information as build artifacts.
        BuckEventBus eventBus = params.getBuckEventBus();
        ExecutionContext executionContext =
            ExecutionContext.builder()
                .setConsole(params.getConsole())
                .setConcurrencyLimit(concurrencyLimit)
                .setBuckEventBus(eventBus)
                .setEnvironment(/* environment */ ImmutableMap.of())
                .setExecutors(ImmutableMap.of(ExecutorPool.CPU, pool.getListeningExecutorService()))
                .setJavaPackageFinder(params.getJavaPackageFinder())
                .setPlatform(params.getPlatform())
                .setCellPathResolver(params.getCell().getCellPathResolver())
                .setBuildCellRootPath(params.getCell().getRoot())
                .setProcessExecutor(new DefaultProcessExecutor(params.getConsole()))
                .setProjectFilesystemFactory(params.getProjectFilesystemFactory())
                .build();

        SourcePathResolver pathResolver =
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(buildRuleResolver));
        BuildEngineBuildContext buildContext =
            BuildEngineBuildContext.builder()
                .setBuildContext(
                    BuildContext.builder()
                        .setSourcePathResolver(pathResolver)
                        .setBuildCellRootPath(cell.getRoot())
                        .setJavaPackageFinder(executionContext.getJavaPackageFinder())
                        .setEventBus(eventBus)
                        .setShouldDeleteTemporaries(
                            params.getBuckConfig().getShouldDeleteTemporaries())
                        .build())
                .setClock(params.getClock())
                .setArtifactCache(params.getArtifactCacheFactory().newInstance())
                .setBuildId(eventBus.getBuildId())
                .setEnvironment(executionContext.getEnvironment())
                .setKeepGoing(false)
                .build();

        // Traverse the TargetGraph to find all of the auto-generated dependencies.
        JavaDepsFinder javaDepsFinder =
            JavaDepsFinder.createJavaDepsFinder(
                params.getBuckConfig(), buildContext, executionContext, buildEngine);

        processor.process(graph, javaDepsFinder, pool.getListeningExecutorService());
      }
    }
  }
}
