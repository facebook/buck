/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FlushConsoleEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.Statistics;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.PrintStream;
import org.kohsuke.args4j.Option;

/**
 * This is the core of our perf commands, it handles the outer prepare + loop and statistics
 * gathering.
 */
public abstract class AbstractPerfCommand<CommandContext> extends AbstractCommand {

  @Option(name = "--repeat", usage = "controls how many times to run the computation.")
  private int repeat = 1;

  @Option(name = "--ignore-first", usage = "ignores the first n runs for the computed statistics.")
  private int ignoreCount = 0;

  @Option(
      name = "--stop-after-seconds",
      usage =
          "configures a time after which the command will exit (pending any executing computations), regardless of the --repeat paramater.")
  private int exitDuration = Integer.MAX_VALUE;

  @Option(
      name = "--force-gc-between-runs",
      usage =
          "calls System.gc() between each run. This can reduce the variance, but may also hide the gc cost of the thing we are measuring.")
  private boolean forceGcBetweenRuns = false;

  /** Result of invocation of the targeted perf test. */
  interface PerfResult {
    // TODO(cjhopman): Do something with this or delete it.
  }

  /** Helper for printing warnings to the console. */
  protected void printWarning(CommandRunnerParams params, String format, Object... args) {
    printWarning(params, String.format(format, args));
  }

  /** Helper for printing warnings to the console. */
  protected void printWarning(CommandRunnerParams params, String s) {
    params.getBuckEventBus().post(ConsoleEvent.warning(s));
  }

  protected abstract String getComputationName();

  /**
   * Heavyweight setup can be done in prepareTest. The returned context will be provided in each
   * runPerfTest call.
   */
  abstract CommandContext prepareTest(CommandRunnerParams params) throws Exception;

  /** Run the targeted test. */
  abstract void runPerfTest(CommandRunnerParams params, CommandContext context) throws Exception;

  @Override
  public final ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    CommandContext context = prepareTest(params);

    Stopwatch stopwatch = Stopwatch.createStarted();
    Stopwatch current = Stopwatch.createStarted();

    Statistics statistics = new Statistics();

    int count = 0;

    for (int i = 0; i < repeat; i++) {
      if (i != 0 && exitDuration > 0 && stopwatch.elapsed().getSeconds() >= exitDuration) {
        printWarning(params, "Exiting after %d cycles due to reaching requested duration.", i);
        break;
      }

      boolean ignore = i < ignoreCount;

      printWarning(
          params,
          "Beginning computation %d%s.%s",
          i + 1,
          ignore ? "(ignored)" : "",
          i == 0
              ? ""
              : String.format(
                  " Current duration %.02f sec, total duration %.02f sec%s.",
                  current.elapsed().toMillis() / 1000.,
                  stopwatch.elapsed().toMillis() / 1000.,
                  exitDuration == 0 ? "" : String.format(" (max duration %s sec)", exitDuration)));

      if (forceGcBetweenRuns) {
        System.gc();
      }
      current.reset().start();
      runPerfTest(params, context);
      if (!ignore) {
        statistics.addValue(current.elapsed().toMillis());
      }
      count++;
    }

    stopwatch.stop();
    params.getBuckEventBus().post(new FlushConsoleEvent());

    PrintStream out = params.getConsole().getStdOut();
    out.printf(
        "Running %d %s computations took %.03f sec\n",
        count, getComputationName(), (stopwatch.elapsed().toMillis() / 1000.));

    if (count > 1 && statistics.getN() > 1) {
      double mean = statistics.getMean() / 1000.;
      double off = statistics.getConfidenceIntervalOffset() / 1000.;
      out.printf(
          "Recorded %d statistics. Mean %.03f sec [%.03f, %.03f].\n",
          statistics.getN(), mean, mean - off, mean + off);
    }

    // TODO(cjhopman): Do something with PerfResult
    return ExitCode.SUCCESS;
  }

  /** Most of our perf tests require a target graph, this helps them get it concisely. */
  protected TargetGraph getTargetGraph(
      CommandRunnerParams params, ImmutableSet<BuildTarget> targets)
      throws InterruptedException, IOException, VersionException {
    TargetGraph targetGraph;
    try (CommandThreadManager pool =
        new CommandThreadManager("Perf", getConcurrencyLimit(params.getBuckConfig()))) {
      targetGraph =
          params
              .getParser()
              .buildTargetGraph(
                  createParsingContext(params.getCell(), pool.getListeningExecutorService()),
                  targets);
    } catch (BuildFileParseException e) {
      throw new BuckUncheckedExecutionException(e);
    }
    if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
      targetGraph =
          toVersionedTargetGraph(params, TargetGraphAndBuildTargets.of(targetGraph, targets))
              .getTargetGraph();
    }
    return targetGraph;
  }

  @Override
  public final boolean isReadOnly() {
    return true;
  }
}
