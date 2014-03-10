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
import com.facebook.buck.java.JavacInMemoryStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

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
    InitializableFromDisk<BuildOutput> {

  public static final String R_DOT_JAVA_LINEAR_ALLOC_SIZE = "r_dot_java_linear_alloc_size";

  private final BuildTarget buildTarget;
  private final ResourcesFilter resourcesFilter;
  private final AndroidResourceDepsFinder androidResourceDepsFinder;
  private final boolean rDotJavaNeedsDexing;
  private final boolean shouldBuildStringSourceMap;

  @Nullable private BuildOutput buildOutput;

  UberRDotJava(BuildTarget buildTarget,
      ResourcesFilter resourcesFilter,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      boolean rDotJavaNeedsDexing,
      boolean shouldBuildStringSourceMap) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.resourcesFilter = Preconditions.checkNotNull(resourcesFilter);
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
    this.rDotJavaNeedsDexing = rDotJavaNeedsDexing;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
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
        getBuildOutput().rDotJavaDexLinearAllocEstimate;
    if (!linearAllocSizeEstimate.isPresent()) {
      return Optional.absent();
    }

    return Optional.<DexWithClasses>of(new DexWithClasses() {
      @Override
      public Path getPathToDexFile() {
        return DexRDotJavaStep.getPathToDexFile(buildTarget);
      }

      @Override
      public ImmutableSet<String> getClassNames() {
        throw new RuntimeException("Since R.java is unconditionally packed in the primary dex, no" +
            "one should call this method.");
      }

      @Override
      public int getSizeEstimate() {
        return linearAllocSizeEstimate.get();
      }
    });
  }

  BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public List<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext
  ) throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    AndroidResourceDetails androidResourceDetails =
        androidResourceDepsFinder.getAndroidResourceDetails();
    ImmutableSet<String> rDotJavaPackages = androidResourceDetails.rDotJavaPackages;
    ImmutableSet<Path> resDirectories = resourcesFilter.getResDirectories();

    if (!resDirectories.isEmpty()) {
      generateAndCompileRDotJavaFiles(resDirectories, rDotJavaPackages, steps, buildableContext);
    }

    final Optional<DexRDotJavaStep> dexRDotJava = rDotJavaNeedsDexing && !resDirectories.isEmpty()
        ? Optional.of(DexRDotJavaStep.create(buildTarget, getPathToCompiledRDotJavaFiles()))
        : Optional.<DexRDotJavaStep>absent();
    if (dexRDotJava.isPresent()) {
      steps.add(dexRDotJava.get());
      buildableContext.recordArtifact(DexRDotJavaStep.getPathToDexFile(buildTarget));
    }

    steps.add(new AbstractExecutionStep("record_build_output") {
      @Override
      public int execute(ExecutionContext context) {
        if (dexRDotJava.isPresent()) {
          buildableContext.addMetadata(
              R_DOT_JAVA_LINEAR_ALLOC_SIZE,
              dexRDotJava.get().getLinearAllocSizeEstimate().toString());
        }
        return 0;
      }
    });

    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Optional<String> linearAllocSizeValue = onDiskBuildInfo.getValue(R_DOT_JAVA_LINEAR_ALLOC_SIZE);
    Optional<Integer> linearAllocSize = linearAllocSizeValue.isPresent()
        ? Optional.of(Integer.parseInt(linearAllocSizeValue.get()))
        : Optional.<Integer>absent();

    return new BuildOutput(linearAllocSize);
  }

  @Override
  public void setBuildOutput(BuildOutput buildOutput) {
    Preconditions.checkState(this.buildOutput == null,
        "buildOutput should not already be set for %s.",
        this);
    this.buildOutput = buildOutput;
  }

  @Override
  public BuildOutput getBuildOutput() {
    Preconditions.checkState(buildOutput != null, "buildOutput must already be set for %s.", this);
    return buildOutput;
  }

  public static class BuildOutput {
    private final Optional<Integer> rDotJavaDexLinearAllocEstimate;

    public BuildOutput(Optional<Integer> rDotJavaDexLinearAllocSizeEstimate) {
      this.rDotJavaDexLinearAllocEstimate =
          Preconditions.checkNotNull(rDotJavaDexLinearAllocSizeEstimate);
    }
  }

  /**
   * Adds the commands to generate and compile the {@code R.java} files. The {@code R.class} files
   * will be written to {@link #getPathToCompiledRDotJavaFiles()}.
   */
  private void generateAndCompileRDotJavaFiles(
      Set<Path> resDirectories,
      Set<String> rDotJavaPackages,
      ImmutableList.Builder<Step> commands,
      BuildableContext buildableContext) {
    // Create the path where the R.java files will be generated.
    Path rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();
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
    Path rDotJavaBin = getPathToCompiledRDotJavaFiles();
    commands.add(new MakeCleanDirectoryStep(rDotJavaBin));

    if (shouldBuildStringSourceMap) {
      // Make sure we have an output directory
      Path outputDirPath = getPathForNativeStringInfoDirectory();
      commands.add(new MakeCleanDirectoryStep(outputDirPath));

      // Add the step that parses R.txt and all the strings.xml files, and
      // produces a JSON with android resource id's and xml paths for each string resource.
      GenStringSourceMapStep genNativeStringInfo = new GenStringSourceMapStep(
          rDotJavaSrc,
          resDirectories,
          outputDirPath);
      commands.add(genNativeStringInfo);

      // Cache the generated strings.json file, it will be stored inside outputDirPath
      buildableContext.recordArtifactsInDirectory(outputDirPath);
    }

    // Compile the R.java files.
    Set<Path> javaSourceFilePaths = Sets.newHashSet();
    for (String rDotJavaPackage : rDotJavaPackages) {
      Path path = rDotJavaSrc.resolve(rDotJavaPackage.replace('.', '/')).resolve("R.java");
      javaSourceFilePaths.add(path);
    }
    JavacInMemoryStep javac = UberRDotJavaUtil.createJavacInMemoryCommandForRDotJavaFiles(
        javaSourceFilePaths, rDotJavaBin);
    commands.add(javac);

    // Ensure the generated R.txt, R.java, and R.class files are also recorded.
    buildableContext.recordArtifactsInDirectory(rDotJavaSrc);
    buildableContext.recordArtifactsInDirectory(rDotJavaBin);
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

  public static Builder newUberRDotJavaBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  static class Builder extends AbstractBuildable.Builder {

    @Nullable private ResourcesFilter resourcesFilter;
    @Nullable private AndroidResourceDepsFinder androidResourceDepsFinder;
    private boolean rDotJavaNeedsDexing = false;
    private boolean shouldBuildStringSourceMap = false;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType.UBER_R_DOT_JAVA;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder setResourcesFilter(ResourcesFilter resourcesFilter) {
      this.resourcesFilter = resourcesFilter;
      addDep(resourcesFilter.getBuildTarget());
      return this;
    }

    public Builder setAndroidResourceDepsFinder(AndroidResourceDepsFinder resourceDepsFinder) {
      this.androidResourceDepsFinder = resourceDepsFinder;
      // Add the android_resource rules as deps.
      for (HasAndroidResourceDeps dep : androidResourceDepsFinder.getAndroidResources()) {
        addDep(dep.getBuildTarget());
      }

      return this;
    }

    public Builder setRDotJavaNeedsDexing(boolean rDotJavaNeedsDexing) {
      this.rDotJavaNeedsDexing = rDotJavaNeedsDexing;
      return this;
    }

    public Builder setBuildStringSourceMap(boolean shouldBuildStringSourceMap) {
      this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
      return this;
    }

    @Override
    protected UberRDotJava newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new UberRDotJava(buildTarget,
          resourcesFilter,
          androidResourceDepsFinder,
          rDotJavaNeedsDexing,
          shouldBuildStringSourceMap);
    }
  }
}
