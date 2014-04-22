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
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
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
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
public class UberRDotJava extends AbstractBuildable implements
    AbiRule, InitializableFromDisk<BuildOutput> {

  public static final String R_DOT_JAVA_LINEAR_ALLOC_SIZE = "r_dot_java_linear_alloc_size";

  private final BuildTarget buildTarget;
  private final FilteredResourcesProvider filteredResourcesProvider;
  private final JavacOptions javacOptions;
  private final AndroidResourceDepsFinder androidResourceDepsFinder;
  private final boolean rDotJavaNeedsDexing;
  private final boolean shouldBuildStringSourceMap;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  UberRDotJava(BuildTarget buildTarget,
      FilteredResourcesProvider filteredResourcesProvider,
      JavacOptions javacOptions,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      boolean rDotJavaNeedsDexing,
      boolean shouldBuildStringSourceMap) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.filteredResourcesProvider = Preconditions.checkNotNull(filteredResourcesProvider);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
    this.rDotJavaNeedsDexing = rDotJavaNeedsDexing;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    javacOptions.appendToRuleKey(builder);
    return builder
        .set("rDotJavaNeedsDexing", rDotJavaNeedsDexing)
        .set("shouldBuildStringSourceMap", shouldBuildStringSourceMap);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
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

  public Map<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().rDotJavaClassesHash;
  }

  BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public List<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext
  ) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    AndroidResourceDetails androidResourceDetails =
        androidResourceDepsFinder.getAndroidResourceDetails();
    ImmutableSet<String> rDotJavaPackages = androidResourceDetails.rDotJavaPackages;
    ImmutableSet<Path> resDirectories = filteredResourcesProvider.getResDirectories();

    if (!resDirectories.isEmpty()) {
      generateAndCompileRDotJavaFiles(resDirectories, rDotJavaPackages, steps, buildableContext);
    }

    if (rDotJavaNeedsDexing && !resDirectories.isEmpty()) {
      Path rDotJavaDexDir = getPathToRDotJavaDexFiles();
      steps.add(new MakeCleanDirectoryStep(rDotJavaDexDir));
      steps.add(new DxStep(
              getPathToRDotJavaDex(),
              Collections.singleton(getPathToCompiledRDotJavaFiles()),
              EnumSet.of(DxStep.Option.NO_OPTIMIZE)));

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
    Map<String, HashCode> classesHash = ImmutableMap.of();
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
    return HasAndroidResourceDeps.ABI_HASHER.apply(androidResourceDepsFinder.getAndroidResources());
  }

  public static class BuildOutput {
    private final Optional<Integer> rDotJavaDexLinearAllocEstimate;
    private final Map<String, HashCode> rDotJavaClassesHash;

    public BuildOutput(
        Optional<Integer> rDotJavaDexLinearAllocSizeEstimate,
        Map<String, HashCode> rDotJavaClassesHash) {
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
      Set<Path> resDirectories,
      Set<String> rDotJavaPackages,
      ImmutableList.Builder<Step> steps,
      final BuildableContext buildableContext) {
    // Create the path where the R.java files will be generated.
    Path rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();
    steps.add(new MakeCleanDirectoryStep(rDotJavaSrc));

    // Generate the R.java files.
    GenRDotJavaStep genRDotJava = new GenRDotJavaStep(
        resDirectories,
        rDotJavaSrc,
        rDotJavaPackages.iterator().next(),
        /* isTempRDotJava */ false,
        rDotJavaPackages);
    steps.add(genRDotJava);

    if (shouldBuildStringSourceMap) {
      // Make sure we have an output directory
      Path outputDirPath = getPathForNativeStringInfoDirectory();
      steps.add(new MakeCleanDirectoryStep(outputDirPath));

      // Add the step that parses R.txt and all the strings.xml files, and
      // produces a JSON with android resource id's and xml paths for each string resource.
      GenStringSourceMapStep genNativeStringInfo = new GenStringSourceMapStep(
          rDotJavaSrc,
          resDirectories,
          outputDirPath);
      steps.add(genNativeStringInfo);

      // Cache the generated strings.json file, it will be stored inside outputDirPath
      buildableContext.recordArtifactsInDirectory(outputDirPath);
    }

    // Compile the R.java files.
    Set<SourcePath> javaSourceFilePaths = Sets.newHashSet();
    for (String rDotJavaPackage : rDotJavaPackages) {
      Path path = rDotJavaSrc.resolve(rDotJavaPackage.replace('.', '/')).resolve("R.java");
      javaSourceFilePaths.add(new PathSourcePath(path));
    }

    // Create the path where the R.java files will be compiled.
    Path rDotJavaBin = getPathToCompiledRDotJavaFiles();
    steps.add(new MakeCleanDirectoryStep(rDotJavaBin));

    JavacStep javac = UberRDotJavaUtil.createJavacStepForUberRDotJavaFiles(
        javaSourceFilePaths,
        rDotJavaBin,
        javacOptions,
        buildTarget);
    steps.add(javac);

    Path rDotJavaClassesTxt = getPathToRDotJavaClassesTxt();
    steps.add(new MakeCleanDirectoryStep(rDotJavaClassesTxt.getParent()));
    steps.add(new AccumulateClassNamesStep(Optional.of(rDotJavaBin), rDotJavaClassesTxt));

    // Ensure the generated R.txt, R.java, and R.class files are also recorded.
    buildableContext.recordArtifactsInDirectory(rDotJavaSrc);
    buildableContext.recordArtifactsInDirectory(rDotJavaBin);
    buildableContext.recordArtifact(rDotJavaClassesTxt);
  }

  private Path getPathForNativeStringInfoDirectory() {
    return BuildTargets.getBinPath(buildTarget, "__%s_string_source_map__");
  }

  /**
   * @return path to the directory where the {@code R.class} files can be found after this rule is
   *     built.
   */
  public Path getPathToCompiledRDotJavaFiles() {
    return BuildTargets.getBinPath(buildTarget, "__%s_uber_rdotjava_bin__");
  }

  /**
   * This directory contains both the generated {@code R.java} and {@code R.txt} files.
   * The {@code R.txt} file will be in the root of the directory whereas the {@code R.java} files
   * will be under a directory path that matches the corresponding package structure.
   */
  Path getPathToGeneratedRDotJavaSrcFiles() {
    return BuildTargets.getBinPath(buildTarget, "__%s_uber_rdotjava_src__");
  }

  Path getPathToRDotJavaDexFiles() {
    return BuildTargets.getBinPath(buildTarget, "__%s_uber_rdotjava_dex__");
  }

  Path getPathToRDotJavaDex() {
    return getPathToRDotJavaDexFiles().resolve("classes.dex.jar");
  }

  Path getPathToRDotJavaClassesTxt() {
    return BuildTargets.getBinPath(buildTarget, "__%s_uber_rdotjava_classes__")
        .resolve("classes.txt");
  }
}
