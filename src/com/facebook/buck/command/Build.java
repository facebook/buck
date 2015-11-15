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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ImmutableBuildContext;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.immutables.value.Value;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

public class Build implements Closeable {

  private static final Predicate<BuildResult> RULES_FAILED_PREDICATE =
      new Predicate<BuildResult>() {
        @Override
        public boolean apply(BuildResult input) {
          return input.getSuccess() == null;
        }
      };

  private final ActionGraph actionGraph;

  private final ExecutionContext executionContext;

  private final ArtifactCache artifactCache;

  private final BuildEngine buildEngine;

  private final DefaultStepRunner stepRunner;

  private final JavaPackageFinder javaPackageFinder;

  private final Clock clock;

  /** Not set until {@link #executeBuild(Iterable, boolean)} is invoked. */
  @Nullable
  private BuildContext buildContext;

  public Build(
      ActionGraph actionGraph,
      Optional<TargetDevice> targetDevice,
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier,
      BuildEngine buildEngine,
      ArtifactCache artifactCache,
      JavaPackageFinder javaPackageFinder,
      Console console,
      long defaultTestTimeoutMillis,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled,
      boolean shouldReportAbsolutePaths,
      BuckEventBus eventBus,
      Platform platform,
      ImmutableMap<String, String> environment,
      ObjectMapper objectMapper,
      Clock clock,
      ConcurrencyLimit concurrencyLimit,
      Optional<AdbOptions> adbOptions,
      Optional<TargetDeviceOptions> targetDeviceOptions) {
    this.actionGraph = actionGraph;

    this.executionContext = ExecutionContext.builder()
        .setConsole(console)
        .setAndroidPlatformTargetSupplier(androidPlatformTargetSupplier)
        .setTargetDevice(targetDevice)
        .setDefaultTestTimeoutMillis(defaultTestTimeoutMillis)
        .setCodeCoverageEnabled(isCodeCoverageEnabled)
        .setDebugEnabled(isDebugEnabled)
        .setShouldReportAbsolutePaths(shouldReportAbsolutePaths)
        .setEventBus(eventBus)
        .setPlatform(platform)
        .setEnvironment(environment)
        .setJavaPackageFinder(javaPackageFinder)
        .setObjectMapper(objectMapper)
        .setConcurrencyLimit(concurrencyLimit)
        .setAdbOptions(adbOptions)
        .setTargetDeviceOptions(targetDeviceOptions)
        .build();
    this.artifactCache = artifactCache;
    this.buildEngine = buildEngine;
    this.stepRunner = new DefaultStepRunner(executionContext);
    this.javaPackageFinder = javaPackageFinder;
    this.clock = clock;
  }

  public ActionGraph getActionGraph() {
    return actionGraph;
  }

  public ExecutionContext getExecutionContext() {
    return executionContext;
  }

  /** Returns null until {@link #executeBuild(Iterable, boolean)} is invoked. */
  @Nullable
  public BuildContext getBuildContext() {
    return buildContext;
  }

  /**
   * If {@code isKeepGoing} is false, then this returns a future that succeeds only if all of
   * {@code rulesToBuild} build successfully. Otherwise, this returns a future that should always
   * succeed, even if individual rules fail to build. In that case, a failed build rule is indicated
   * by a {@code null} value in the corresponding position in the iteration order of
   * {@code rulesToBuild}.
   * @param targetish The targets to build. All targets in this iterable must be unique.
   */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public BuildExecutionResult executeBuild(
      Iterable<? extends HasBuildTarget> targetish,
      boolean isKeepGoing)
      throws IOException, StepFailedException, ExecutionException, InterruptedException {
    buildContext = ImmutableBuildContext.builder()
        .setActionGraph(actionGraph)
        .setStepRunner(stepRunner)
        .setClock(clock)
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(javaPackageFinder)
        .setEventBus(executionContext.getBuckEventBus())
        .setAndroidBootclasspathSupplier(
            BuildContext.createBootclasspathSupplier(
                executionContext.getAndroidPlatformTargetSupplier()))
        .setBuildId(executionContext.getBuildId())
        .putAllEnvironment(executionContext.getEnvironment())
        .setKeepGoing(isKeepGoing)
        .setShouldReportAbsolutePaths(executionContext.shouldReportAbsolutePaths())
        .build();

    ImmutableSet<BuildTarget> targetsToBuild = FluentIterable.from(targetish)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();

    // It is important to use this logic to determine the set of rules to build rather than
    // build.getActionGraph().getNodesWithNoIncomingEdges() because, due to graph enhancement,
    // there could be disconnected subgraphs in the DependencyGraph that we do not want to build.
    ImmutableList<BuildRule> rulesToBuild = ImmutableList.copyOf(
        FluentIterable
            .from(targetsToBuild)
            .transform(new Function<HasBuildTarget, BuildRule>() {
                         @Override
                         public BuildRule apply(HasBuildTarget hasBuildTarget) {
                           return Preconditions.checkNotNull(
                               actionGraph.findBuildRuleByTarget(hasBuildTarget.getBuildTarget()),
                               "No build rule found for target %s in %s",
                               hasBuildTarget.getBuildTarget(),
                               actionGraph);
                         }
                       })
            .toSet());

    // Calculate and post the number of rules that need to built.
    int numRules = buildEngine.getNumRulesToBuild(rulesToBuild);
    getExecutionContext().getBuckEventBus().post(
        BuildEvent.ruleCountCalculated(
            targetsToBuild,
            numRules));

    final BuildContext currentBuildContext = buildContext;
    List<ListenableFuture<BuildResult>> futures = FluentIterable.from(rulesToBuild)
        .transform(
        new Function<BuildRule, ListenableFuture<BuildResult>>() {
          @Override
          public ListenableFuture<BuildResult> apply(BuildRule rule) {
            return buildEngine.build(currentBuildContext, rule);
          }
        }).toList();

    // Get the Future representing the build and then block until everything is built.
    ListenableFuture<List<BuildResult>> buildFuture = Futures.allAsList(futures);
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
    } catch (InterruptedException e) {
      try {
        buildFuture.cancel(true);
      } catch (CancellationException ignored) {
        // Rethrow original InterruptedException instead.
      }
      Thread.currentThread().interrupt();
      throw e;
    }

    // Insertion order matters
    LinkedHashMap<BuildRule, Optional<BuildResult>> resultBuilder = new LinkedHashMap<>();

    Preconditions.checkState(rulesToBuild.size() == results.size());
    for (int i = 0, len = rulesToBuild.size(); i < len; i++) {
      BuildRule rule = rulesToBuild.get(i);
      resultBuilder.put(rule, Optional.fromNullable(results.get(i)));
    }

    return BuildExecutionResult.builder()
        .setFailures(FluentIterable.from(results).filter(RULES_FAILED_PREDICATE))
        .setResults(resultBuilder)
        .build();
  }

  private String getFailureMessage(Throwable thrown) {
    return "BUILD FAILED: " + thrown.getMessage();
  }

  public int executeAndPrintFailuresToEventBus(
      Iterable<? extends HasBuildTarget> targetsish,
      boolean isKeepGoing,
      BuckEventBus eventBus,
      Console console,
      Optional<Path> pathToBuildReport) throws InterruptedException {
    int exitCode;

    try {
      BuildExecutionResult buildExecutionResult = executeBuild(
          targetsish,
          isKeepGoing);

      BuildReport buildReport = new BuildReport(buildExecutionResult);

      if (isKeepGoing) {
        String buildReportText = buildReport.generateForConsole(console);
        // Remove trailing newline from build report.
        buildReportText = buildReportText.substring(0, buildReportText.length() - 1);
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
    } catch (IOException e) {
      eventBus.post(ConsoleEvent.severe(getFailureMessage(e)));
      exitCode = 1;
    } catch (StepFailedException e) {
      eventBus.post(ConsoleEvent.severe(getFailureMessage(e)));
      exitCode = e.getExitCode();
    } catch (ExecutionException e) {
      // This is likely a checked exception that was caught while building a build rule.
      Throwable cause = e.getCause();
      if (cause instanceof HumanReadableException) {
        throw ((HumanReadableException) cause);
      } else if (cause instanceof ExceptionWithHumanReadableMessage) {
        throw new HumanReadableException((ExceptionWithHumanReadableMessage) cause);
      } else {
        if (cause instanceof RuntimeException) {
          eventBus.post(ThrowableConsoleEvent.create(cause, getFailureMessage(cause)));
        } else {
          eventBus.post(ConsoleEvent.severe(getFailureMessage(cause)));
        }
        exitCode = 1;
      }
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
     * @return Keys are build rules built during this invocation of Buck. Values reflect
     * the success of each build rule, if it succeeded. ({@link Optional#absent()} represents a
     * failed build rule.)
     */
    public abstract Map<BuildRule, Optional<BuildResult>> getResults();
    public abstract ImmutableSet<BuildResult> getFailures();
  }

}
