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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.java.autodeps.JavaDepsFinder;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.ImmutableBuildContext;
import com.facebook.buck.rules.LocalCachingBuildEngineDelegate;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * Utility that aids in creating the objects necessary to traverse the target graph with special
 * knowledge of Java-based rules. This is needed by commands such as {@code buck autodeps} and
 * {@code buck suggest}.
 */
final class JavaBuildGraphProcessor {

  /** Utility class: do not instantiate. */
  private JavaBuildGraphProcessor() {}

  /**
   * Can be thrown by
   * {@link Processor#process(TargetGraph, JavaDepsFinder, WeightedListeningExecutorService)} to
   * indicate the way in which processing has failed. The exit code value may be useful if the
   * failure is bubbled up to a Buck command.
   */
  static final class ExitCodeException extends Exception {
    public final int exitCode;
    ExitCodeException(int exitCode) {
      this.exitCode = exitCode;
    }
  }

  /**
   * Does the user-defined processing on the objects built up by
   * {@link #run(CommandRunnerParams, AbstractCommand, Processor)}.
   */
  interface Processor {
    void process(
        TargetGraph graph,
        JavaDepsFinder javaDepsFinder,
        WeightedListeningExecutorService executorService);
  }

  /**
   * Creates the appropriate target graph and other resources needed for the {@link Processor} and
   * runs it. This method will take responsibility for cleaning up the executor service after it
   * runs.
   */
  static void run(
      final CommandRunnerParams params,
      final AbstractCommand command,
      final Processor processor
  ) throws ExitCodeException, InterruptedException, IOException {
    final ConcurrencyLimit concurrencyLimit = command.getConcurrencyLimit(params.getBuckConfig());
    try (CommandThreadManager pool = new CommandThreadManager(
        command.getClass().getName(),
        concurrencyLimit)) {
      Cell cell = params.getCell();
      WeightedListeningExecutorService executorService = pool.getExecutor();

      // Ideally, we should be able to construct the TargetGraph quickly assuming most of it is
      // already in memory courtesy of buckd. Though we could make a performance optimization where
      // we pass an option to buck.py that tells it to ignore reading the BUCK.autodeps files when
      // parsing the BUCK files because we never need to consider the existing auto-generated deps
      // when creating the new auto-generated deps. If we did so, we would have to make sure to keep
      // the nodes for that version of the graph separate from the ones that are actually used for
      // building.
      TargetGraph graph;
      try {
        graph = params.getParser()
            .buildTargetGraphForTargetNodeSpecs(
                params.getBuckEventBus(),
                cell,
                command.getEnableParserProfiling(),
                executorService,
                ImmutableList.of(
                    TargetNodePredicateSpec.of(
                        Predicates.alwaysTrue(),
                        BuildFileSpec.fromRecursivePath(Paths.get(""), cell.getRoot()))),
                /* ignoreBuckAutodepsFiles */ true).getTargetGraph();
      } catch (BuildTargetException | BuildFileParseException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
            MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        throw new ExitCodeException(1);
      }

      BuildRuleResolver buildRuleResolver = new BuildRuleResolver(
          graph,
          new DefaultTargetNodeToBuildRuleTransformer());
      BuildEngine buildEngine = new CachingBuildEngine(
          new LocalCachingBuildEngineDelegate(params.getFileHashCache()),
          executorService,
          CachingBuildEngine.BuildMode.SHALLOW,
          params.getBuckConfig().getBuildDepFiles(),
          params.getBuckConfig().getBuildMaxDepFileCacheEntries(),
          params.getBuckConfig().getBuildArtifactCacheSizeLimit(),
          params.getBuckConfig().getBuildInputRuleKeyFileSizeLimit(),
          params.getObjectMapper(),
          buildRuleResolver,
          params.getBuckConfig().getKeySeed(),
          params.getBuckConfig().getResourceAwareSchedulingInfo());

      // Create a BuildEngine because we store symbol information as build artifacts.
      BuckEventBus eventBus = params.getBuckEventBus();
      ExecutionContext executionContext = ExecutionContext.builder()
          .setConsole(params.getConsole())
          .setConcurrencyLimit(concurrencyLimit)
          .setBuckEventBus(eventBus)
          .setEnvironment(/* environment */ ImmutableMap.of())
          .setExecutors(
              ImmutableMap.<ExecutorPool, ListeningExecutorService>of(
                  ExecutorPool.CPU,
                  executorService))
          .setJavaPackageFinder(params.getJavaPackageFinder())
          .setObjectMapper(params.getObjectMapper())
          .setPlatform(params.getPlatform())
          .build();
      StepRunner stepRunner = new DefaultStepRunner(executionContext);

      BuildContext buildContext = ImmutableBuildContext.builder()
          // Note we do not create a real action graph because we do not need one.
          .setActionGraph(new ActionGraph(ImmutableList.of()))
          .setStepRunner(stepRunner)
          .setClock(params.getClock())
          .setArtifactCache(params.getArtifactCache())
          .setJavaPackageFinder(executionContext.getJavaPackageFinder())
          .setEventBus(eventBus)
          .setBuildId(eventBus.getBuildId())
          .setObjectMapper(params.getObjectMapper())
          .putAllEnvironment(executionContext.getEnvironment())
          .setKeepGoing(false)
          .setShouldReportAbsolutePaths(false)
          .build();

      // Traverse the TargetGraph to find all of the auto-generated dependencies.
      JavaDepsFinder javaDepsFinder = JavaDepsFinder.createJavaDepsFinder(
          params.getBuckConfig(),
          params.getCell().getCellPathResolver(),
          params.getObjectMapper(),
          buildContext,
          buildEngine);

      processor.process(graph, javaDepsFinder, executorService);
    }
  }
}
