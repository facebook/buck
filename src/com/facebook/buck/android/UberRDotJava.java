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

import com.facebook.buck.android.UberRDotJava.BuildOutput;
import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.java.AccumulateClassNamesStep;
import com.facebook.buck.java.HasJavaClassHashes;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
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
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Buildable that is responsible for:
 * <ul>
 *   <li>Generating a single {@code R.java} file from those directories (aka the uber-R.java).
 *   <li>Compiling the single {@code R.java} file.
 *   <li>Dexing the single {@code R.java} to a {@code classes.dex.jar} file (Optional).
 * </ul>
 * <p>
 * Clients of this Buildable may need to know the path to the {@code R.java} file.
 */
public class UberRDotJava extends AbstractBuildRule implements
    AbiRule, InitializableFromDisk<BuildOutput>, HasJavaClassHashes {

  public static final String R_DOT_JAVA_LINEAR_ALLOC_SIZE = "r_dot_java_linear_alloc_size";

  /** Options to use with {@link com.facebook.buck.android.DxStep} when dexing R.java. */
  public static final EnumSet<DxStep.Option> DX_OPTIONS = EnumSet.of(
      DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
      DxStep.Option.RUN_IN_PROCESS,
      DxStep.Option.NO_OPTIMIZE);

  private final FilteredResourcesProvider filteredResourcesProvider;
  private final ImmutableList<HasAndroidResourceDeps> resourceDeps;
  private final Supplier<ImmutableSet<String>> rDotJavaPackages;
  private final JavacOptions javacOptions;
  private final boolean rDotJavaNeedsDexing;
  private final boolean shouldBuildStringSourceMap;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  UberRDotJava(
      BuildRuleParams params,
      FilteredResourcesProvider filteredResourcesProvider,
      ImmutableList<HasAndroidResourceDeps> resourceDeps,
      Supplier<ImmutableSet<String>> rDotJavaPackages,
      JavacOptions javacOptions,
      boolean rDotJavaNeedsDexing,
      boolean shouldBuildStringSourceMap) {
    super(params);
    this.filteredResourcesProvider = Preconditions.checkNotNull(filteredResourcesProvider);
    this.resourceDeps = Preconditions.checkNotNull(resourceDeps);
    this.rDotJavaPackages = Preconditions.checkNotNull(rDotJavaPackages);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.rDotJavaNeedsDexing = rDotJavaNeedsDexing;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    javacOptions.appendToRuleKey(builder);
    return builder
        .set("rDotJavaNeedsDexing", rDotJavaNeedsDexing)
        .set("shouldBuildStringSourceMap", shouldBuildStringSourceMap);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  public Optional<DexWithClasses> getRDotJavaDexWithClasses() {
    Preconditions.checkState(rDotJavaNeedsDexing,
        "Error trying to get R.java dex file: R.java is not supposed to be dexed.");

    final Optional<Integer> linearAllocSizeEstimate =
        buildOutputInitializer.getBuildOutput().rDotJavaDexLinearAllocEstimate;
    if (!linearAllocSizeEstimate.isPresent()) {
      return Optional.absent();
    }

    return Optional.<DexWithClasses>of(new DexWithClasses() {
          @Override
          public Path getPathToDexFile() {
            return getPathToRDotJavaDex();
          }

          @Override
          public ImmutableSet<String> getClassNames() {
            throw new RuntimeException(
                "Since R.java is unconditionally packed in the primary dex, no" +
                    "one should call this method.");
          }

          @Override
          public Sha1HashCode getClassesHash() {
            return Sha1HashCode.fromHashCode(
                Hashing.combineOrdered(getClassNamesToHashes().values()));
          }

          @Override
          public int getSizeEstimate() {
            return linearAllocSizeEstimate.get();
          }
        });
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().rDotJavaClassesHash;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    ImmutableList<Path> resDirectories = filteredResourcesProvider.getResDirectories();

    if (!resDirectories.isEmpty()) {
      generateAndCompileRDotJavaFiles(
          resDirectories,
          rDotJavaPackages.get(),
          steps,
          buildableContext);
    }

    if (rDotJavaNeedsDexing && !resDirectories.isEmpty()) {
      Path rDotJavaDexDir = getPathToRDotJavaDexFiles();
      steps.add(new MakeCleanDirectoryStep(rDotJavaDexDir));
      steps.add(new DxStep(
              getPathToRDotJavaDex(),
              Collections.singleton(getPathToCompiledRDotJavaFiles()),
              DX_OPTIONS));

      final EstimateLinearAllocStep estimateLinearAllocStep = new EstimateLinearAllocStep(
          getPathToCompiledRDotJavaFiles());
      steps.add(estimateLinearAllocStep);

      buildableContext.recordArtifact(getPathToRDotJavaDex());
      steps.add(
          new AbstractExecutionStep("record_build_output") {
            @Override
            public int execute(ExecutionContext context) {
              buildableContext.addMetadata(
                  R_DOT_JAVA_LINEAR_ALLOC_SIZE,
                  estimateLinearAllocStep.get().toString());
              return 0;
            }
          });
    }

    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    ImmutableSortedMap<String, HashCode> classesHash = ImmutableSortedMap.of();
    if (!filteredResourcesProvider.getResDirectories().isEmpty()) {
      List<String> lines;
      try {
        lines = onDiskBuildInfo.getOutputFileContentsByLine(getPathToRDotJavaClassesTxt());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      classesHash = AccumulateClassNamesStep.parseClassHashes(lines);
    }

    Optional<String> linearAllocSizeValue = onDiskBuildInfo.getValue(R_DOT_JAVA_LINEAR_ALLOC_SIZE);
    Optional<Integer> linearAllocSize = linearAllocSizeValue.isPresent()
        ? Optional.of(Integer.parseInt(linearAllocSizeValue.get()))
        : Optional.<Integer>absent();

    return new BuildOutput(linearAllocSize, classesHash);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return HasAndroidResourceDeps.ABI_HASHER.apply(resourceDeps);
  }

  public static class BuildOutput {
    private final Optional<Integer> rDotJavaDexLinearAllocEstimate;
    private final ImmutableSortedMap<String, HashCode> rDotJavaClassesHash;

    public BuildOutput(
        Optional<Integer> rDotJavaDexLinearAllocSizeEstimate,
        ImmutableSortedMap<String, HashCode> rDotJavaClassesHash) {
      this.rDotJavaDexLinearAllocEstimate =
          Preconditions.checkNotNull(rDotJavaDexLinearAllocSizeEstimate);
      this.rDotJavaClassesHash = Preconditions.checkNotNull(rDotJavaClassesHash);
    }
  }

  /**
   * Adds the commands to generate and compile the {@code R.java} files. The {@code R.class} files
   * will be written to {@link #getPathToCompiledRDotJavaFiles()}.
   */
  private void generateAndCompileRDotJavaFiles(
      ImmutableList<Path> resDirectories,
      Set<String> rDotJavaPackages,
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {
    // Create the path where the R.txt files will be generated.
    Path rDotTxtDir = getPathToRDotTxtDir();
    steps.add(new MakeCleanDirectoryStep(rDotTxtDir));

    // Generate the real R.txt file.
    Path dummyManifestFile = BuildTargets.getGenPath(
        getBuildTarget(), "__%s_dummy_manifest/AndroidManifest.xml");
    steps.addAll(GenRDotTxtUtil.createSteps(
        resDirectories,
        rDotTxtDir,
        Suppliers.ofInstance(Iterables.get(rDotJavaPackages, 0)),
        /* isTempRDotJava */ false,
        dummyManifestFile));

    // Merge R.txt of HasAndroidRes and generate the resulting R.java files per package.
    Path rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();
    steps.add(new MakeCleanDirectoryStep(rDotJavaSrc));
    MergeAndroidResourcesStep mergeStep = new MergeAndroidResourcesStep(
        resourceDeps,
        Optional.of(rDotTxtDir.resolve("R.txt")),
        rDotJavaSrc);
    steps.add(mergeStep);

    if (shouldBuildStringSourceMap) {
      // Make sure we have an output directory
      Path outputDirPath = getPathForNativeStringInfoDirectory();
      steps.add(new MakeCleanDirectoryStep(outputDirPath));

      // Add the step that parses R.txt and all the strings.xml files, and
      // produces a JSON with android resource id's and xml paths for each string resource.
      GenStringSourceMapStep genNativeStringInfo = new GenStringSourceMapStep(
          rDotTxtDir,
          resDirectories,
          outputDirPath);
      steps.add(genNativeStringInfo);

      // Cache the generated strings.json file, it will be stored inside outputDirPath
      buildableContext.recordArtifactsInDirectory(outputDirPath);
    }

    // Create the path where the R.java files will be compiled.
    Path rDotJavaBin = getPathToCompiledRDotJavaFiles();
    steps.add(new MakeCleanDirectoryStep(rDotJavaBin));

    JavacStep javac = UberRDotJavaUtil.createJavacStepForUberRDotJavaFiles(
        mergeStep.getRDotJavaFiles(),
        rDotJavaBin,
        javacOptions,
        getBuildTarget());
    steps.add(javac);

    Path rDotJavaClassesTxt = getPathToRDotJavaClassesTxt();
    steps.add(new MakeCleanDirectoryStep(rDotJavaClassesTxt.getParent()));
    steps.add(new AccumulateClassNamesStep(Optional.of(rDotJavaBin), rDotJavaClassesTxt));

    // Ensure the generated R.txt, R.java, and R.class files are also recorded.
    buildableContext.recordArtifactsInDirectory(rDotTxtDir);
    buildableContext.recordArtifactsInDirectory(rDotJavaSrc);
    buildableContext.recordArtifactsInDirectory(rDotJavaBin);
    buildableContext.recordArtifact(rDotJavaClassesTxt);
  }

  private Path getPathForNativeStringInfoDirectory() {
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_string_source_map__");
  }

  /**
   * @return path to the directory where the {@code R.class} files can be found after this rule is
   *     built.
   */
  public Path getPathToCompiledRDotJavaFiles() {
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_uber_rdotjava_bin__");
  }

  public Path getPathToRDotTxtDir() {
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_res_symbols__");
  }

  /**
   * This directory contains both the generated {@code R.java} files under a directory path that
   * matches the corresponding package structure.
   */
  Path getPathToGeneratedRDotJavaSrcFiles() {
    return getPathToGeneratedRDotJavaSrcFiles(getBuildTarget());
  }

  Path getPathToRDotJavaDexFiles() {
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_uber_rdotjava_dex__");
  }

  Path getPathToRDotJavaDex() {
    return getPathToRDotJavaDexFiles().resolve("classes.dex.jar");
  }

  Path getPathToRDotJavaClassesTxt() {
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_uber_rdotjava_classes__")
        .resolve("classes.txt");
  }

  @VisibleForTesting
  static Path getPathToGeneratedRDotJavaSrcFiles(BuildTarget buildTarget) {
    return BuildTargets.getBinPath(buildTarget, "__%s_uber_rdotjava_src__");
  }
}
