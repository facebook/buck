/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.extension;

import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.CopyResourcesStep;
import com.facebook.buck.java.JarDirectoryStep;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Builds an extension for buck. This is similar to a {@code java_library}, but will automatically
 * have the classpath of buck itself added to its dependencies.
 */
public class BuckExtension extends AbstractBuildRule {
  @AddToRuleKey
  private final JavacOptions javacOptions;
  @AddToRuleKey
  private final ImmutableSortedSet<? extends SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableSortedSet<? extends SourcePath> resources;
  private final Path output;
  private final Path working;

  public BuckExtension(
      BuildRuleParams params,
      JavacOptions javacOptions,
      SourcePathResolver resolver,
      ImmutableSortedSet<? extends SourcePath> srcs,
      ImmutableSortedSet<? extends SourcePath> resources) {
    super(params, resolver);

    this.javacOptions = javacOptions;
    this.srcs = srcs;
    this.resources = resources;

    BuildTarget target = params.getBuildTarget();
    this.output = BuildTargets.getGenPath(target, "%s-buck.jar");
    this.working = BuildTargets.getScratchPath(target, "__%s__");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableSortedSet.Builder<Path> classpath = ImmutableSortedSet.naturalOrder();
    ImmutableCollection<Path> depPaths = Classpaths.getClasspathEntries(getDeclaredDeps()).values();
    classpath.addAll(depPaths);
    readBuckClasspath(classpath);
    ImmutableSortedSet<Path> declaredClasspath = classpath.build();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MakeCleanDirectoryStep(working));
    steps.add(new MkdirStep(output.getParent()));

    steps.add(
        new JavacStep(
            working,
            Optional.<Path>absent(),
            ImmutableSet.copyOf(getResolver().getAllPaths(srcs)),
            Optional.<Path>absent(),
            /* transitive classpath */ ImmutableSortedSet.<Path>of(),
            declaredClasspath,
            javacOptions,
            getBuildTarget(),
            BuildDependencies.FIRST_ORDER_ONLY,
            Optional.<JavacStep.SuggestBuildRules>absent()));
    steps.add(new CopyResourcesStep(
            getResolver(),
            getBuildTarget(),
            resources,
            output,
            context.getJavaPackageFinder()));
    steps.add(new JarDirectoryStep(
            output,
            ImmutableSortedSet.<Path>naturalOrder().add(working).addAll(depPaths).build(),
            /* main class */ null,
            /* manifest file */ null));

    return steps.build();
  }

  @VisibleForTesting
  static Collection<Path> readBuckClasspath(ImmutableSortedSet.Builder<Path> builder) {
    ClassLoader classLoader = BuckExtension.class.getClassLoader();
    Preconditions.checkState(classLoader instanceof URLClassLoader);

    @SuppressWarnings("resource")
    // Because this ClassLoader comes from BuckExtension.class, it is not obvious that it would
    // be safe to close it. See
    // http://docs.oracle.com/javase/7/docs/technotes/guides/net/ClassLoader.html.
    URLClassLoader urlLoader = (URLClassLoader) classLoader;
    for (URL url : urlLoader.getURLs()) {
      try {
        if (!"file".equals(url.toURI().getScheme())) {
          continue;
        }
        builder.add(new File(url.toURI()).toPath().toAbsolutePath().normalize());
      } catch (URISyntaxException e) {
        // There's no sane way that this will happen.
        throw new RuntimeException(e);
      }
    }

    return builder.build();
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }
}
