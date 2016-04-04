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

import com.facebook.buck.autodeps.AutodepsWriter;
import com.facebook.buck.autodeps.DepsForBuildFiles;
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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ImmutableBuildContext;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;

/**
 * Command for generating BUCK.generated files with deps.
 */
public class AutodepsCommand extends AbstractCommand {

  /*
   * This command is intended to support inferring dependencies for various programming languages,
   * though initially, we focus exclusively on Java. Further, it should be possible to run this
   * command on a specific set of BUCK files, but at least initially, we will only support running
   * this on the entire project.
   *
   * Finally, the initial implementation is admittedly very inefficient because it does not do any
   * caching. It should leverage Watchman to avoid re-scanning the entire filesystem every time.
   */

  @Override
  public int runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    ConcurrencyLimit concurrencyLimit = getConcurrencyLimit(params.getBuckConfig());
    try (CommandThreadManager pool = new CommandThreadManager(
        "Autodeps",
        WorkQueueExecutionOrder.FIFO, // FIFO vs. LIFO probably does not matter here?
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
                getEnableProfiling(),
                executorService,
                ImmutableList.of(
                    TargetNodePredicateSpec.of(
                        Predicates.<TargetNode<?>>alwaysTrue(),
                        BuildFileSpec.fromRecursivePath(
                            Paths.get("")))),
                /* ignoreBuckAutodepsFiles */ true).getTargetGraph();
      } catch (BuildTargetException | BuildFileParseException e) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
            MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      BuildRuleResolver buildRuleResolver = new BuildRuleResolver(
          graph,
          new DefaultTargetNodeToBuildRuleTransformer());
      BuildEngine buildEngine = new CachingBuildEngine(
          executorService,
          params.getFileHashCache(),
          CachingBuildEngine.BuildMode.SHALLOW,
          params.getBuckConfig().getDependencySchedulingOrder(),
          params.getBuckConfig().getBuildDepFiles(),
          params.getBuckConfig().getBuildMaxDepFileCacheEntries(),
          buildRuleResolver);

      // Create a BuildEngine because we store symbol information as build artifacts.
      BuckEventBus eventBus = params.getBuckEventBus();
      ExecutionContext executionContext = ExecutionContext.builder()
          .setConsole(params.getConsole())
          .setConcurrencyLimit(concurrencyLimit)
          .setEventBus(eventBus)
          .setEnvironment(/* environment */ ImmutableMap.<String, String>of())
          .setExecutors(
              ImmutableMap.<ExecutionContext.ExecutorPool, ListeningExecutorService>of(
                  ExecutionContext.ExecutorPool.CPU,
                  executorService))
          .setJavaPackageFinder(params.getJavaPackageFinder())
          .setObjectMapper(params.getObjectMapper())
          .setPlatform(params.getPlatform())
          .build();
      StepRunner stepRunner = new DefaultStepRunner(executionContext);

      BuildContext buildContext = ImmutableBuildContext.builder()
          // Note we do not create a real action graph because we do not need one.
          .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
          .setStepRunner(stepRunner)
          .setClock(params.getClock())
          .setArtifactCache(params.getArtifactCache())
          .setJavaPackageFinder(executionContext.getJavaPackageFinder())
          .setEventBus(eventBus)
          .setBuildId(eventBus.getBuildId())
          .putAllEnvironment(executionContext.getEnvironment())
          .setKeepGoing(false)
          .setShouldReportAbsolutePaths(false)
          .build();

      // Traverse the TargetGraph to find all of the auto-generated dependencies.
      JavaDepsFinder javaDepsFinder = JavaDepsFinder.createJavaDepsFinder(
          params.getBuckConfig(),
          params.getCell().getCellRoots(),
          params.getObjectMapper(),
          buildContext,
          buildEngine);
      Console console = params.getConsole();
      DepsForBuildFiles depsForBuildFiles = javaDepsFinder.findDepsForBuildFiles(graph, console);

      // Now that the dependencies have been computed, write out the BUCK.autodeps files.
      int numWritten;
      try {
        numWritten = AutodepsWriter.write(
            depsForBuildFiles,
            cell.getBuildFileName(),
            params.getObjectMapper(),
            executorService,
            concurrencyLimit.threadLimit);
      } catch (ExecutionException e) {
        throw new RuntimeException(e);
      }

      String message = numWritten == 1
          ? "1 file written."
          : numWritten + " files written.";
      console.printSuccess(message);
    }

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "auto-generates dependencies for build rules, where possible";
  }
}
