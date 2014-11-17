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

import com.facebook.buck.android.HasAndroidPlatformTarget;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.graph.TraversableGraph;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

public class Build implements Closeable {

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
   * @param environment
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
          } else if (!target.equals(androidPlatformTargetId)) {
            throw new RuntimeException(
                String.format("More than one android platform targeted: %s and %s",
                    target,
                    androidPlatformTargetId));
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
   * @param rulesToBuild The rules to build. All build rules in this iterable must be unique.
   */
  public ListenableFuture<List<BuildRuleSuccess>> executeBuild(
      Iterable<BuildRule> rulesToBuild,
      boolean isKeepGoing)
      throws IOException, StepFailedException {
    buildContext = BuildContext.builder()
        .setActionGraph(actionGraph)
        .setStepRunner(stepRunner)
        .setProjectFilesystem(executionContext.getProjectFilesystem())
        .setClock(clock)
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(javaPackageFinder)
        .setEventBus(executionContext.getBuckEventBus())
        .setAndroidBootclasspathForAndroidPlatformTarget(
            executionContext.getAndroidPlatformTargetOptional())
        .setBuildDependencies(buildDependencies)
        .setBuildId(executionContext.getBuildId())
        .setEnvironment(executionContext.getEnvironment())
        .build();

    Iterable<ListenableFuture<BuildRuleSuccess>> futures = Iterables.transform(
        rulesToBuild,
        new Function<BuildRule, ListenableFuture<BuildRuleSuccess>>() {
          @Override
          public ListenableFuture<BuildRuleSuccess> apply(BuildRule rule) {
            return buildEngine.build(buildContext, rule);
          }
        });
    if (isKeepGoing) {
      return Futures.successfulAsList(futures);
    } else {
      return Futures.allAsList(futures);
    }
  }

  @Override
  public void close() throws IOException {
    stepRunner.close();
  }
}
