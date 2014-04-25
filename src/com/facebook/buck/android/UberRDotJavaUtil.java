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

package com.facebook.buck.android;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.JavacInMemoryStep;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.java.JavacStepUtil;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Creates the {@link Step}s needed to generate an uber {@code R.java} file.
 * <p>
 * Buck builds two types of {@code R.java} files: temporary ones and uber ones. A temporary
 * {@code R.java} file's values are garbage and correspond to a single Android libraries. An uber
 * {@code R.java} file represents the transitive closure of Android libraries that are being
 * packaged into an APK and has the real values for that APK.
 */
public class UberRDotJavaUtil {

  private static final ImmutableSet<BuildRuleType> TRAVERSABLE_TYPES = ImmutableSet.of(
      AndroidBinaryDescription.TYPE,
      AndroidInstrumentationApkDescription.TYPE,
      AndroidLibraryDescription.TYPE,
      AndroidResourceDescription.TYPE,
      ApkGenruleDescription.TYPE,
      JavaLibraryDescription.TYPE,
      JavaTestDescription.TYPE,
      RobolectricTestDescription.TYPE);


  /** Utility class: do not instantiate. */
  private UberRDotJavaUtil() {}

  /**
   * Finds the transitive set of {@code rule}'s {@link AndroidResource} dependencies with
   * non-null {@code res} directories, which can also include {@code rule} itself.
   * This set will be returned as an {@link ImmutableList} with the rules topologically sorted.
   * Rules will be ordered from least dependent to most dependent.
   */
  public static ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps(BuildRule rule) {
    return getAndroidResourceDeps(Collections.singleton(rule), /*includeAssetOnlyRules */ false);
  }

  public static ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps(
      Collection<BuildRule> rules) {
    return getAndroidResourceDeps(rules, /* includeAssetOnlyRules */ false);
  }

  /**
   * Finds the transitive set of {@code rules}' {@link AndroidResource} dependencies with
   * non-null {@code res} directories, which can also include any of the {@code rules} themselves.
   * This set will be returned as an {@link ImmutableList} with the rules topologically sorted.
   * Rules will be ordered from least dependent to most dependent.
   */
  public static ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps(
      Collection<BuildRule> rules,
      final boolean includeAssetOnlyRules) {
    // This visitor finds all AndroidResourceRules that are reachable from the specified rules via
    // rules with types in the TRAVERSABLE_TYPES collection. It also builds up the dependency graph
    // that was traversed to find the AndroidResourceRules.
    final MutableDirectedGraph<BuildRule> mutableGraph = new MutableDirectedGraph<>();

    final ImmutableSet.Builder<HasAndroidResourceDeps> androidResources = ImmutableSet.builder();
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(rules) {

      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        HasAndroidResourceDeps androidResourceRule = null;
        if (rule instanceof HasAndroidResourceDeps) {
          androidResourceRule = (HasAndroidResourceDeps) rule;
        } else if (rule.getBuildable() instanceof HasAndroidResourceDeps) {
          androidResourceRule = (HasAndroidResourceDeps) rule.getBuildable();
        }
        if (androidResourceRule != null &&
            (androidResourceRule.getRes() != null ||
                (includeAssetOnlyRules && androidResourceRule.getAssets() != null))) {
          androidResources.add(androidResourceRule);
        }

        // Only certain types of rules should be considered as part of this traversal.
        BuildRuleType type = rule.getType();
        ImmutableSet<BuildRule> depsToVisit = maybeVisitAllDeps(rule,
            TRAVERSABLE_TYPES.contains(type));
        mutableGraph.addNode(rule);
        for (BuildRule dep : depsToVisit) {
          mutableGraph.addEdge(rule, dep);
        }
        return depsToVisit;
      }

    };
    visitor.start();

    final Set<HasAndroidResourceDeps> allAndroidResourceRules = androidResources.build();

    // Now that we have the transitive set of AndroidResourceRules, we need to return them in
    // topologically sorted order. This is critical because the order in which -S flags are passed
    // to aapt is significant and must be consistent.
    Predicate<BuildRule> inclusionPredicate = new Predicate<BuildRule>() {
      @Override
      public boolean apply(BuildRule rule) {
        return allAndroidResourceRules.contains(rule) ||
            allAndroidResourceRules.contains(rule.getBuildable());
      }
    };
    ImmutableList<BuildRule> sortedAndroidResourceRules = TopologicalSort.sort(mutableGraph,
        inclusionPredicate);

    // TopologicalSort.sort() returns rules in leaves-first order, which is the opposite of what we
    // want, so we must reverse the list and cast BuildRules to AndroidResourceRules.
    return ImmutableList.copyOf(
        Iterables.transform(
            sortedAndroidResourceRules.reverse(),
            CAST_TO_ANDROID_RESOURCE_RULE));
  }

  private static final Function<BuildRule, HasAndroidResourceDeps> CAST_TO_ANDROID_RESOURCE_RULE =
      new Function<BuildRule, HasAndroidResourceDeps>() {
        @Override
        public HasAndroidResourceDeps apply(BuildRule rule) {
          return (rule instanceof HasAndroidResourceDeps)
              ? (HasAndroidResourceDeps) rule
              : (HasAndroidResourceDeps) rule.getBuildable();
        }
      };

  static JavacStep createJavacStepForUberRDotJavaFiles(
      Set<SourcePath> javaSourceFilePaths,
      Path outputDirectory,
      JavacOptions javacOptions,
      BuildTarget buildTarget) {
    return createJavacStepForDummyRDotJavaFiles(
        javaSourceFilePaths,
        outputDirectory,
        /* pathToOutputAbiFile */ Optional.<Path>absent(),
        javacOptions,
        buildTarget);
  }

  static JavacStep createJavacStepForDummyRDotJavaFiles(
      Set<? extends SourcePath> javaSourceFilePaths,
      Path outputDirectory,
      Optional<Path> pathToOutputAbiFile,
      JavacOptions javacOptions,
      BuildTarget buildTarget) {

    return JavacStepUtil.createJavacStep(
        outputDirectory,
        javaSourceFilePaths,
        ImmutableSet.<Path>of(),
        /* classpathEntries */ ImmutableSet.<Path>of(),
        JavacOptions.builder(JavacOptions.DEFAULTS)
            .setJavaCompilerEnviornment(javacOptions.getJavaCompilerEnvironment())
            .build(),
        pathToOutputAbiFile,
        Optional.<String>absent(),
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacInMemoryStep.SuggestBuildRules>absent(),
        /* pathToSrcsList */ Optional.<Path>absent(),
        buildTarget,
        /* workingDirectory */ Optional.<Path>absent());
  }
}
