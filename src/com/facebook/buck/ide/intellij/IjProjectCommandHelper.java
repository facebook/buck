/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.ide.intellij.aggregation.AggregationMode;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;

public class IjProjectCommandHelper {

  private final BuckEventBus buckEventBus;
  private final ProjectFilesystem projectFilesystem;
  private final Console console;
  private final BuckConfig buckConfig;
  private final ActionGraphCache actionGraphCache;
  private final boolean skipBuild;
  private final boolean build;
  private final AggregationMode intellijAggregationMode;
  private final String generatedFilesListFilename;
  private final boolean processAnnotations;
  private final boolean runIjCleaner;
  private final boolean removeUnusedLibraries;
  private final boolean excludeArtifacts;
  private final BuckBuildRunner buckBuildRunner;

  public IjProjectCommandHelper(
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      Console console,
      BuckConfig buckConfig,
      ActionGraphCache actionGraphCache,
      boolean skipBuild,
      boolean build,
      AggregationMode intellijAggregationMode,
      String generatedFilesListFilename,
      boolean processAnnotations,
      boolean runIjCleaner,
      boolean removeUnusedLibraries,
      boolean excludeArtifacts,
      BuckBuildRunner buckBuildRunner) {
    this.buckEventBus = buckEventBus;
    this.projectFilesystem = projectFilesystem;
    this.console = console;
    this.buckConfig = buckConfig;
    this.actionGraphCache = actionGraphCache;
    this.skipBuild = skipBuild;
    this.build = build;
    this.intellijAggregationMode = intellijAggregationMode;
    this.generatedFilesListFilename = generatedFilesListFilename;
    this.processAnnotations = processAnnotations;
    this.runIjCleaner = runIjCleaner;
    this.removeUnusedLibraries = removeUnusedLibraries;
    this.excludeArtifacts = excludeArtifacts;
    this.buckBuildRunner = buckBuildRunner;
  }

  /** Run intellij specific project generation actions. */
  public int runIntellijProjectGenerator(final TargetGraphAndTargets targetGraphAndTargets)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> requiredBuildTargets =
        writeProjectAndGetRequiredBuildTargets(targetGraphAndTargets);

    if (requiredBuildTargets.isEmpty()) {
      return 0;
    }

    boolean skipBuilds = skipBuild || getSkipBuildFromConfig() || !build;
    if (skipBuilds) {
      ConsoleEvent.severe(
          "Please remember to buck build --deep the targets you intent to work with.");
      return 0;
    }

    return processAnnotations
        ? buildRequiredTargetsWithoutUsingCacheForAnnotatedTargets(
            targetGraphAndTargets, requiredBuildTargets)
        : runBuild(requiredBuildTargets);
  }

  private ImmutableSet<BuildTarget> writeProjectAndGetRequiredBuildTargets(
      TargetGraphAndTargets targetGraphAndTargets) throws IOException {
    ActionGraphAndResolver result =
        Preconditions.checkNotNull(
            actionGraphCache.getActionGraph(
                buckEventBus,
                buckConfig.isActionGraphCheckingEnabled(),
                buckConfig.isSkipActionGraphCache(),
                targetGraphAndTargets.getTargetGraph(),
                buckConfig.getKeySeed()));

    BuildRuleResolver ruleResolver = result.getResolver();

    JavacOptions javacOptions = buckConfig.getView(JavaBuckConfig.class).getDefaultJavacOptions();

    IjProjectConfig projectConfig =
        IjProjectBuckConfig.create(
            buckConfig,
            intellijAggregationMode,
            generatedFilesListFilename,
            runIjCleaner,
            removeUnusedLibraries,
            excludeArtifacts);

    IjProject project =
        new IjProject(
            targetGraphAndTargets,
            getJavaPackageFinder(buckConfig),
            JavaFileParser.createJavaFileParser(javacOptions),
            ruleResolver,
            projectFilesystem,
            projectConfig);

    return project.write();
  }

  private int buildRequiredTargetsWithoutUsingCacheForAnnotatedTargets(
      TargetGraphAndTargets targetGraphAndTargets, ImmutableSet<BuildTarget> requiredBuildTargets)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> annotatedTargets =
        getTargetsWithAnnotations(targetGraphAndTargets.getTargetGraph(), requiredBuildTargets);

    ImmutableSet<BuildTarget> unannotatedTargets =
        Sets.difference(requiredBuildTargets, annotatedTargets).immutableCopy();

    int exitCode = runBuild(unannotatedTargets);
    if (exitCode != 0) {
      addBuildFailureError();
    }

    if (annotatedTargets.isEmpty()) {
      return exitCode;
    }

    int annotationExitCode = buckBuildRunner.runBuild(annotatedTargets, true);
    if (exitCode == 0 && annotationExitCode != 0) {
      addBuildFailureError();
    }

    return exitCode == 0 ? annotationExitCode : exitCode;
  }

  private int runBuild(ImmutableSet<BuildTarget> targets) throws IOException, InterruptedException {
    return buckBuildRunner.runBuild(targets, false);
  }

  private ImmutableSet<BuildTarget> getTargetsWithAnnotations(
      final TargetGraph targetGraph, ImmutableSet<BuildTarget> buildTargets) {
    return buildTargets
        .stream()
        .filter(
            input -> {
              TargetNode<?, ?> targetNode = targetGraph.get(input);
              return targetNode != null && isTargetWithAnnotations(targetNode);
            })
        .collect(MoreCollectors.toImmutableSet());
  }

  private void addBuildFailureError() {
    console
        .getAnsi()
        .printHighlightedSuccessText(
            console.getStdErr(),
            "Because the build did not complete successfully some parts of the project may not\n"
                + "work correctly with IntelliJ. Please fix the errors and run this command again.\n");
  }

  private static boolean isTargetWithAnnotations(TargetNode<?, ?> target) {
    if (target.getDescription() instanceof JavaLibraryDescription) {
      return false;
    }
    JavaLibraryDescription.Arg arg = ((JavaLibraryDescription.Arg) target.getConstructorArg());
    return !arg.annotationProcessors.isEmpty();
  }

  private boolean getSkipBuildFromConfig() {
    return buckConfig.getBooleanValue("project", "skip_build", false);
  }

  public JavaPackageFinder getJavaPackageFinder(BuckConfig buckConfig) {
    return buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder();
  }
}
