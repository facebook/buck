/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasMavenCoordinates;
import com.facebook.buck.jvm.core.HasSources;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link BuildRule} used to have the provided {@link JavaLibrary} published to a maven repository
 *
 * @see #create
 */
public class MavenUberJar extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements MavenPublishable {

  private final Optional<String> mavenCoords;
  private final Optional<SourcePath> mavenPomTemplate;
  private final TraversedDeps traversedDeps;

  private MavenUberJar(
      TraversedDeps traversedDeps,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<String> mavenCoords,
      Optional<SourcePath> mavenPomTemplate) {
    super(buildTarget, projectFilesystem, params);
    this.traversedDeps = traversedDeps;
    this.mavenCoords = mavenCoords;
    this.mavenPomTemplate = mavenPomTemplate;
  }

  private static BuildRuleParams adjustParams(BuildRuleParams params, TraversedDeps traversedDeps) {
    return params
        .withDeclaredDeps(ImmutableSortedSet.copyOf(Ordering.natural(), traversedDeps.packagedDeps))
        .withoutExtraDeps();
  }

  /**
   * Will traverse transitive dependencies of {@code rootRule}, separating those that do and don't
   * have maven coordinates. Those that do will be considered maven-external dependencies. They will
   * be returned by {@link #getMavenDeps} and will end up being specified as dependencies in
   * pom.xml. Others will be packaged in the same jar as if they are just a part of the one
   * published item.
   */
  public static MavenUberJar create(
      JavaLibrary rootRule,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Optional<String> mavenCoords,
      Optional<SourcePath> mavenPomTemplate) {
    TraversedDeps traversedDeps = TraversedDeps.traverse(ImmutableSet.of(rootRule));
    return new MavenUberJar(
        traversedDeps,
        buildTarget,
        projectFilesystem,
        adjustParams(params, traversedDeps),
        mavenCoords,
        mavenPomTemplate);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path pathToOutput = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());
    MkdirStep mkOutputDirStep =
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToOutput.getParent()));
    JarDirectoryStep mergeOutputsStep =
        new JarDirectoryStep(
            getProjectFilesystem(),
            JarParameters.builder()
                .setJarPath(pathToOutput)
                .setEntriesToJar(
                    toOutputPaths(context.getSourcePathResolver(), traversedDeps.packagedDeps))
                .setMergeManifests(true)
                .build());
    return ImmutableList.of(mkOutputDirStep, mergeOutputsStep);
  }

  private static ImmutableSortedSet<Path> toOutputPaths(
      SourcePathResolver pathResolver, Iterable<? extends BuildRule> rules) {
    return RichStream.from(rules)
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .map(pathResolver::getAbsolutePath)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        DefaultJavaLibrary.getOutputJarPath(getBuildTarget(), getProjectFilesystem()));
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public Optional<SourcePath> getPomTemplate() {
    return mavenPomTemplate;
  }

  @Override
  public Iterable<HasMavenCoordinates> getMavenDeps() {
    return traversedDeps.mavenDeps;
  }

  @Override
  public Iterable<BuildRule> getPackagedDependencies() {
    return traversedDeps.packagedDeps;
  }

  public static class SourceJar extends JavaSourceJar implements MavenPublishable {

    private final TraversedDeps traversedDeps;
    private final Optional<SourcePath> mavenPomTemplate;

    public SourceJar(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        ImmutableSortedSet<SourcePath> srcs,
        Optional<String> mavenCoords,
        Optional<SourcePath> mavenPomTemplate,
        TraversedDeps traversedDeps) {
      super(buildTarget, projectFilesystem, params, srcs, mavenCoords);
      this.traversedDeps = traversedDeps;
      this.mavenPomTemplate = mavenPomTemplate;
    }

    public static SourceJar create(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        ImmutableSortedSet<SourcePath> topLevelSrcs,
        Optional<String> mavenCoords,
        Optional<SourcePath> mavenPomTemplate) {
      TraversedDeps traversedDeps = TraversedDeps.traverse(params.getBuildDeps());

      params = adjustParams(params, traversedDeps);

      ImmutableSortedSet<SourcePath> sourcePaths =
          FluentIterable.from(traversedDeps.packagedDeps)
              .filter(HasSources.class)
              .transformAndConcat(HasSources::getSources)
              .append(topLevelSrcs)
              .toSortedSet(Ordering.natural());
      return new SourceJar(
          buildTarget,
          projectFilesystem,
          params,
          sourcePaths,
          mavenCoords,
          mavenPomTemplate,
          traversedDeps);
    }

    @Override
    public Optional<SourcePath> getPomTemplate() {
      return mavenPomTemplate;
    }

    @Override
    public Iterable<HasMavenCoordinates> getMavenDeps() {
      return traversedDeps.mavenDeps;
    }

    @Override
    public Iterable<BuildRule> getPackagedDependencies() {
      return traversedDeps.packagedDeps;
    }
  }

  private static class TraversedDeps {
    public final Iterable<HasMavenCoordinates> mavenDeps;
    public final Iterable<BuildRule> packagedDeps;

    private TraversedDeps(
        Iterable<HasMavenCoordinates> mavenDeps, Iterable<BuildRule> packagedDeps) {
      this.mavenDeps = mavenDeps;
      this.packagedDeps = packagedDeps;
    }

    private static TraversedDeps traverse(Set<? extends BuildRule> roots) {
      ImmutableSortedSet.Builder<HasMavenCoordinates> depsCollector =
          ImmutableSortedSet.naturalOrder();

      ImmutableSortedSet.Builder<JavaLibrary> candidates = ImmutableSortedSet.naturalOrder();
      for (BuildRule root : roots) {
        Preconditions.checkState(root instanceof HasClasspathEntries);
        candidates.addAll(
            ((HasClasspathEntries) root)
                .getTransitiveClasspathDeps()
                .stream()
                .filter(buildRule -> !root.equals(buildRule))
                .iterator());
      }
      ImmutableSortedSet.Builder<JavaLibrary> removals = ImmutableSortedSet.naturalOrder();
      for (JavaLibrary javaLibrary : candidates.build()) {
        if (HasMavenCoordinates.isMavenCoordsPresent(javaLibrary)) {
          depsCollector.add(javaLibrary);
          removals.addAll(javaLibrary.getTransitiveClasspathDeps());
        }
      }

      return new TraversedDeps(
          /* mavenDeps */ depsCollector.build(),
          /* packagedDeps */ Sets.union(
              roots, Sets.difference(candidates.build(), removals.build())));
    }
  }
}
