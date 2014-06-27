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
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavacInMemoryStep;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.java.JavacVersion;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

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
  private static final JavaCompilerEnvironment BUCK_ENV = new JavaCompilerEnvironment(
      /* javac path */ Optional.<Path>absent(),
      /* javac version */ Optional.<JavacVersion>absent(),
      /* source level */ "7",
      /* target level */ "7");

  private final ImmutableSortedSet<? extends SourcePath> srcs;
  private final ImmutableSortedSet<? extends SourcePath> resources;
  private final Path output;
  private final Path abi;
  private final Path working;

  public BuckExtension(
      BuildRuleParams params,
      ImmutableSortedSet<? extends SourcePath> srcs,
      ImmutableSortedSet<? extends SourcePath> resources) {
    super(params);
    this.srcs = Preconditions.checkNotNull(srcs);
    this.resources = Preconditions.checkNotNull(resources);

    BuildTarget target = params.getBuildTarget();
    this.output = BuildTargets.getGenPath(target, "%s-buck.jar");
    this.abi = BuildTargets.getGenPath(target, "%s-buck.abi");
    this.working = BuildTargets.getBinPath(target, "__%s__");
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(Iterables.concat(srcs, resources));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    JavacOptions javacOptions = JavacOptions.builder()
        .setJavaCompilerEnviornment(BUCK_ENV)
        .build();
    ImmutableSortedSet.Builder<Path> classpath = ImmutableSortedSet.naturalOrder();
    ImmutableCollection<Path> depPaths = Classpaths.getClasspathEntries(getDeclaredDeps()).values();
    classpath.addAll(depPaths);
    readBuckClasspath(classpath);
    ImmutableSortedSet<Path> declaredClasspath = classpath.build();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MakeCleanDirectoryStep(working));
    steps.add(new MkdirStep(output.getParent()));
    steps.add(new JavacInMemoryStep(
            working,
            ImmutableSortedSet.copyOf(srcs),
            /* transitive classpath */ ImmutableSortedSet.<Path>of(),
            declaredClasspath,
            javacOptions,
            Optional.of(abi),
            Optional.of(getBuildTarget()),
            BuildDependencies.FIRST_ORDER_ONLY,
            Optional.<JavacStep.SuggestBuildRules>absent(),
            /* path to sources list */ Optional.<Path>absent()));
    steps.add(new CopyResourcesStep(
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
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }
}
