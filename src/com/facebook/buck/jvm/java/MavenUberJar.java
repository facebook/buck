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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * A {@link BuildRule} used to have the provided {@link JavaLibrary} published to a maven repository
 *
 * @see #create
 */
public class MavenUberJar extends AbstractBuildRule implements MavenPublishable {

  private final Optional<String> mavenCoords;
  private final Optional<SourcePath> mavenPomTemplate;
  private final TraversedDeps traversedDeps;

  private MavenUberJar(
      TraversedDeps traversedDeps,
      BuildRuleParams params,
      SourcePathResolver resolver,
      Optional<String> mavenCoords,
      Optional<SourcePath> mavenPomTemplate) {
    super(params, resolver);
    this.traversedDeps = traversedDeps;
    this.mavenCoords = mavenCoords;
    this.mavenPomTemplate = mavenPomTemplate;
  }

  private static BuildRuleParams adjustParams(BuildRuleParams params, TraversedDeps traversedDeps) {
    return params.copyWithDeps(
        FluentIterable.from(traversedDeps.packagedDeps)
            .toSortedSet(Ordering.<BuildRule>natural()),
        ImmutableSortedSet.<BuildRule>of());
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
      BuildRuleParams params,
      SourcePathResolver resolver,
      Optional<String> mavenCoords,
      Optional<SourcePath> mavenPomTemplate) {
    TraversedDeps traversedDeps = TraversedDeps.traverse(ImmutableSet.of(rootRule));
    return new MavenUberJar(
        traversedDeps,
        adjustParams(params, traversedDeps),
        resolver,
        mavenCoords,
        mavenPomTemplate);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path pathToOutput = getPathToOutput();
    MkdirStep mkOutputDirStep = new MkdirStep(getProjectFilesystem(), pathToOutput.getParent());
    JarDirectoryStep mergeOutputsStep = new JarDirectoryStep(
        getProjectFilesystem(),
        pathToOutput,
        toOutputPaths(traversedDeps.packagedDeps),
        /* mainClass */ null,
        /* manifestFile */ null);
    return ImmutableList.of(mkOutputDirStep, mergeOutputsStep);
  }

  private static ImmutableSortedSet<Path> toOutputPaths(Iterable<? extends BuildRule> rules) {
    return FluentIterable
          .from(rules)
          .transform(
              new Function<BuildRule, Path>() {
                @Nullable
                @Override
                public Path apply(BuildRule input) {
                  Path pathToOutput = input.getPathToOutput();
                  if (pathToOutput == null) {
                    return null;
                  }
                  return input.getProjectFilesystem().resolve(pathToOutput);
                }
              })
          .filter(Predicates.notNull())
          .toSortedSet(Ordering.<Path>natural());
  }

  @Override
  public Path getPathToOutput() {
    return DefaultJavaLibrary.getOutputJarPath(getBuildTarget(), getProjectFilesystem());
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public Optional<Path> getPomTemplate() {
    return mavenPomTemplate.transform(getResolver().getAbsolutePathFunction());
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
        BuildRuleParams params,
        SourcePathResolver resolver,
        ImmutableSortedSet<SourcePath> srcs,
        Optional<String> mavenCoords,
        Optional<SourcePath> mavenPomTemplate,
        TraversedDeps traversedDeps) {
      super(params, resolver, srcs, mavenCoords);
      this.traversedDeps = traversedDeps;
      this.mavenPomTemplate = mavenPomTemplate;
    }

    public static SourceJar create(
        BuildRuleParams params,
        final SourcePathResolver resolver,
        ImmutableSortedSet<SourcePath> topLevelSrcs,
        Optional<String> mavenCoords,
        Optional<SourcePath> mavenPomTemplate) {
      TraversedDeps traversedDeps = TraversedDeps.traverse(params.getDeps());

      params = adjustParams(params, traversedDeps);

      ImmutableSortedSet<SourcePath> sourcePaths =
          FluentIterable
              .from(traversedDeps.packagedDeps)
              .filter(HasSources.class)
              .transformAndConcat(
                  new Function<HasSources, Iterable<SourcePath>>() {
                    @Override
                    public Iterable<SourcePath> apply(HasSources input) {
                      return input.getSources();
                    }
                  })
              .append(topLevelSrcs)
              .toSortedSet(Ordering.natural());
      return new SourceJar(
          params,
          resolver,
          sourcePaths,
          mavenCoords,
          mavenPomTemplate,
          traversedDeps);
    }

    @Override
    public Optional<Path> getPomTemplate() {
      return mavenPomTemplate.transform(getResolver().getAbsolutePathFunction());
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
        Iterable<HasMavenCoordinates> mavenDeps,
        Iterable<BuildRule> packagedDeps) {
      this.mavenDeps = mavenDeps;
      this.packagedDeps = packagedDeps;
    }

    private static TraversedDeps traverse(ImmutableSet<? extends BuildRule> roots) {
      ImmutableSortedSet.Builder<HasMavenCoordinates> depsCollector =
          ImmutableSortedSet.naturalOrder();

      ImmutableSortedSet.Builder<JavaLibrary> candidates = ImmutableSortedSet.naturalOrder();
      for (final BuildRule root : roots) {
        Preconditions.checkState(root instanceof HasClasspathEntries);
        candidates.addAll(FluentIterable
            .from(((HasClasspathEntries) root).getTransitiveClasspathDeps())
            .filter(new Predicate<JavaLibrary>() {
              @Override
              public boolean apply(JavaLibrary buildRule) {
                return !root.equals(buildRule);
              }
            }));
      }
      ImmutableSortedSet.Builder<JavaLibrary> removals = ImmutableSortedSet.naturalOrder();
      for (JavaLibrary javaLibrary : candidates.build()) {
        if (HasMavenCoordinates.MAVEN_COORDS_PRESENT_PREDICATE.apply(javaLibrary)) {
          depsCollector.add(javaLibrary);
          removals.addAll(javaLibrary.getTransitiveClasspathDeps());
        }
      }

      return new TraversedDeps(
          /* mavenDeps */ depsCollector.build(),
          /* packagedDeps */ Sets.union(
          roots,
          Sets.difference(
              candidates.build(),
              removals.build())));
    }
  }
}
