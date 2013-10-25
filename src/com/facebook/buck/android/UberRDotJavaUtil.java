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

import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.java.JavacInMemoryStep;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.Iterator;
import java.util.Map;
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
      BuildRuleType.ANDROID_BINARY,
      BuildRuleType.ANDROID_INSTRUMENTATION_APK,
      BuildRuleType.ANDROID_LIBRARY,
      BuildRuleType.ANDROID_RESOURCE,
      BuildRuleType.APK_GENRULE,
      BuildRuleType.JAVA_LIBRARY,
      BuildRuleType.JAVA_TEST,
      BuildRuleType.ROBOLECTRIC_TEST
  );


  /** Utility class: do not instantiate. */
  private UberRDotJavaUtil() {}

  /**
   * Adds the commands to generate and compile the {@code R.java} files. The {@code .class} files
   * will be written to {@link #getPathToCompiledRDotJavaFiles(BuildTarget)}.
   */
  public static void generateRDotJavaFiles(
      Set<String> resDirectories,
      Set<String> rDotJavaPackages,
      BuildTarget buildTarget,
      ImmutableList.Builder<Step> commands) {
    // Create the path where the R.java files will be generated.
    String rDotJavaSrc = String.format("%s/%s__%s_uber_rdotjava_src__",
        BuckConstant.BIN_DIR,
        buildTarget.getBasePathWithSlash(),
        buildTarget.getShortName());
    commands.add(new MakeCleanDirectoryStep(rDotJavaSrc));

    // Generate the R.java files.
    GenRDotJavaStep genRDotJava = new GenRDotJavaStep(
        resDirectories,
        rDotJavaSrc,
        rDotJavaPackages.iterator().next(),
        /* isTempRDotJava */ false,
        rDotJavaPackages);
    commands.add(genRDotJava);

    // Create the path where the R.java files will be compiled.
    String rDotJavaBin = getPathToCompiledRDotJavaFiles(buildTarget);
    commands.add(new MakeCleanDirectoryStep(rDotJavaBin));

    // Compile the R.java files.
    Set<String> javaSourceFilePaths = Sets.newHashSet();
    for (String rDotJavaPackage : rDotJavaPackages) {
      String path = rDotJavaSrc + "/" + rDotJavaPackage.replace('.', '/') + "/R.java";
      javaSourceFilePaths.add(path);
    }
    JavacInMemoryStep javac = createJavacInMemoryCommandForRDotJavaFiles(
        javaSourceFilePaths, rDotJavaBin);
    commands.add(javac);
  }

  public static String getPathToCompiledRDotJavaFiles(BuildTarget buildTarget) {
    return String.format("%s/%s__%s_uber_rdotjava_bin__",
        BuckConstant.BIN_DIR,
        buildTarget.getBasePathWithSlash(),
        buildTarget.getShortName());
  }

  /**
   * Finds the transitive set of {@code rule}'s {@link AndroidResourceRule} dependencies with
   * non-null {@code res} directories, which can also include {@code rule} itself.
   * This set will be returned as an {@link ImmutableList} with the rules topologically sorted as
   * determined by {@code graph}. Rules will be ordered from least dependent to most dependent.
   */
  public static ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps(
      BuildRule rule,
      DependencyGraph graph) {
    final Set<HasAndroidResourceDeps> allAndroidResourceRules = findAllAndroidResourceDeps(rule);

    // Now that we have the transitive set of AndroidResourceRules, we need to return them in
    // topologically sorted order. This is critical because the order in which -S flags are passed
    // to aapt is significant and must be consistent.
    Predicate<BuildRule> inclusionPredicate = new Predicate<BuildRule>() {
      @Override
      public boolean apply(BuildRule rule) {
        return allAndroidResourceRules.contains(rule);
      }
    };
    ImmutableList<BuildRule> sortedAndroidResourceRules = TopologicalSort.sort(graph,
        inclusionPredicate);

    // TopologicalSort.sort() returns rules in leaves-first order, which is the opposite of what we
    // want, so we must reverse the list and cast BuildRules to AndroidResourceRules.
    return ImmutableList.copyOf(
        Iterables.transform(
            sortedAndroidResourceRules.reverse(),
            CAST_TO_ANDROID_RESOURCE_RULE)
        );
  }


  private static Function<BuildRule, HasAndroidResourceDeps> CAST_TO_ANDROID_RESOURCE_RULE =
      new Function<BuildRule, HasAndroidResourceDeps>() {
        @Override
        public HasAndroidResourceDeps apply(BuildRule rule) {
          return (HasAndroidResourceDeps)rule;
        }
      };

  private static ImmutableSet<HasAndroidResourceDeps> findAllAndroidResourceDeps(BuildRule buildRule) {
    final ImmutableSet.Builder<HasAndroidResourceDeps> androidResources = ImmutableSet.builder();
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(buildRule) {

      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof HasAndroidResourceDeps) {
          HasAndroidResourceDeps androidResourceRule = (HasAndroidResourceDeps)rule;
          if (androidResourceRule.getRes() != null) {
            androidResources.add(androidResourceRule);
          }
        }

        // Only certain types of rules should be considered as part of this traversal.
        BuildRuleType type = rule.getType();
        return maybeVisitAllDeps(rule, TRAVERSABLE_TYPES.contains(type));
      }

    };
    visitor.start();

    return androidResources.build();
  }

  /**
   * Aggregate information about a list of {@link AndroidResourceRule}s.
   */
  public static class AndroidResourceDetails {
    /**
     * The {@code res} directories associated with the {@link AndroidResourceRule}s.
     * <p>
     * An {@link Iterator} over this collection will reflect the order of the original list of
     * {@link AndroidResourceRule}s that were specified.
     */
    public final ImmutableSet<String> resDirectories;

    public final ImmutableSet<String> rDotJavaPackages;

    @Beta
    public AndroidResourceDetails(ImmutableList<HasAndroidResourceDeps> androidResourceDeps) {
      ImmutableSet.Builder<String> resDirectoryBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<String> rDotJavaPackageBuilder = ImmutableSet.builder();
      for (HasAndroidResourceDeps androidResource : androidResourceDeps) {
        String resDirectory = androidResource.getRes();
        if (resDirectory != null) {
          resDirectoryBuilder.add(resDirectory);
          rDotJavaPackageBuilder.add(androidResource.getRDotJavaPackage());
        }
      }
      resDirectories = resDirectoryBuilder.build();
      rDotJavaPackages = rDotJavaPackageBuilder.build();
    }
  }

  public static void createDummyRDotJavaFiles(
      ImmutableList<HasAndroidResourceDeps> androidResourceDeps,
      BuildTarget buildTarget,
      ImmutableList.Builder<Step> commands) {
    // Clear out the folder for the .java files.
    String rDotJavaSrcFolder = getRDotJavaSrcFolder(buildTarget);
    commands.add(new MakeCleanDirectoryStep(rDotJavaSrcFolder));

    // Generate the .java files and record where they will be written in javaSourceFilePaths.
    Set<String> javaSourceFilePaths = Sets.newHashSet();
    if (androidResourceDeps.isEmpty()) {
      // In this case, the user is likely running a Robolectric test that does not happen to
      // depend on any resources. However, if Robolectric doesn't find an R.java file, it flips
      // out, so we have to create one, anyway.

      // TODO(mbolin): Stop hardcoding com.facebook. This should match the package in the
      // associated TestAndroidManifest.xml file.
      String rDotJavaPackage = "com.facebook";
      String javaCode = MergeAndroidResourcesStep.generateJavaCodeForPackageWithoutResources(
          rDotJavaPackage);
      commands.add(new MakeCleanDirectoryStep(rDotJavaSrcFolder + "/com/facebook"));
      String rDotJavaFile = rDotJavaSrcFolder + "/com/facebook/R.java";
      commands.add(new WriteFileStep(javaCode, rDotJavaFile));
      javaSourceFilePaths.add(rDotJavaFile);
    } else {
      Map<String, String> symbolsFileToRDotJavaPackage = Maps.newHashMap();
      for (HasAndroidResourceDeps res : androidResourceDeps) {
        String rDotJavaPackage = res.getRDotJavaPackage();
        symbolsFileToRDotJavaPackage.put(res.getPathToTextSymbolsFile(), rDotJavaPackage);
        String rDotJavaFilePath = MergeAndroidResourcesStep.getOutputFilePath(
            rDotJavaSrcFolder, rDotJavaPackage);
        javaSourceFilePaths.add(rDotJavaFilePath);
      }
      commands.add(new MergeAndroidResourcesStep(symbolsFileToRDotJavaPackage,
          rDotJavaSrcFolder));
    }

    // Clear out the directory where the .class files will be generated.
    String rDotJavaClassesDirectory = getRDotJavaBinFolder(buildTarget);
    commands.add(new MakeCleanDirectoryStep(rDotJavaClassesDirectory));

    // Compile the .java files.
    JavacInMemoryStep javac = createJavacInMemoryCommandForRDotJavaFiles(
        javaSourceFilePaths, rDotJavaClassesDirectory);
    commands.add(javac);
  }

  static String getRDotJavaSrcFolder(BuildTarget buildTarget) {
    return String.format("%s/%s__%s_rdotjava_src__",
        BuckConstant.BIN_DIR,
        buildTarget.getBasePathWithSlash(),
        buildTarget.getShortName());
  }

  public static String getRDotJavaBinFolder(BuildTarget buildTarget) {
    return String.format("%s/%s__%s_rdotjava_bin__",
        BuckConstant.BIN_DIR,
        buildTarget.getBasePathWithSlash(),
        buildTarget.getShortName());
  }

  static JavacInMemoryStep createJavacInMemoryCommandForRDotJavaFiles(
    Set<String> javaSourceFilePaths, String outputDirectory) {

    ImmutableSet<String> classpathEntries = ImmutableSet.of();
    return new JavacInMemoryStep(
        outputDirectory,
        javaSourceFilePaths,
        ImmutableSet.<String>of(),
        classpathEntries,
        JavacOptions.DEFAULTS,
        /* pathToOutputAbiFile */ Optional.<String>absent(),
        Optional.<String>absent(),
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacInMemoryStep.SuggestBuildRules>absent());
  }
}
