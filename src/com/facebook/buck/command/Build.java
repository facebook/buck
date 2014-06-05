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
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.graph.TraversableGraph;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.Builder;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

public class Build implements Closeable {

  private final ActionGraph actionGraph;

  private final ExecutionContext executionContext;

  private final ArtifactCache artifactCache;

  private final BuildEngine buildEngine;

  private final DefaultStepRunner stepRunner;

  private final JavaPackageFinder javaPackageFinder;

  private final BuildDependencies buildDependencies;

  /** Not set until {@link #executeBuild(Set)} is invoked. */
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
      boolean isJacocoEnabled,
      boolean isDebugEnabled,
      BuildDependencies buildDependencies,
      BuckEventBus eventBus,
      Platform platform,
      ImmutableMap<String, String> environment,
      BuckConfig buckConfig) {
    this.actionGraph = Preconditions.checkNotNull(actionGraph);

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
        .setJacocoEnabled(isJacocoEnabled)
        .setDebugEnabled(isDebugEnabled)
        .setEventBus(eventBus)
        .setPlatform(platform)
        .setEnvironment(environment)
        .build();
    this.artifactCache = Preconditions.checkNotNull(artifactCache);
    this.buildEngine = Preconditions.checkNotNull(buildEngine);
    this.stepRunner = new DefaultStepRunner(executionContext, numThreads);
    this.javaPackageFinder = Preconditions.checkNotNull(javaPackageFinder);
    this.buildDependencies = Preconditions.checkNotNull(buildDependencies);
  }

  public ActionGraph getActionGraph() {
    return actionGraph;
  }

  public ExecutionContext getExecutionContext() {
    return executionContext;
  }

  /** Returns null until {@link #executeBuild(Set)} is invoked. */
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

      private String androidPlatformTargetId = null;

      private boolean isEncounteredAndroidRuleInTraversal = false;

      @Override
      public void visit(BuildRule rule) {
        if (rule.getProperties().is(ANDROID)) {
          isEncounteredAndroidRuleInTraversal = true;
        }

        Buildable buildable = rule.getBuildable();
        if (buildable != null && buildable instanceof HasAndroidPlatformTarget) {
          String target = ((HasAndroidPlatformTarget) buildable).getAndroidPlatformTarget();
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
          eventBus.post(LogEvent.warning("No Android platform target specified. Using default: %s",
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

  public ListenableFuture<List<BuildRuleSuccess>> executeBuild(
      Set<BuildRule> rulesToBuild)
      throws IOException, StepFailedException {
    buildContext = BuildContext.builder()
        .setActionGraph(actionGraph)
        .setStepRunner(stepRunner)
        .setProjectFilesystem(executionContext.getProjectFilesystem())
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(javaPackageFinder)
        .setEventBus(executionContext.getBuckEventBus())
        .setAndroidBootclasspathForAndroidPlatformTarget(
            executionContext.getAndroidPlatformTargetOptional())
        .setBuildDependencies(buildDependencies)
        .build();

    return Builder.getInstance().buildRules(buildEngine, rulesToBuild, buildContext);
  }

  @Override
  public void close() throws IOException {
    stepRunner.close();
  }
}
