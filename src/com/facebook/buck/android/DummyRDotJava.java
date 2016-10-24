/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.CalculateAbiStep;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JarDirectoryStep;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.AbiRule;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Buildable that takes in a list of {@link HasAndroidResourceDeps} and for each of these rules,
 * first creates an {@code R.java} file using {@link MergeAndroidResourcesStep} and compiles it to
 * generate a corresponding {@code R.class} file. These are called "dummy" {@code R.java} files
 * since these are later merged together into a single {@code R.java} file by {@link AaptStep}.
 */
public class DummyRDotJava extends AbstractBuildRule
    implements AbiRule, HasJavaAbi {

  private final ImmutableList<HasAndroidResourceDeps> androidResourceDeps;
  private final SourcePath abiJar;
  private final Path outputJar;
  @AddToRuleKey
  private final JavacOptions javacOptions;
  @AddToRuleKey
  private final boolean forceFinalResourceIds;
  @AddToRuleKey
  private final Optional<String> unionPackage;
  @AddToRuleKey
  private final Optional<String> finalRName;

  public DummyRDotJava(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Set<HasAndroidResourceDeps> androidResourceDeps,
      SourcePath abiJar,
      JavacOptions javacOptions,
      boolean forceFinalResourceIds,
      Optional<String> unionPackage,
      Optional<String> finalRName) {
    super(params, resolver);
    // Sort the input so that we get a stable ABI for the same set of resources.
    this.androidResourceDeps = FluentIterable.from(androidResourceDeps)
        .toSortedList(HasBuildTarget.BUILD_TARGET_COMPARATOR);
    this.abiJar = abiJar;
    this.outputJar = getOutputJarPath(getBuildTarget(), getProjectFilesystem());
    this.javacOptions = javacOptions;
    this.forceFinalResourceIds = forceFinalResourceIds;
    this.unionPackage = unionPackage;
    this.finalRName = finalRName;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    final Path rDotJavaSrcFolder = getRDotJavaSrcFolder(getBuildTarget(), getProjectFilesystem());
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), rDotJavaSrcFolder));

    // Generate the .java files and record where they will be written in javaSourceFilePaths.
    ImmutableSortedSet<Path> javaSourceFilePaths;
    if (androidResourceDeps.isEmpty()) {
      // In this case, the user is likely running a Robolectric test that does not happen to
      // depend on any resources. However, if Robolectric doesn't find an R.java file, it flips
      // out, so we have to create one, anyway.

      // TODO(bolinfest): Stop hardcoding com.facebook. This should match the package in the
      // associated TestAndroidManifest.xml file.
      Path emptyRDotJava = rDotJavaSrcFolder.resolve("com/facebook/R.java");
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), emptyRDotJava.getParent()));
      steps.add(
          new WriteFileStep(
              getProjectFilesystem(),
              "package com.facebook;\n public class R {}\n",
              emptyRDotJava,
              /* executable */ false));
      javaSourceFilePaths = ImmutableSortedSet.of(emptyRDotJava);
    } else {
      MergeAndroidResourcesStep mergeStep = MergeAndroidResourcesStep.createStepForDummyRDotJava(
          getProjectFilesystem(),
          getResolver(),
          androidResourceDeps,
          rDotJavaSrcFolder,
          forceFinalResourceIds,
          unionPackage,
          /* rName */ Optional.empty());
      steps.add(mergeStep);

      if (!finalRName.isPresent()) {
        javaSourceFilePaths = mergeStep.getRDotJavaFiles();
      } else {
        MergeAndroidResourcesStep mergeFinalRStep =
            MergeAndroidResourcesStep.createStepForDummyRDotJava(
                getProjectFilesystem(),
                getResolver(),
                androidResourceDeps,
                rDotJavaSrcFolder,
                /* forceFinalResourceIds */ true,
                unionPackage,
                finalRName);
        steps.add(mergeFinalRStep);

        javaSourceFilePaths = ImmutableSortedSet.<Path>naturalOrder()
            .addAll(mergeStep.getRDotJavaFiles())
            .addAll(mergeFinalRStep.getRDotJavaFiles())
            .build();
      }
    }

    // Clear out the directory where the .class files will be generated.
    final Path rDotJavaClassesFolder = getRDotJavaBinFolder();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), rDotJavaClassesFolder));

    Path pathToAbiOutputDir = getPathToAbiOutputDir(getBuildTarget(), getProjectFilesystem());
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), pathToAbiOutputDir));
    Path pathToAbiOutputFile = pathToAbiOutputDir.resolve("abi.jar");

    Path pathToJarOutputDir = outputJar.getParent();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), pathToJarOutputDir));

    Path pathToSrcsList =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__srcs");
    steps.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

    // Compile the .java files.
    final JavacStep javacStep =
        RDotJava.createJavacStepForDummyRDotJavaFiles(
            javaSourceFilePaths,
            pathToSrcsList,
            rDotJavaClassesFolder,
            javacOptions,
            getBuildTarget(),
            getResolver(),
            getProjectFilesystem());
    steps.add(javacStep);
    buildableContext.recordArtifact(rDotJavaClassesFolder);

    steps.add(
        new JarDirectoryStep(
            getProjectFilesystem(),
            outputJar,
            ImmutableSortedSet.of(rDotJavaClassesFolder),
            /* mainClass */ null,
            /* manifestFile */ null));
    buildableContext.recordArtifact(outputJar);

    steps.add(
        new CalculateAbiStep(
            buildableContext,
            getProjectFilesystem(),
            rDotJavaClassesFolder,
            pathToAbiOutputFile));

    return steps.build();
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps(DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory) {
    return HasAndroidResourceDeps.hashAbi(androidResourceDeps);
  }

  public static Path getRDotJavaSrcFolder(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_src__");
  }

  public static Path getRDotJavaBinFolder(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_bin__");
  }

  private static Path getPathToAbiOutputDir(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "__%s_dummyrdotjava_abi__");
  }

  private static Path getPathToOutputDir(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "__%s_dummyrdotjava_output__");
  }

  private static Path getOutputJarPath(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return getPathToOutputDir(buildTarget, filesystem).resolve(
        String.format("%s.jar", buildTarget.getShortNameAndFlavorPostfix()));
  }

  @Override
  public Path getPathToOutput() {
    return outputJar;
  }

  public Path getRDotJavaBinFolder() {
    return getRDotJavaBinFolder(getBuildTarget(), getProjectFilesystem());
  }

  public ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps() {
    return androidResourceDeps;
  }

  @VisibleForTesting
  JavacOptions getJavacOptions() {
    return javacOptions;
  }

  @Override
  public Optional<SourcePath> getAbiJar() {
    return Optional.of(abiJar);
  }

}
