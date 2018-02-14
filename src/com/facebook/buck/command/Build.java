/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEngineBuildContext;
import com.facebook.buck.rules.BuildEngineResult;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.CleanBuildShutdownException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.immutables.value.Value;

public class Build implements Closeable {

  private static final Logger LOG = Logger.get(Build.class);

  private final BuildRuleResolver ruleResolver;
  private final Cell rootCell;
  private final ExecutionContext executionContext;
  private final ArtifactCache artifactCache;
  private final BuildEngine buildEngine;
  private final JavaPackageFinder javaPackageFinder;
  private final Clock clock;
  private final BuildEngineBuildContext buildContext;
  private boolean symlinksCreated = false;

  public Build(
      BuildRuleResolver ruleResolver,
      Cell rootCell,
      BuildEngine buildEngine,
      ArtifactCache artifactCache,
      JavaPackageFinder javaPackageFinder,
      Clock clock,
      ExecutionContext executionContext,
      boolean isKeepGoing) {
    this.ruleResolver = ruleResolver;
    this.rootCell = rootCell;
    this.executionContext = executionContext;
    this.artifactCache = artifactCache;
    this.buildEngine = buildEngine;
    this.javaPackageFinder = javaPackageFinder;
    this.clock = clock;
    this.buildContext = createBuildContext(isKeepGoing);
  }

  private BuildEngineBuildContext createBuildContext(boolean isKeepGoing) {
    BuildId buildId = executionContext.getBuildId();
    return BuildEngineBuildContext.builder()
        .setBuildContext(
            BuildContext.builder()
                .setSourcePathResolver(
                    DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver)))
                .setBuildCellRootPath(rootCell.getRoot())
                .setJavaPackageFinder(javaPackageFinder)
                .setEventBus(executionContext.getBuckEventBus())
                .build())
        .setClock(clock)
        .setArtifactCache(artifactCache)
        .setBuildId(buildId)
        .putAllEnvironment(executionContext.getEnvironment())
        .setKeepGoing(isKeepGoing)
        .build();
  }

  public BuildRuleResolver getRuleResolver() {
    return ruleResolver;
  }

  public ExecutionContext getExecutionContext() {
    return executionContext;
  }

  // This method is thread-safe
  public int executeAndPrintFailuresToEventBus(
      Iterable<BuildTarget> targetsish,
      BuckEventBus eventBus,
      Console console,
      Optional<Path> pathToBuildReport)
      throws InterruptedException {
    int exitCode;

    ImmutableList<BuildRule> rulesToBuild = getRulesToBuild(targetsish);

    try {
      List<BuildEngineResult> resultFutures = initializeBuild(rulesToBuild);
      exitCode =
          waitForBuildToFinishAndPrintFailuresToEventBus(
              rulesToBuild, resultFutures, eventBus, console, pathToBuildReport);
    } catch (Exception e) {
      reportExceptionToUser(eventBus, e);
      exitCode = 1;
    }

    return exitCode;
  }

  public void terminateBuildWithFailure(Throwable failure) {
    buildEngine.terminateBuildWithFailure(failure);
  }

  /**
   * When the user overrides the configured buck-out directory via the `.buckconfig` and also sets
   * the `project.buck_out_compat_link` setting to `true`, we symlink the original output path
   * (`buck-out/`) to this newly configured location for backwards compatibility.
   */
  private synchronized void createConfiguredBuckOutSymlinks() throws IOException {
    // Symlinks should only be created once, across all invocations of Build, otherwise
    // there will be file system conflicts.
    if (symlinksCreated) {
      return;
    }

    for (Cell cell : rootCell.getAllCells()) {
      BuckConfig buckConfig = cell.getBuckConfig();
      ProjectFilesystem filesystem = cell.getFilesystem();
      BuckPaths configuredPaths = filesystem.getBuckPaths();
      if (!configuredPaths.getConfiguredBuckOut().equals(configuredPaths.getBuckOut())
          && buckConfig.getBuckOutCompatLink()
          && Platform.detect() != Platform.WINDOWS) {
        BuckPaths unconfiguredPaths =
            configuredPaths.withConfiguredBuckOut(configuredPaths.getBuckOut());
        ImmutableMap<Path, Path> paths =
            ImmutableMap.of(
                unconfiguredPaths.getGenDir(), configuredPaths.getGenDir(),
                unconfiguredPaths.getScratchDir(), configuredPaths.getScratchDir());
        for (Map.Entry<Path, Path> entry : paths.entrySet()) {
          filesystem.deleteRecursivelyIfExists(entry.getKey());
          filesystem.createSymLink(
              entry.getKey(),
              entry.getKey().getParent().relativize(entry.getValue()),
              /* force */ false);
        }
      }
    }

    symlinksCreated = true;
  }

  /**
   * * Converts given BuildTargets into BuildRules
   *
   * @param targetish
   * @return
   */
  public ImmutableList<BuildRule> getRulesToBuild(Iterable<? extends BuildTarget> targetish) {
    // It is important to use this logic to determine the set of rules to build rather than
    // build.getActionGraph().getNodesWithNoIncomingEdges() because, due to graph enhancement,
    // there could be disconnected subgraphs in the DependencyGraph that we do not want to build.
    ImmutableSet<BuildTarget> targetsToBuild =
        StreamSupport.stream(targetish.spliterator(), false).collect(ImmutableSet.toImmutableSet());

    ImmutableList<BuildRule> rulesToBuild =
        ImmutableList.copyOf(
            targetsToBuild
                .stream()
                .map(buildTarget -> getRuleResolver().requireRule(buildTarget))
                .collect(ImmutableSet.toImmutableSet()));

    // Calculate and post the number of rules that need to built.
    int numRules = buildEngine.getNumRulesToBuild(rulesToBuild);
    getExecutionContext()
        .getBuckEventBus()
        .post(BuildEvent.ruleCountCalculated(targetsToBuild, numRules));
    return rulesToBuild;
  }

  /** Represents an exceptional situation that happened while building requested rules. */
  private static class BuildExecutionException extends ExecutionException {
    private final ImmutableList<BuildRule> rulesToBuild;
    private final List<BuildResult> buildResults;

    BuildExecutionException(
        Throwable cause, ImmutableList<BuildRule> rulesToBuild, List<BuildResult> buildResults) {
      super(cause);
      this.rulesToBuild = rulesToBuild;
      this.buildResults = buildResults;
    }

    /**
     * Creates a build execution result that might be partial when interrupted before all build
     * rules had a chance to run.
     */
    public BuildExecutionResult createBuildExecutionResult() {
      // Insertion order matters
      LinkedHashMap<BuildRule, Optional<BuildResult>> resultBuilder = new LinkedHashMap<>();
      for (int i = 0, len = buildResults.size(); i < len; i++) {
        BuildRule rule = rulesToBuild.get(i);
        resultBuilder.put(rule, Optional.ofNullable(buildResults.get(i)));
      }

      return BuildExecutionResult.builder()
          .setFailures(
              buildResults
                  .stream()
                  .filter(input -> !input.isSuccess())
                  .collect(Collectors.toList()))
          .setResults(resultBuilder)
          .build();
    }
  }

  private static BuildExecutionResult createBuildExecutionResult(
      ImmutableList<BuildRule> rulesToBuild, List<BuildResult> results) {
    // Insertion order matters
    LinkedHashMap<BuildRule, Optional<BuildResult>> resultBuilder = new LinkedHashMap<>();

    Preconditions.checkState(rulesToBuild.size() == results.size());
    for (int i = 0, len = rulesToBuild.size(); i < len; i++) {
      BuildRule rule = rulesToBuild.get(i);
      resultBuilder.put(rule, Optional.ofNullable(results.get(i)));
    }

    return BuildExecutionResult.builder()
        .setFailures(
            results.stream().filter(input -> !input.isSuccess()).collect(Collectors.toList()))
        .setResults(resultBuilder)
        .build();
  }

  /**
   * Starts building the given BuildRules asynchronously.
   *
   * @param rulesToBuild
   * @return Futures that will complete once the rules have finished building
   * @throws IOException
   */
  public List<BuildEngineResult> initializeBuild(ImmutableList<BuildRule> rulesToBuild)
      throws IOException {
    // Setup symlinks required when configuring the output path.
    createConfiguredBuckOutSymlinks();

    List<BuildEngineResult> resultFutures =
        rulesToBuild
            .stream()
            .map(rule -> buildEngine.build(buildContext, executionContext, rule))
            .collect(ImmutableList.toImmutableList());

    return resultFutures;
  }

  private BuildExecutionResult waitForBuildToFinish(
      ImmutableList<BuildRule> rulesToBuild, List<BuildEngineResult> resultFutures)
      throws ExecutionException, InterruptedException, CleanBuildShutdownException {
    // Get the Future representing the build and then block until everything is built.
    ListenableFuture<List<BuildResult>> buildFuture =
        Futures.allAsList(
            resultFutures.stream().map(BuildEngineResult::getResult).collect(Collectors.toList()));
    List<BuildResult> results;
    try {
      results = buildFuture.get();
      if (!buildContext.isKeepGoing()) {
        for (BuildResult result : results) {
          if (!result.isSuccess()) {
            if (result.getFailure() instanceof CleanBuildShutdownException) {
              throw (CleanBuildShutdownException) result.getFailure();
            } else {
              throw new BuildExecutionException(result.getFailure(), rulesToBuild, results);
            }
          }
        }
      }
    } catch (ExecutionException | InterruptedException | RuntimeException e) {
      Throwable t = Throwables.getRootCause(e);
      if (e instanceof InterruptedException
          || t instanceof InterruptedException
          || t instanceof ClosedByInterruptException) {
        try {
          buildFuture.cancel(true);
        } catch (CancellationException ex) {
          // Rethrow original InterruptedException instead.
          LOG.warn(ex, "Received CancellationException during processing of InterruptedException");
        }
        Threads.interruptCurrentThread();
      }
      throw e;
    }
    return createBuildExecutionResult(rulesToBuild, results);
  }

  private int processBuildReportAndGenerateExitCode(
      BuildExecutionResult buildExecutionResult,
      BuckEventBus eventBus,
      Console console,
      Optional<Path> pathToBuildReport)
      throws IOException {
    int exitCode;

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));
    BuildReport buildReport = new BuildReport(buildExecutionResult, pathResolver);

    if (buildContext.isKeepGoing()) {
      String buildReportText = buildReport.generateForConsole(console);
      buildReportText =
          buildReportText.isEmpty()
              ? "Failure report is empty."
              :
              // Remove trailing newline from build report.
              buildReportText.substring(0, buildReportText.length() - 1);
      eventBus.post(ConsoleEvent.info(buildReportText));
      exitCode = buildExecutionResult.getFailures().isEmpty() ? 0 : 1;
      if (exitCode != 0) {
        eventBus.post(ConsoleEvent.severe("Not all rules succeeded."));
      }
    } else {
      exitCode = 0;
    }

    if (pathToBuildReport.isPresent()) {
      // Note that pathToBuildReport is an absolute path that may exist outside of the project
      // root, so it is not appropriate to use ProjectFilesystem to write the output.
      String jsonBuildReport = buildReport.generateJsonBuildReport();
      // TODO(cjhopman): The build report should use an ErrorLogger to extract good error
      // messages.
      try {
        Files.write(jsonBuildReport, pathToBuildReport.get().toFile(), Charsets.UTF_8);
      } catch (IOException e) {
        eventBus.post(ThrowableConsoleEvent.create(e, "Failed writing report"));
        exitCode = 1;
      }
    }

    return exitCode;
  }

  /**
   * * Waits for the given BuildRules to finish building (as tracked by the corresponding Futures).
   * Prints all failures to the event bus.
   *
   * @param rulesToBuild
   * @param resultFutures
   * @param eventBus
   * @param console
   * @param pathToBuildReport
   * @return
   */
  public int waitForBuildToFinishAndPrintFailuresToEventBus(
      ImmutableList<BuildRule> rulesToBuild,
      List<BuildEngineResult> resultFutures,
      BuckEventBus eventBus,
      Console console,
      Optional<Path> pathToBuildReport) {
    int exitCode;

    try {
      // Can throw BuildExecutionException
      BuildExecutionResult buildExecutionResult = waitForBuildToFinish(rulesToBuild, resultFutures);

      exitCode =
          processBuildReportAndGenerateExitCode(
              buildExecutionResult, eventBus, console, pathToBuildReport);
    } catch (CleanBuildShutdownException e) {
      LOG.warn(e, "Build shutdown cleanly.");
      exitCode = 1;
    } catch (Exception e) {
      if (e instanceof BuildExecutionException) {
        pathToBuildReport.ifPresent(
            path -> writePartialBuildReport(eventBus, path, (BuildExecutionException) e));
      }
      reportExceptionToUser(eventBus, e);
      exitCode = 1;
    }

    return exitCode;
  }

  private void reportExceptionToUser(BuckEventBus eventBus, Exception e) {
    if (e instanceof RuntimeException) {
      e = rootCauseOfBuildException(e);
    }
    new ErrorLogger(eventBus, "Build failed: ", "Got an exception during the build.")
        .logException(e);
  }

  /**
   * Returns a root cause of the build exception {@code e}.
   *
   * @param e The build exception.
   * @return The root cause exception for why the build failed.
   */
  private Exception rootCauseOfBuildException(Exception e) {
    Throwable cause = e.getCause();
    if (cause == null || !(cause instanceof Exception)) {
      return e;
    }
    if (cause instanceof IOException
        || cause instanceof StepFailedException
        || cause instanceof InterruptedException
        || cause instanceof ExceptionWithHumanReadableMessage) {
      return (Exception) cause;
    }
    return e;
  }

  /**
   * Writes build report for a build interrupted by execution exception.
   *
   * <p>In case {@code keepGoing} flag is not set, the build terminates without having all build
   * results, but clients are still very much interested in finding out what exactly went wrong.
   * {@link BuildExecutionException} captures partial build execution result, which can still be
   * used to provide the most useful information about build result.
   */
  private void writePartialBuildReport(
      BuckEventBus eventBus, Path pathToBuildReport, BuildExecutionException e) {
    // Note that pathToBuildReport is an absolute path that may exist outside of the project
    // root, so it is not appropriate to use ProjectFilesystem to write the output.
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));
    BuildReport buildReport = new BuildReport(e.createBuildExecutionResult(), pathResolver);
    try {
      String jsonBuildReport = buildReport.generateJsonBuildReport();
      Files.write(jsonBuildReport, pathToBuildReport.toFile(), Charsets.UTF_8);
    } catch (IOException writeException) {
      LOG.warn(writeException, "Failed to write the build report to %s", pathToBuildReport);
      eventBus.post(ThrowableConsoleEvent.create(e, "Failed writing report"));
    }
  }

  @Override
  public void close() {
    // As time goes by, we add and remove things from this close() method. Instead of having to move
    // Build instances in and out of try-with-resources blocks, just keep the close() method even
    // when it doesn't do anything.
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractBuildExecutionResult {

    /**
     * @return Keys are build rules built during this invocation of Buck. Values reflect the success
     *     of each build rule, if it succeeded. ({@link Optional#empty()} represents a failed build
     *     rule.)
     */
    public abstract Map<BuildRule, Optional<BuildResult>> getResults();

    public abstract ImmutableSet<BuildResult> getFailures();
  }
}
