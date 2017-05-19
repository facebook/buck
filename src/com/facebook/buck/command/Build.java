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

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.BuckPaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEngineBuildContext;
import com.facebook.buck.rules.BuildEngineResult;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.RuleKeyDiagnosticsMode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.shell.WorkerProcessPool;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.immutables.value.Value;

public class Build implements Closeable {

  private static final Logger LOG = Logger.get(Build.class);

  private final ActionGraph actionGraph;
  private final BuildRuleResolver ruleResolver;
  private final Cell rootCell;
  private final ExecutionContext executionContext;
  private final ArtifactCache artifactCache;
  private final BuildEngine buildEngine;
  private final JavaPackageFinder javaPackageFinder;
  private final Clock clock;

  public Build(
      ActionGraph actionGraph,
      BuildRuleResolver ruleResolver,
      Cell rootCell,
      Optional<TargetDevice> targetDevice,
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier,
      BuildEngine buildEngine,
      ArtifactCache artifactCache,
      JavaPackageFinder javaPackageFinder,
      Console console,
      long defaultTestTimeoutMillis,
      boolean isCodeCoverageEnabled,
      boolean isInclNoLocationClassesEnabled,
      boolean isDebugEnabled,
      boolean shouldReportAbsolutePaths,
      RuleKeyDiagnosticsMode ruleKeyDiagnosticsMode,
      BuckEventBus eventBus,
      Platform platform,
      ImmutableMap<String, String> environment,
      Clock clock,
      ConcurrencyLimit concurrencyLimit,
      Optional<AdbOptions> adbOptions,
      Optional<TargetDeviceOptions> targetDeviceOptions,
      Optional<ConcurrentMap<String, WorkerProcessPool>> persistentWorkerPools,
      ProcessExecutor processExecutor,
      Map<ExecutorPool, ListeningExecutorService> executors) {
    this.actionGraph = actionGraph;
    this.ruleResolver = ruleResolver;
    this.rootCell = rootCell;
    this.executionContext =
        ExecutionContext.builder()
            .setConsole(console)
            .setAndroidPlatformTargetSupplier(androidPlatformTargetSupplier)
            .setTargetDevice(targetDevice)
            .setDefaultTestTimeoutMillis(defaultTestTimeoutMillis)
            .setCodeCoverageEnabled(isCodeCoverageEnabled)
            .setInclNoLocationClassesEnabled(isInclNoLocationClassesEnabled)
            .setDebugEnabled(isDebugEnabled)
            .setRuleKeyDiagnosticsMode(ruleKeyDiagnosticsMode)
            .setShouldReportAbsolutePaths(shouldReportAbsolutePaths)
            .setBuckEventBus(eventBus)
            .setPlatform(platform)
            .setEnvironment(environment)
            .setJavaPackageFinder(javaPackageFinder)
            .setConcurrencyLimit(concurrencyLimit)
            .setAdbOptions(adbOptions)
            .setPersistentWorkerPools(persistentWorkerPools)
            .setTargetDeviceOptions(targetDeviceOptions)
            .setExecutors(executors)
            .setCellPathResolver(rootCell.getCellPathResolver())
            .setProcessExecutor(processExecutor)
            .build();
    this.artifactCache = artifactCache;
    this.buildEngine = buildEngine;
    this.javaPackageFinder = javaPackageFinder;
    this.clock = clock;
  }

  public BuildRuleResolver getRuleResolver() {
    return ruleResolver;
  }

  public ExecutionContext getExecutionContext() {
    return executionContext;
  }

  /**
   * When the user overrides the configured buck-out directory via the `.buckconfig` and also sets
   * the `project.buck_out_compat_link` setting to `true`, we symlink the original output path
   * (`buck-out/`) to this newly configured location for backwards compatibility.
   */
  private void createConfiguredBuckOutSymlinks() throws IOException {
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
  }

  /**
   * If {@code isKeepGoing} is false, then this returns a future that succeeds only if all of {@code
   * rulesToBuild} build successfully. Otherwise, this returns a future that should always succeed,
   * even if individual rules fail to build. In that case, a failed build rule is indicated by a
   * {@code null} value in the corresponding position in the iteration order of {@code
   * rulesToBuild}.
   *
   * @param targetish The targets to build. All targets in this iterable must be unique.
   */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public BuildExecutionResult executeBuild(
      Iterable<? extends BuildTarget> targetish, boolean isKeepGoing)
      throws IOException, ExecutionException, InterruptedException {
    BuildId buildId = executionContext.getBuildId();
    BuildEngineBuildContext buildContext =
        BuildEngineBuildContext.builder()
            .setBuildContext(
                BuildContext.builder()
                    .setActionGraph(actionGraph)
                    .setSourcePathResolver(
                        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver)))
                    .setJavaPackageFinder(javaPackageFinder)
                    .setEventBus(executionContext.getBuckEventBus())
                    .setAndroidPlatformTargetSupplier(
                        executionContext.getAndroidPlatformTargetSupplier())
                    .build())
            .setClock(clock)
            .setArtifactCache(artifactCache)
            .setBuildId(buildId)
            .putAllEnvironment(executionContext.getEnvironment())
            .setKeepGoing(isKeepGoing)
            .build();

    // It is important to use this logic to determine the set of rules to build rather than
    // build.getActionGraph().getNodesWithNoIncomingEdges() because, due to graph enhancement,
    // there could be disconnected subgraphs in the DependencyGraph that we do not want to build.
    ImmutableSet<BuildTarget> targetsToBuild =
        StreamSupport.stream(targetish.spliterator(), false)
            .collect(MoreCollectors.toImmutableSet());

    // It is important to use this logic to determine the set of rules to build rather than
    // build.getActionGraph().getNodesWithNoIncomingEdges() because, due to graph enhancement,
    // there could be disconnected subgraphs in the DependencyGraph that we do not want to build.
    ImmutableList<BuildRule> rulesToBuild =
        ImmutableList.copyOf(
            targetsToBuild
                .stream()
                .map(
                    buildTarget -> {
                      try {
                        return getRuleResolver().requireRule(buildTarget);
                      } catch (NoSuchBuildTargetException e) {
                        throw new HumanReadableException(
                            "No build rule found for target %s", buildTarget);
                      }
                    })
                .collect(MoreCollectors.toImmutableSet()));

    // Calculate and post the number of rules that need to built.
    int numRules = buildEngine.getNumRulesToBuild(rulesToBuild);
    getExecutionContext()
        .getBuckEventBus()
        .post(BuildEvent.ruleCountCalculated(targetsToBuild, numRules));

    // Setup symlinks required when configuring the output path.
    createConfiguredBuckOutSymlinks();

    List<BuildEngineResult> futures =
        rulesToBuild
            .stream()
            .map(rule -> buildEngine.build(buildContext, executionContext, rule))
            .collect(MoreCollectors.toImmutableList());

    // Get the Future representing the build and then block until everything is built.
    ListenableFuture<List<BuildResult>> buildFuture =
        Futures.allAsList(
            futures.stream().map(BuildEngineResult::getResult).collect(Collectors.toList()));
    List<BuildResult> results;
    try {
      results = buildFuture.get();
      if (!isKeepGoing) {
        for (BuildResult result : results) {
          Throwable thrown = result.getFailure();
          if (thrown != null) {
            throw new ExecutionException(thrown);
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
        } catch (CancellationException ignored) {
          // Rethrow original InterruptedException instead.
        }
        Thread.currentThread().interrupt();
      }
      throw e;
    }

    // Insertion order matters
    LinkedHashMap<BuildRule, Optional<BuildResult>> resultBuilder = new LinkedHashMap<>();

    Preconditions.checkState(rulesToBuild.size() == results.size());
    for (int i = 0, len = rulesToBuild.size(); i < len; i++) {
      BuildRule rule = rulesToBuild.get(i);
      resultBuilder.put(rule, Optional.ofNullable(results.get(i)));
    }

    return BuildExecutionResult.builder()
        .setFailures(FluentIterable.from(results).filter(input -> input.getSuccess() == null))
        .setResults(resultBuilder)
        .build();
  }

  private String getFailureMessage(Throwable thrown) {
    return "BUILD FAILED: " + thrown.getMessage();
  }

  private String getFailureMessageWithClassName(Throwable thrown) {
    return "BUILD FAILED: " + thrown.getClass().getName() + " " + thrown.getMessage();
  }

  public int executeAndPrintFailuresToEventBus(
      Iterable<BuildTarget> targetsish,
      boolean isKeepGoing,
      BuckEventBus eventBus,
      Console console,
      Optional<Path> pathToBuildReport)
      throws InterruptedException {
    int exitCode;

    try {
      try {
        BuildExecutionResult buildExecutionResult = executeBuild(targetsish, isKeepGoing);

        SourcePathResolver pathResolver =
            new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
        BuildReport buildReport = new BuildReport(buildExecutionResult, pathResolver);

        if (isKeepGoing) {
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
          try {
            Files.write(jsonBuildReport, pathToBuildReport.get().toFile(), Charsets.UTF_8);
          } catch (IOException e) {
            eventBus.post(ThrowableConsoleEvent.create(e, "Failed writing report"));
            exitCode = 1;
          }
        }
      } catch (ExecutionException | RuntimeException e) {
        // This is likely a checked exception that was caught while building a build rule.
        Throwable cause = e.getCause();
        if (cause == null) {
          Throwables.throwIfInstanceOf(e, RuntimeException.class);
          throw new RuntimeException(e);
        }
        Throwables.throwIfInstanceOf(cause, IOException.class);
        Throwables.throwIfInstanceOf(cause, StepFailedException.class);
        Throwables.throwIfInstanceOf(cause, InterruptedException.class);
        Throwables.throwIfInstanceOf(cause, ClosedByInterruptException.class);
        Throwables.throwIfInstanceOf(cause, HumanReadableException.class);
        if (cause instanceof ExceptionWithHumanReadableMessage) {
          throw new HumanReadableException((ExceptionWithHumanReadableMessage) cause);
        }

        LOG.debug(e, "Got an exception during the build.");
        throw new RuntimeException(e);
      }
    } catch (IOException e) {
      LOG.debug(e, "Got an exception during the build.");
      eventBus.post(ConsoleEvent.severe(getFailureMessageWithClassName(e)));
      exitCode = 1;
    } catch (StepFailedException e) {
      LOG.debug(e, "Got an exception during the build.");
      eventBus.post(ConsoleEvent.severe(getFailureMessage(e)));
      exitCode = 1;
    }

    return exitCode;
  }

  @Override
  public void close() throws IOException {
    executionContext.close();
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
