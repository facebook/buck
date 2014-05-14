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

import com.facebook.buck.java.HasJavaAbi;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Buildable that takes in a list of {@link HasAndroidResourceDeps} and for each of these rules,
 * first creates an {@code R.java} file using {@link MergeAndroidResourcesStep} and compiles it to
 * generate a corresponding {@code R.class} file. These are called "dummy" {@code R.java} files
 * since these are later merged together into a single {@code R.java} file by {@link AaptStep}.
 */
public class DummyRDotJava extends AbstractBuildable
    implements AbiRule, HasJavaAbi, InitializableFromDisk<DummyRDotJava.BuildOutput> {

  private final ImmutableList<HasAndroidResourceDeps> androidResourceDeps;
  private final BuildTarget buildTarget;
  private final JavacOptions javacOptions;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  @VisibleForTesting
  static final String METADATA_KEY_FOR_ABI_KEY = "DUMMY_R_DOT_JAVA_ABI_KEY";

  public DummyRDotJava(
      Set<HasAndroidResourceDeps> androidResourceDeps,
      BuildTarget buildTarget,
      JavacOptions javacOptions) {
    // Sort the input so that we get a stable ABI for the same set of resources.
    this.androidResourceDeps = FluentIterable.from(androidResourceDeps)
        .toSortedList(HasBuildTarget.BUILD_TARGET_COMPARATOR);
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    final Path rDotJavaSrcFolder = getRDotJavaSrcFolder(buildTarget);
    steps.add(new MakeCleanDirectoryStep(rDotJavaSrcFolder));

    // Generate the .java files and record where they will be written in javaSourceFilePaths.
    Set<SourcePath> javaSourceFilePaths = Sets.newHashSet();
    if (androidResourceDeps.isEmpty()) {
      // In this case, the user is likely running a Robolectric test that does not happen to
      // depend on any resources. However, if Robolectric doesn't find an R.java file, it flips
      // out, so we have to create one, anyway.

      // TODO(mbolin): Stop hardcoding com.facebook. This should match the package in the
      // associated TestAndroidManifest.xml file.
      String rDotJavaPackage = "com.facebook";
      String javaCode = MergeAndroidResourcesStep.generateJavaCodeForPackageWithoutResources(
          rDotJavaPackage);
      steps.add(new MakeCleanDirectoryStep(rDotJavaSrcFolder.resolve("com/facebook")));
      Path rDotJavaFile = rDotJavaSrcFolder.resolve("com/facebook/R.java");
      steps.add(new WriteFileStep(javaCode, rDotJavaFile));
      javaSourceFilePaths.add(new PathSourcePath(rDotJavaFile));
    } else {
      Map<Path, String> symbolsFileToRDotJavaPackage = Maps.newHashMap();
      for (HasAndroidResourceDeps res : androidResourceDeps) {
        String rDotJavaPackage = res.getRDotJavaPackage();
        symbolsFileToRDotJavaPackage.put(res.getPathToTextSymbolsFile(), rDotJavaPackage);
        Path rDotJavaFilePath = MergeAndroidResourcesStep.getOutputFilePath(
            rDotJavaSrcFolder, rDotJavaPackage);
        javaSourceFilePaths.add(new PathSourcePath(rDotJavaFilePath));
      }
      steps.add(new MergeAndroidResourcesStep(symbolsFileToRDotJavaPackage,
          rDotJavaSrcFolder));
    }

    // Clear out the directory where the .class files will be generated.
    final Path rDotJavaClassesFolder = getRDotJavaBinFolder();
    steps.add(new MakeCleanDirectoryStep(rDotJavaClassesFolder));

    Path pathToAbiOutputDir = getPathToAbiOutputDir(buildTarget);
    steps.add(new MakeCleanDirectoryStep(pathToAbiOutputDir));
    Path pathToAbiOutputFile = pathToAbiOutputDir.resolve("abi");

    // Compile the .java files.
    final JavacStep javacStep =
        UberRDotJavaUtil.createJavacStepForDummyRDotJavaFiles(
            javaSourceFilePaths,
            rDotJavaClassesFolder,
            Optional.of(pathToAbiOutputFile),
            javacOptions,
            buildTarget);
    steps.add(javacStep);

    steps.add(new AbstractExecutionStep("record_abi_key") {
      @Override
      public int execute(ExecutionContext context) {
        Sha1HashCode abiKey = javacStep.getAbiKey();
        Preconditions.checkNotNull(abiKey,
            "Javac step must create a non-null ABI key for this rule.");
        buildableContext.addMetadata(METADATA_KEY_FOR_ABI_KEY, abiKey.getHash());
        return 0;
      }
    });

    buildableContext.recordArtifactsInDirectory(rDotJavaClassesFolder);
    return steps.build();
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return HasAndroidResourceDeps.ABI_HASHER.apply(androidResourceDeps);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return javacOptions.appendToRuleKey(builder);
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
    return getRDotJavaBinFolder(buildTarget);
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
    Optional<Sha1HashCode> abiKey = onDiskBuildInfo.getHash(METADATA_KEY_FOR_ABI_KEY);
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

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public static class BuildOutput {
    @VisibleForTesting
    final Sha1HashCode rDotTxtSha1;

    public BuildOutput(Sha1HashCode rDotTxtSha1) {
      this.rDotTxtSha1 = Preconditions.checkNotNull(rDotTxtSha1);
    }
  }
}
