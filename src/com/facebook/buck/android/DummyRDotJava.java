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

import com.facebook.buck.java.CalculateAbiStep;
import com.facebook.buck.java.HasJavaAbi;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Buildable that takes in a list of {@link HasAndroidResourceDeps} and for each of these rules,
 * first creates an {@code R.java} file using {@link MergeAndroidResourcesStep} and compiles it to
 * generate a corresponding {@code R.class} file. These are called "dummy" {@code R.java} files
 * since these are later merged together into a single {@code R.java} file by {@link AaptStep}.
 */
public class DummyRDotJava extends AbstractBuildRule
    implements AbiRule, HasJavaAbi, InitializableFromDisk<DummyRDotJava.BuildOutput> {

  private final ImmutableList<HasAndroidResourceDeps> androidResourceDeps;
  private final JavacOptions javacOptions;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  public DummyRDotJava(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Set<HasAndroidResourceDeps> androidResourceDeps,
      JavacOptions javacOptions) {
    super(params, resolver);
    // Sort the input so that we get a stable ABI for the same set of resources.
    this.androidResourceDeps = FluentIterable.from(androidResourceDeps)
        .toSortedList(HasBuildTarget.BUILD_TARGET_COMPARATOR);
    this.javacOptions = javacOptions;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    final Path rDotJavaSrcFolder = getRDotJavaSrcFolder(getBuildTarget());
    steps.add(new MakeCleanDirectoryStep(rDotJavaSrcFolder));

    // Generate the .java files and record where they will be written in javaSourceFilePaths.
    Set<Path> javaSourceFilePaths;
    if (androidResourceDeps.isEmpty()) {
      // In this case, the user is likely running a Robolectric test that does not happen to
      // depend on any resources. However, if Robolectric doesn't find an R.java file, it flips
      // out, so we have to create one, anyway.

      // TODO(mbolin): Stop hardcoding com.facebook. This should match the package in the
      // associated TestAndroidManifest.xml file.
      Path emptyRDotJava = rDotJavaSrcFolder.resolve("com/facebook/R.java");
      steps.add(new MakeCleanDirectoryStep(emptyRDotJava.getParent()));
      steps.add(new WriteFileStep(
          "package com.facebook;\n public class R {}\n",
          emptyRDotJava));
      javaSourceFilePaths = ImmutableSet.of(emptyRDotJava);
    } else {
      MergeAndroidResourcesStep mergeStep = MergeAndroidResourcesStep.createStepForDummyRDotJava(
          androidResourceDeps,
          rDotJavaSrcFolder);
      steps.add(mergeStep);
      javaSourceFilePaths =
          ImmutableSet.copyOf(getResolver().getAllPaths(mergeStep.getRDotJavaFiles()));
    }

    // Clear out the directory where the .class files will be generated.
    final Path rDotJavaClassesFolder = getRDotJavaBinFolder();
    steps.add(new MakeCleanDirectoryStep(rDotJavaClassesFolder));

    Path pathToAbiOutputDir = getPathToAbiOutputDir(getBuildTarget());
    steps.add(new MakeCleanDirectoryStep(pathToAbiOutputDir));
    Path pathToAbiOutputFile = pathToAbiOutputDir.resolve("abi.jar");

    // Compile the .java files.
    final JavacStep javacStep =
        RDotJava.createJavacStepForDummyRDotJavaFiles(
            javaSourceFilePaths,
            rDotJavaClassesFolder,
            javacOptions,
            getBuildTarget());
    steps.add(javacStep);
    buildableContext.recordArtifactsInDirectory(rDotJavaClassesFolder);

    steps.add(new CalculateAbiStep(buildableContext, rDotJavaClassesFolder, pathToAbiOutputFile));

    return steps.build();
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return HasAndroidResourceDeps.ABI_HASHER.apply(androidResourceDeps);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return javacOptions.appendToRuleKey(builder, "javacOptions");
  }

  private static Path getRDotJavaSrcFolder(BuildTarget buildTarget) {
    return BuildTargets.getBinPath(buildTarget, "__%s_rdotjava_src__");
  }

  private static Path getRDotJavaBinFolder(BuildTarget buildTarget) {
    return BuildTargets.getBinPath(buildTarget, "__%s_rdotjava_bin__");
  }

  private static Path getPathToAbiOutputDir(BuildTarget buildTarget) {
    return BuildTargets.getGenPath(buildTarget, "__%s_dummyrdotjava_abi__");
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  public Path getRDotJavaBinFolder() {
    return getRDotJavaBinFolder(getBuildTarget());
  }

  public ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps() {
    return androidResourceDeps;
  }

  @VisibleForTesting
  JavacOptions getJavacOptions() {
    return javacOptions;
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Optional<Sha1HashCode> abiKey = onDiskBuildInfo.getHash(AbiRule.ABI_KEY_ON_DISK_METADATA);
    if (!abiKey.isPresent()) {
      throw new IllegalStateException(String.format(
          "Should not be initializing %s from disk if ABI key is not written",
          this));
    }
    return new BuildOutput(abiKey.get());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Sha1HashCode getAbiKey() {
    return buildOutputInitializer.getBuildOutput().rDotTxtSha1;
  }

  public static class BuildOutput {
    @VisibleForTesting
    final Sha1HashCode rDotTxtSha1;

    public BuildOutput(Sha1HashCode rDotTxtSha1) {
      this.rDotTxtSha1 = rDotTxtSha1;
    }
  }
}
