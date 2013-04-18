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
import com.facebook.buck.rules.HasAndroidPlatformTarget;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.shell.CommandFailedException;
import com.facebook.buck.shell.CommandRunner;
import com.facebook.buck.shell.DefaultCommandRunner;
import com.facebook.buck.shell.ExecutionContext;
import com.facebook.buck.shell.Verbosity;
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

  private final CommandRunner commandRunner;

  private final JavaPackageFinder javaPackageFinder;

  /** Not set until {@link #executeBuild(EventBus)} is invoked. */
  @Nullable
  private BuildContext buildContext;

  /**
   * @param androidSdkDir where the user's Android SDK is installed.
   */
  public Build(
      DependencyGraph dependencyGraph,
      File androidSdkDir,
      Optional<File> ndkRoot,
      File projectDirectoryRoot,
      Verbosity verbosity,
      ListeningExecutorService listeningExecutorService,
      JavaPackageFinder javaPackageFinder,
      Ansi ansi,
      PrintStream stdOut,
      PrintStream stdErr,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled) {
    Preconditions.checkNotNull(androidSdkDir);
    Preconditions.checkArgument(androidSdkDir.exists());
    Preconditions.checkArgument(androidSdkDir.isDirectory());
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
    this.commandRunner = new DefaultCommandRunner(executionContext, listeningExecutorService);
    this.javaPackageFinder = Preconditions.checkNotNull(javaPackageFinder);
  }

  public DependencyGraph getDependencyGraph() {
    return dependencyGraph;
  }

  public ExecutionContext getExecutionContext() {
    return executionContext;
  }

  public CommandRunner getCommandRunner() {
    return commandRunner;
  }

  /** Returns null until {@link #executeBuild(EventBus)} is invoked. */
  @Nullable
  public BuildContext getBuildContext() {
    return buildContext;
  }

  public static Optional<AndroidPlatformTarget> findAndroidPlatformTarget(
      DependencyGraph dependencyGraph, final File androidSdkDir, final PrintStream stdErr) {
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
      throws IOException, CommandFailedException {
    events.post(BuildEvents.started());
    Set<BuildRule> rulesToBuild = dependencyGraph.getNodesWithNoIncomingEdges();

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(
        executionContext.getProjectDirectoryRoot());

    buildContext = BuildContext.builder()
        .setProjectRoot(executionContext.getProjectDirectoryRoot())
        .setDependencyGraph(dependencyGraph)
        .setCommandRunner(commandRunner)
        .setProjectFilesystem(projectFilesystem)
        .setJavaPackageFinder(javaPackageFinder)
        .setEventBus(events)
        .setAndroidBootclasspathForAndroidPlatformTarget(executionContext.getAndroidPlatformTarget())
        .build();

    return Builder.getInstance().buildRules(rulesToBuild, buildContext);
  }
}
