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

package com.facebook.buck.java;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasDependencies;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * A {@link BuildRule} used to have the provided {@link JavaLibrary} published to a maven repository
 *
 * @see #create
 */
public class MavenUberJar extends AbstractBuildRule implements MavenPublishable {

  private final Optional<String> mavenCoords;
  private final TraversedDeps traversedDeps;

  private MavenUberJar(
      TraversedDeps traversedDeps,
      BuildRuleParams params,
      SourcePathResolver resolver,
      Optional<String> mavenCoords) {
    super(params, resolver);
    this.traversedDeps = traversedDeps;
    this.mavenCoords = mavenCoords;
  }

  private static BuildRuleParams adjustParams(BuildRuleParams params, TraversedDeps traversedDeps) {
    return params.copyWithDeps(
        Suppliers.ofInstance(traversedDeps.builtinDeps),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
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
      Optional<String> mavenCoords) {
    TraversedDeps traversedDeps = TraversedDeps.traverse(rootRule);
    return new MavenUberJar(
        traversedDeps,
        adjustParams(params, traversedDeps),
        resolver,
        mavenCoords);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path pathToOutput = getPathToOutput();
    MkdirStep mkOutputDirStep = new MkdirStep(getProjectFilesystem(), pathToOutput.getParent());
    JarDirectoryStep mergeOutputsStep = new JarDirectoryStep(
        getProjectFilesystem(),
        pathToOutput,
        toOutputPaths(traversedDeps.builtinDeps),
        null,
        null);
    return ImmutableList.of(mkOutputDirStep, mergeOutputsStep);
  }

  private static ImmutableSet<Path> toOutputPaths(Iterable<? extends BuildRule> rules) {
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
          .toSet();
  }

  @Override
  public Path getPathToOutput() {
    return DefaultJavaLibrary.getOutputJarPath(getBuildTarget());
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public ImmutableSortedSet<HasMavenCoordinates> getMavenDeps() {
    return traversedDeps.mavenDeps;
  }

  public static class SourceJar extends JavaSourceJar implements MavenPublishable {

    private final TraversedDeps traversedDeps;

    public SourceJar(
        BuildRuleParams params,
        SourcePathResolver resolver,
        ImmutableSortedSet<SourcePath> srcs,
        Optional<String> mavenCoords,
        TraversedDeps traversedDeps) {
      super(params, resolver, srcs, mavenCoords);
      this.traversedDeps = traversedDeps;
    }

    public static SourceJar create(
        BuildRuleParams params,
        final SourcePathResolver resolver,
        ImmutableSortedSet<SourcePath> topLevelSrcs,
        Optional<String> mavenCoords) {
      TraversedDeps traversedDeps = TraversedDeps.traverse(params);

      params = adjustParams(params, traversedDeps);

      ImmutableSortedSet<SourcePath> sourcePaths =
          FluentIterable
              .from(traversedDeps.builtinDeps)
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
          traversedDeps);
    }

    @Override
    public ImmutableSortedSet<HasMavenCoordinates> getMavenDeps() {
      return Preconditions.checkNotNull(traversedDeps).mavenDeps;
    }
  }

  private static class TraversedDeps {
    public final ImmutableSortedSet<HasMavenCoordinates> mavenDeps;
    public final ImmutableSortedSet<BuildRule> builtinDeps;

    private TraversedDeps(
        ImmutableSortedSet<HasMavenCoordinates> mavenDeps,
        ImmutableSortedSet<BuildRule> builtinDeps) {
      this.mavenDeps = mavenDeps;
      this.builtinDeps = builtinDeps;
    }

    private static TraversedDeps traverse(HasDependencies root) {
      ImmutableSortedSet.Builder<HasMavenCoordinates> depsCollector =
          ImmutableSortedSet.naturalOrder();
      ImmutableSortedSet.Builder<BuildRule> builtinCollector = ImmutableSortedSet.naturalOrder();
      if (root instanceof BuildRule) {
        builtinCollector.add((BuildRule) root);
      }

      traverseDepsRecursively(root, depsCollector, builtinCollector);

      return new TraversedDeps(depsCollector.build(), builtinCollector.build());
    }

    private static void traverseDepsRecursively(
        HasDependencies visitedNode,
        ImmutableSortedSet.Builder<HasMavenCoordinates> depsCollector,
        ImmutableSortedSet.Builder<BuildRule> builtinsCollector) {
      for (BuildRule dep : visitedNode.getDeps()) {
        if (HasMavenCoordinates.MAVEN_COORDS_PRESENT_PREDICATE.apply(dep)) {
          depsCollector.add((HasMavenCoordinates) dep);
        } else {
          builtinsCollector.add(dep);
          traverseDepsRecursively(dep, depsCollector, builtinsCollector);
        }
      }

    }
  }
}
