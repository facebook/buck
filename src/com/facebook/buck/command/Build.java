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

import com.facebook.buck.debug.Tracer;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildEvents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.Builder;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.android.HasAndroidPlatformTarget;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.Verbosity;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

public class Build {

  private final DependencyGraph dependencyGraph;

  private final ExecutionContext executionContext;

  private final StepRunner stepRunner;

  private final JavaPackageFinder javaPackageFinder;

  private final BuildDependencies buildDependencies;

  /** Not set until {@link #executeBuild(EventBus)} is invoked. */
  @Nullable
  private BuildContext buildContext;

  /**
   * @param androidSdkDir where the user's Android SDK is installed.
   * @param buildDependencies How to include dependencies when building rules.
   */
  public Build(
      DependencyGraph dependencyGraph,
      Optional<File> androidSdkDir,
      Optional<File> ndkRoot,
      File projectDirectoryRoot,
      Verbosity verbosity,
      ListeningExecutorService listeningExecutorService,
      JavaPackageFinder javaPackageFinder,
      Ansi ansi,
      PrintStream stdOut,
      PrintStream stdErr,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled,
      BuildDependencies buildDependencies) {
    this.dependencyGraph = Preconditions.checkNotNull(dependencyGraph);

    Optional<AndroidPlatformTarget> androidPlatformTarget = findAndroidPlatformTarget(
        dependencyGraph, androidSdkDir, stdErr);
    this.executionContext = new ExecutionContext(
        verbosity,
        projectDirectoryRoot,
        androidPlatformTarget,
        ndkRoot,
        ansi,
        isCodeCoverageEnabled,
        isDebugEnabled,
        stdOut,
        stdErr);
    this.stepRunner = new DefaultStepRunner(executionContext, listeningExecutorService);
    this.javaPackageFinder = Preconditions.checkNotNull(javaPackageFinder);
    this.buildDependencies = Preconditions.checkNotNull(buildDependencies);
  }

  public DependencyGraph getDependencyGraph() {
    return dependencyGraph;
  }

  public ExecutionContext getExecutionContext() {
    return executionContext;
  }

  public StepRunner getCommandRunner() {
    return stepRunner;
  }

  /** Returns null until {@link #executeBuild(EventBus)} is invoked. */
  @Nullable
  public BuildContext getBuildContext() {
    return buildContext;
  }

  public static Optional<AndroidPlatformTarget> findAndroidPlatformTarget(
      DependencyGraph dependencyGraph, Optional<File> androidSdkDirOption, PrintStream stdErr) {
    if (androidSdkDirOption.isPresent()) {
      File androidSdkDir = androidSdkDirOption.get();
      return findAndroidPlatformTarget(dependencyGraph, androidSdkDir, stdErr);
    } else {
      // If the Android SDK has not been specified, then no AndroidPlatformTarget can be found.
      return Optional.<AndroidPlatformTarget>absent();
    }
  }

  private static Optional<AndroidPlatformTarget> findAndroidPlatformTarget(
      final DependencyGraph dependencyGraph, final File androidSdkDir, final PrintStream stdErr) {
    // Traverse the dependency graph to determine androidPlatformTarget.
    AbstractBottomUpTraversal<BuildRule, Optional<AndroidPlatformTarget>> traversal =
        new AbstractBottomUpTraversal<BuildRule, Optional<AndroidPlatformTarget>>(dependencyGraph) {

      private String androidPlatformTargetId = null;

      private boolean isEncounteredAndroidRuleInTraversal = false;

      @Override
      public void visit(BuildRule rule) {
        if (rule.isAndroidRule()) {
          isEncounteredAndroidRuleInTraversal = true;
        }

        if (rule instanceof HasAndroidPlatformTarget) {
          String target = ((HasAndroidPlatformTarget)rule).getAndroidPlatformTarget();
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
        // Find an appropriate AndroidPlatformTarget for the target attribute specified in one of the
        // transitively included android_binary() build rules. If no such target has been specified,
        // then use a default AndroidPlatformTarget so that it is possible to build non-Android Java
        // code, as well.
        Optional<AndroidPlatformTarget> result;
        if (androidPlatformTargetId != null) {
          Optional<AndroidPlatformTarget> target = AndroidPlatformTarget.getTargetForId(
              androidPlatformTargetId, androidSdkDir);
          if (target.isPresent()) {
            result = target;
          } else {
            throw new RuntimeException("No target found with id: " + androidPlatformTargetId);
          }
        } else if (isEncounteredAndroidRuleInTraversal) {
          // Print the start of the message first in case getDefaultPlatformTarget() fails.
          stdErr.print("No Android platform target specified. Using default: ");
          AndroidPlatformTarget androidPlatformTarget = AndroidPlatformTarget
              .getDefaultPlatformTarget(androidSdkDir);
          stdErr.println(androidPlatformTarget.getName());
          result = Optional.of(androidPlatformTarget);
        } else {
          result = Optional.absent();
        }

        Tracer.addComment("Android platform target determined.");
        return result;
      }
    };
    traversal.traverse();
    return traversal.getResult();
  }

  public ListenableFuture<List<BuildRuleSuccess>> executeBuild(EventBus events)
      throws IOException, StepFailedException {
    events.post(BuildEvents.started());
    Set<BuildRule> rulesToBuild = dependencyGraph.getNodesWithNoIncomingEdges();

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(
        executionContext.getProjectDirectoryRoot());

    buildContext = BuildContext.builder()
        .setProjectRoot(executionContext.getProjectDirectoryRoot())
        .setDependencyGraph(dependencyGraph)
        .setCommandRunner(stepRunner)
        .setProjectFilesystem(projectFilesystem)
        .setJavaPackageFinder(javaPackageFinder)
        .setEventBus(events)
        .setAndroidBootclasspathForAndroidPlatformTarget(
            executionContext.getAndroidPlatformTargetOptional())
        .setBuildDependencies(buildDependencies)
        .build();

    return Builder.getInstance().buildRules(rulesToBuild, buildContext);
  }
}
