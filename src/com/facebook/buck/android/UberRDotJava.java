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

import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
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
import com.facebook.buck.util.MorePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Buildable that is responsible for:
 * <ul>
 *   <li>Taking a set of res/ directories and applying an optional resource filter to them,
 *       ultimately generating the final set of res/ directories whose contents should be included
 *       in an APK.
 *   <li>Generating a single {@code R.java} file from those directories (aka the uber-R.java).
 *   <li>Compiling the single {@code R.java} file.
 *   <li>Dexing the single {@code R.java} to a {@code classes.dex.jar} file (Optional).
 * </ul>
 * <p>
 * Clients of this Buildable may need to know:
 * <ul>
 *   <li>The set of res/ directories that was used to calculate the R.java file. (These are needed
 *       as arguments to aapt to create the unsigned APK, as well as arguments to create a
 *       ProGuard config, if appropriate.)
 *   <li>The set of non-english {@code strings.xml} files identified by the resource filter.
 *   <li>The path to the {@code R.java} file.
 * </ul>
 */
public class UberRDotJava extends AbstractBuildable implements
    InitializableFromDisk<BuildOutput> {

  public static final String R_DOT_JAVA_LINEAR_ALLOC_SIZE = "r_dot_java_linear_alloc_size";
  private static final String RES_DIRECTORIES_KEY = "res_directories";
  private static final String NON_ENGLISH_STRING_FILES_KEY = "non_english_string_files";

  static enum ResourceCompressionMode {
    DISABLED(/* isCompressResources */ false, /* isStoreStringsAsAssets */ false),
    ENABLED(/* isCompressResources */ true, /* isStoreStringsAsAssets */ false),
    ENABLED_WITH_STRINGS_AS_ASSETS(
      /* isCompressResources */ true,
      /* isStoreStringsAsAssets */ true),
    ;

    private final boolean isCompressResources;
    private final boolean isStoreStringsAsAssets;

    private ResourceCompressionMode(boolean isCompressResources, boolean isStoreStringsAsAssets) {
      this.isCompressResources = isCompressResources;
      this.isStoreStringsAsAssets = isStoreStringsAsAssets;
    }

    public boolean isCompressResources() {
      return isCompressResources;
    }

    public boolean isStoreStringsAsAssets() {
      return isStoreStringsAsAssets;
    }
  }

  private final BuildTarget buildTarget;
  private final ResourceCompressionMode resourceCompressionMode;
  private final FilterResourcesStep.ResourceFilter resourceFilter;
  private final AndroidResourceDepsFinder androidResourceDepsFinder;
  private final boolean rDotJavaNeedsDexing;

  @Nullable private BuildOutput buildOutput;

  UberRDotJava(BuildTarget buildTarget,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourceFilter,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      boolean rDotJavaNeedsDexing) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.resourceCompressionMode = Preconditions.checkNotNull(resourceCompressionMode);
    this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
    this.rDotJavaNeedsDexing = rDotJavaNeedsDexing;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder
        .set("resourceCompressionMode", resourceCompressionMode.toString())
        .set("resourceFilter", resourceFilter.getDescription())
        .set("rDotJavaNeedsDexing", rDotJavaNeedsDexing);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  public ImmutableSet<String> getResDirectories() {
    return getBuildOutput().resDirectories;
  }

  public ImmutableSet<Path> getNonEnglishStringFiles() {
    return getBuildOutput().nonEnglishStringFiles;
  }

  public Optional<DexWithClasses> getRDotJavaDexWithClasses() {
    Preconditions.checkState(rDotJavaNeedsDexing,
        "Error trying to get R.java dex file: R.java is not supposed to be dexed.");

    final Optional<Integer> linearAllocSizeEstimate = getBuildOutput().rDotJavaDexLinearAllocEstimate;
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
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    AndroidResourceDetails androidResourceDetails =
        androidResourceDepsFinder.getAndroidResourceDetails();
    final Set<String> rDotJavaPackages = androidResourceDetails.rDotJavaPackages;
    final ImmutableSet<Path> resDirectories;
    final Supplier<ImmutableSet<String>> nonEnglishStringFiles;
    if (requiresResourceFilter()) {
      final FilterResourcesStep filterResourcesStep = createFilterResourcesStep(
          androidResourceDetails.resDirectories,
          androidResourceDetails.whitelistedStringDirs);
      steps.add(filterResourcesStep);

      resDirectories = filterResourcesStep.getOutputResourceDirs();
      nonEnglishStringFiles = new Supplier<ImmutableSet<String>>() {
        @Override
        public ImmutableSet<String> get() {
          return FluentIterable.from(filterResourcesStep.getNonEnglishStringFiles())
              .transform(Functions.toStringFunction())
              .toSet();
        }
      };
      for (Path outputResourceDir : resDirectories) {
        buildableContext.recordArtifactsInDirectory(outputResourceDir);
      }
    } else {
      resDirectories = androidResourceDetails.resDirectories;
      nonEnglishStringFiles = Suppliers.ofInstance(ImmutableSet.<String>of());
    }

    if (!resDirectories.isEmpty()) {
      generateAndCompileRDotJavaFiles(
          resDirectories, rDotJavaPackages, steps, buildableContext);
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
        buildableContext.addMetadata(
            RES_DIRECTORIES_KEY,
            Iterables.transform(resDirectories, Functions.toStringFunction()));
        buildableContext.addMetadata(
            NON_ENGLISH_STRING_FILES_KEY,
            nonEnglishStringFiles.get());

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

    return new BuildOutput(
      ImmutableSet.copyOf(onDiskBuildInfo.getValues(RES_DIRECTORIES_KEY).get()),
      FluentIterable.from(onDiskBuildInfo.getValues(NON_ENGLISH_STRING_FILES_KEY).get())
          .transform(MorePaths.TO_PATH)
          .toSet(),
      linearAllocSize
    );
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
    private final ImmutableSet<String> resDirectories;
    private final ImmutableSet<Path> nonEnglishStringFiles;
    private final Optional<Integer> rDotJavaDexLinearAllocEstimate;

    public BuildOutput(ImmutableSet<String> resDirectories,
        ImmutableSet<Path> nonEnglishStringFiles,
        Optional<Integer> rDotJavaDexLinearAllocSizeEstimate) {
      this.resDirectories = Preconditions.checkNotNull(resDirectories);
      this.nonEnglishStringFiles = Preconditions.checkNotNull(nonEnglishStringFiles);
      this.rDotJavaDexLinearAllocEstimate =
          Preconditions.checkNotNull(rDotJavaDexLinearAllocSizeEstimate);
    }
  }

  AndroidTransitiveDependencies getAndroidTransitiveDependencies() {
    return androidResourceDepsFinder.getAndroidTransitiveDependencies();
  }

  private boolean requiresResourceFilter() {
    return resourceFilter.isEnabled() || isStoreStringsAsAssets();
  }

  boolean isStoreStringsAsAssets() {
    return resourceCompressionMode.isStoreStringsAsAssets();
  }

  /**
   * Sets up filtering of resources, images/drawables and strings in particular, based on build
   * rule parameters {@link #resourceFilter} and {@link #isStoreStringsAsAssets}.
   *
   * {@link com.facebook.buck.android.FilterResourcesStep.ResourceFilter} {@code resourceFilter}
   * determines which drawables end up in the APK (based on density - mdpi, hdpi etc), and also
   * whether higher density drawables get scaled down to the specified density (if not present).
   *
   * {@code isStoreStringsAsAssets} determines whether non-english string resources are packaged
   * separately as assets (and not bundled together into the {@code resources.arsc} file).
   *
   * @param whitelistedStringDirs overrides storing non-english strings as assets for resources
   *     inside these directories.
   */
  @VisibleForTesting
  FilterResourcesStep createFilterResourcesStep(Set<Path> resourceDirectories,
      ImmutableSet<Path> whitelistedStringDirs) {
    ImmutableBiMap.Builder<Path, Path> filteredResourcesDirMapBuilder = ImmutableBiMap.builder();
    String resDestinationBasePath = getResDestinationBasePath();
    int count = 0;
    for (Path resDir : resourceDirectories) {
      filteredResourcesDirMapBuilder.put(resDir,
          Paths.get(resDestinationBasePath, String.valueOf(count++)));
    }

    ImmutableBiMap<Path, Path> resSourceToDestDirMap = filteredResourcesDirMapBuilder.build();
    FilterResourcesStep.Builder filterResourcesStepBuilder = FilterResourcesStep.builder()
        .setInResToOutResDirMap(resSourceToDestDirMap)
        .setResourceFilter(resourceFilter);

    if (isStoreStringsAsAssets()) {
      filterResourcesStepBuilder.enableStringsFilter();
      filterResourcesStepBuilder.setWhitelistedStringDirs(whitelistedStringDirs);
    }

    return filterResourcesStepBuilder.build();
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

    // TODO: add command to build the string source map file...


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

  private String getResDestinationBasePath() {
    return BuildTargets.getBinPath(buildTarget, "__filtered__%s__").toString();
  }

  public static Builder newUberRDotJavaBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  static class Builder extends AbstractBuildable.Builder {

    @Nullable private ResourceCompressionMode resourceCompressionMode;
    @Nullable private FilterResourcesStep.ResourceFilter resourceFilter;
    @Nullable private AndroidResourceDepsFinder androidResourceDepsFinder;
    private boolean rDotJavaNeedsDexing = false;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType._UBER_R_DOT_JAVA;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder setResourceCompressionMode(ResourceCompressionMode mode) {
      this.resourceCompressionMode = mode;
      return this;
    }

    public Builder setResourceFilter(ResourceFilter resourceFilter) {
      this.resourceFilter = resourceFilter;
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

    @Override
    protected UberRDotJava newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new UberRDotJava(buildTarget,
          resourceCompressionMode,
          resourceFilter,
          androidResourceDepsFinder,
          rDotJavaNeedsDexing);
    }
  }
}
