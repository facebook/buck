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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.HasAndroidPlatformTarget;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.graph.TraversableGraph;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.ImmutableBuildContext;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

public class Build implements Closeable {

  private static final Predicate<Optional<BuildRuleSuccess>> RULES_FAILED_PREDICATE =
      new Predicate<Optional<BuildRuleSuccess>>() {
        @Override
        public boolean apply(Optional<BuildRuleSuccess> input) {
          return !input.isPresent();
        }
      };

  private final ActionGraph actionGraph;

  private final ExecutionContext executionContext;

  private final ArtifactCache artifactCache;

  private final BuildEngine buildEngine;

  private final DefaultStepRunner stepRunner;

  private final JavaPackageFinder javaPackageFinder;

  private final BuildDependencies buildDependencies;

  private final Clock clock;

  /** Not set until {@link #executeBuild(Iterable, boolean)} is invoked. */
  @Nullable
  private BuildContext buildContext;

  /**
   * @param buildDependencies How to include dependencies when building rules.
   */
  public Build(
      ActionGraph actionGraph,
      Optional<TargetDevice> targetDevice,
      ProjectFilesystem projectFilesystem,
      AndroidDirectoryResolver androidDirectoryResolver,
      BuildEngine buildEngine,
      ArtifactCache artifactCache,
      int numThreads,
      JavaPackageFinder javaPackageFinder,
      Console console,
      long defaultTestTimeoutMillis,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled,
      BuildDependencies buildDependencies,
      BuckEventBus eventBus,
      Platform platform,
      ImmutableMap<String, String> environment,
      BuckConfig buckConfig,
      ObjectMapper objectMapper,
      Clock clock) {
    this.actionGraph = actionGraph;

    Optional<AndroidPlatformTarget> androidPlatformTarget = findAndroidPlatformTarget(
        actionGraph,
        androidDirectoryResolver,
        eventBus,
        buckConfig);
    this.executionContext = ExecutionContext.builder()
        .setProjectFilesystem(projectFilesystem)
        .setConsole(console)
        .setAndroidPlatformTarget(androidPlatformTarget)
        .setTargetDevice(targetDevice)
        .setDefaultTestTimeoutMillis(defaultTestTimeoutMillis)
        .setCodeCoverageEnabled(isCodeCoverageEnabled)
        .setDebugEnabled(isDebugEnabled)
        .setEventBus(eventBus)
        .setPlatform(platform)
        .setEnvironment(environment)
        .setJavaPackageFinder(javaPackageFinder)
        .setObjectMapper(objectMapper)
        .build();
    this.artifactCache = artifactCache;
    this.buildEngine = buildEngine;
    this.stepRunner = new DefaultStepRunner(executionContext, numThreads);
    this.javaPackageFinder = javaPackageFinder;
    this.buildDependencies = buildDependencies;
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

  public static Optional<AndroidPlatformTarget> findAndroidPlatformTarget(
      final ActionGraph actionGraph,
      final AndroidDirectoryResolver androidDirectoryResolver,
      final BuckEventBus eventBus,
      final BuckConfig buckConfig) {
    Optional<Path> androidSdkDirOption =
        androidDirectoryResolver.findAndroidSdkDirSafe();
    if (!androidSdkDirOption.isPresent()) {
      return Optional.absent();
    }

    // Traverse the action graph to determine androidPlatformTarget.
    TraversableGraph<BuildRule> graph = actionGraph;
    AbstractBottomUpTraversal<BuildRule, Optional<AndroidPlatformTarget>> traversal =
        new AbstractBottomUpTraversal<BuildRule, Optional<AndroidPlatformTarget>>(graph) {

      @Nullable
      private String androidPlatformTargetId = null;

      @Nullable
      private String androidPlatformTargetIdRuleName = null;

      private boolean isEncounteredAndroidRuleInTraversal = false;

      @Override
      public void visit(BuildRule rule) {
        if (rule.getProperties().is(ANDROID)) {
          isEncounteredAndroidRuleInTraversal = true;
        }

        if (rule instanceof HasAndroidPlatformTarget) {
          String target = ((HasAndroidPlatformTarget) rule).getAndroidPlatformTarget();
          if (androidPlatformTargetId == null) {
            androidPlatformTargetId = target;
            androidPlatformTargetIdRuleName = rule.getFullyQualifiedName();
          } else if (!target.equals(androidPlatformTargetId)) {
            throw new HumanReadableException(
                "More than one android platform targeted: '%s' and '%s'\n" +
                "Target originally set by %s, conflicting with %s",
                target,
                androidPlatformTargetId,
                androidPlatformTargetIdRuleName,
                rule.getFullyQualifiedName());
          }
        }
      }

      @Override
      public Optional<AndroidPlatformTarget> getResult() {
        // Find an appropriate AndroidPlatformTarget for the target attribute specified in one of
        // the transitively included android_binary() build rules. If no such target has been
        // specified, then use a default AndroidPlatformTarget so that it is possible to build
        // non-Android Java code, as well.
        Optional<AndroidPlatformTarget> result;
        if (androidPlatformTargetId != null) {
          Optional<AndroidPlatformTarget> target = AndroidPlatformTarget.getTargetForId(
              androidPlatformTargetId,
              androidDirectoryResolver,
              buckConfig.getAaptOverride());
          if (target.isPresent()) {
            result = target;
          } else {
            throw new RuntimeException("No target found with id: " + androidPlatformTargetId);
          }
        } else if (isEncounteredAndroidRuleInTraversal) {
          AndroidPlatformTarget androidPlatformTarget = AndroidPlatformTarget
              .getDefaultPlatformTarget(androidDirectoryResolver, buckConfig.getAaptOverride());
          eventBus.post(
              ConsoleEvent.warning("No Android platform target specified. Using default: %s",
                  androidPlatformTarget.getName()));
          result = Optional.of(androidPlatformTarget);
        } else {
          result = Optional.absent();
        }
        return result;
      }
    };
    traversal.traverse();
    return traversal.getResult();
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
  public LinkedHashMap<BuildRule, Optional<BuildRuleSuccess>> executeBuild(
      Iterable<? extends HasBuildTarget> targetish,
      boolean isKeepGoing)
      throws IOException, StepFailedException, ExecutionException, InterruptedException {
    buildContext = ImmutableBuildContext.builder()
        .setActionGraph(actionGraph)
        .setStepRunner(stepRunner)
        .setProjectFilesystem(executionContext.getProjectFilesystem())
        .setClock(clock)
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(javaPackageFinder)
        .setEventBus(executionContext.getBuckEventBus())
        .setAndroidBootclasspathSupplier(
            BuildContext.getAndroidBootclasspathSupplierForAndroidPlatformTarget(
                executionContext.getAndroidPlatformTargetOptional()))
        .setBuildDependencies(buildDependencies)
        .setBuildId(executionContext.getBuildId())
        .putAllEnvironment(executionContext.getEnvironment())
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
                               actionGraph.findBuildRuleByTarget(hasBuildTarget.getBuildTarget()));
                         }
                       })
            .toSet());

    // Calculate and post the number of rules that need to built.
    int numRules = getNumRulesToBuild(targetsToBuild, actionGraph);
    getExecutionContext().getBuckEventBus().post(
        BuildEvent.ruleCountCalculated(
            targetsToBuild,
            numRules));


    List<ListenableFuture<BuildRuleSuccess>> futures = FluentIterable.from(rulesToBuild)
        .transform(
        new Function<BuildRule, ListenableFuture<BuildRuleSuccess>>() {
          @Override
          public ListenableFuture<BuildRuleSuccess> apply(BuildRule rule) {
            return buildEngine.build(buildContext, rule);
          }
        }).toList();

    // Get the Future representing the build and then block until everything is built.
    ListenableFuture<List<BuildRuleSuccess>> buildFuture;
    if (isKeepGoing) {
      buildFuture = Futures.successfulAsList(futures);
    } else {
      buildFuture = Futures.allAsList(futures);
    }

    List<BuildRuleSuccess> results;
    try {
      results = buildFuture.get();
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
    LinkedHashMap<BuildRule, Optional<BuildRuleSuccess>> resultBuilder = new LinkedHashMap<>();

    Preconditions.checkState(rulesToBuild.size() == results.size());
    for (int i = 0, len = rulesToBuild.size(); i < len; i++) {
      BuildRule rule = rulesToBuild.get(i);
      BuildRuleSuccess success = results.get(i);
      resultBuilder.put(rule, Optional.fromNullable(success));
    }

    return resultBuilder;
  }

  public int executeAndPrintFailuresToConsole(
      Iterable<? extends HasBuildTarget> targetsish,
      boolean isKeepGoing,
      Console console,
      Optional<Path> pathToBuildReport) throws InterruptedException {
    int exitCode;

    try {
      LinkedHashMap<BuildRule, Optional<BuildRuleSuccess>> ruleToResult = executeBuild(
          targetsish,
          isKeepGoing);

      BuildReport buildReport = new BuildReport(ruleToResult);

      if (isKeepGoing) {
        String buildReportForConsole = buildReport.generateForConsole(console.getAnsi());
        console.getStdErr().print(buildReportForConsole);
        exitCode = Iterables.any(ruleToResult.values(), RULES_FAILED_PREDICATE) ? 1 : 0;
        if (exitCode != 0) {
          console.printBuildFailure("Not all rules succeeded.");
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
          e.printStackTrace(console.getStdErr());
          exitCode = 1;
        }
      }
    } catch (IOException e) {
      console.printBuildFailureWithoutStacktrace(e);
      exitCode = 1;
    } catch (StepFailedException e) {
      console.printBuildFailureWithoutStacktrace(e);
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
          console.printBuildFailureWithStacktrace(e);
        } else {
          console.printBuildFailureWithoutStacktrace(e);
        }
        exitCode = 1;
      }
    }

    return exitCode;
  }

  @Override
  public void close() throws IOException {
    stepRunner.close();
    executionContext.close();
  }

  private int getNumRulesToBuild(
      Iterable<BuildTarget> buildTargets,
      final ActionGraph actionGraph) {
    Set<BuildRule> baseBuildRules = FluentIterable
        .from(buildTargets)
        .transform(new Function<HasBuildTarget, BuildRule>() {
                     @Override
                     public BuildRule apply(HasBuildTarget hasBuildTarget) {
                       return Preconditions.checkNotNull(
                           actionGraph.findBuildRuleByTarget(hasBuildTarget.getBuildTarget()));
                     }
                   })
        .toSet();

    Set<BuildRule> allBuildRules = Sets.newHashSet();
    for (BuildRule rule : baseBuildRules) {
      addTransitiveDepsForRule(rule, allBuildRules);
    }
    allBuildRules.addAll(baseBuildRules);
    return allBuildRules.size();
  }

  private static void addTransitiveDepsForRule(
      BuildRule buildRule,
      Set<BuildRule> transitiveDeps) {
    ImmutableSortedSet<BuildRule> deps = buildRule.getDeps();
    if (deps.isEmpty()) {
      return;
    }
    for (BuildRule dep : deps) {
      if (!transitiveDeps.contains(dep)) {
        transitiveDeps.add(dep);
        addTransitiveDepsForRule(dep, transitiveDeps);
      }
    }
  }
}
