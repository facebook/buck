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
import com.facebook.buck.android.UberRDotJavaBuildable.BuildOutput;
import com.facebook.buck.java.JavacInMemoryStep;
import com.facebook.buck.model.BuildTarget;
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
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.MorePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * </ul>
 * <p>
 * Clients of this Buildable may need to know:
 * <ul>
 *   <li>The set of res/ directories that was used to calculate the R.java file. (These are needed
 *       as arguments to aapt to create the unsigned APK, as well as arguments to create a
 *       ProGuard config, if appropriate.)
 *   <li>The set of non-english {@code strings.xml} files identified by the resource filter.
 *   <li>The path to the R.java file.
 * </ul>
 */
public class UberRDotJavaBuildable extends AbstractBuildable implements
    InitializableFromDisk<BuildOutput> {

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

  @Nullable private BuildOutput buildOutput;

  UberRDotJavaBuildable(BuildTarget buildTarget,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourceFilter,
      AndroidResourceDepsFinder androidResourceDepsFinder) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.resourceCompressionMode = Preconditions.checkNotNull(resourceCompressionMode);
    this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder
        .set("resourceCompressionMode", resourceCompressionMode.toString())
        .set("resourceFilter", resourceFilter.getDescription());
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public String getPathToOutputFile() {
    return null;
  }

  public ImmutableSet<String> getResDirectories() {
    return getBuildOutput().resDirectories;
  }

  public ImmutableSet<Path> getNonEnglishStringFiles() {
    return getBuildOutput().nonEnglishStringFiles;
  }

  BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    AndroidTransitiveDependencies transitiveDependencies = getAndroidTransitiveDependencies();
    final Set<String> rDotJavaPackages = transitiveDependencies.rDotJavaPackages;
    final ImmutableSet<String> resDirectories;
    final Supplier<ImmutableSet<String>> nonEnglishStringFiles;
    if (requiresResourceFilter()) {
      final FilterResourcesStep filterResourcesStep = createFilterResourcesStep(
          transitiveDependencies.resDirectories);
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
      for (String outputResourceDir : resDirectories) {
        buildableContext.recordArtifactsInDirectory(Paths.get(outputResourceDir));
      }
    } else {
      resDirectories = transitiveDependencies.resDirectories;
      nonEnglishStringFiles = Suppliers.ofInstance(ImmutableSet.<String>of());
    }

    if (!resDirectories.isEmpty()) {
      generateAndCompileRDotJavaFiles(
          resDirectories, rDotJavaPackages, steps, buildableContext);
    }

    steps.add(new AbstractExecutionStep("record_build_output") {
      @Override
      public int execute(ExecutionContext context) {
        buildableContext.addMetadata(RES_DIRECTORIES_KEY, resDirectories);
        buildableContext.addMetadata(NON_ENGLISH_STRING_FILES_KEY, nonEnglishStringFiles.get());
        return 0;
      }
    });

    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return new BuildOutput(
      ImmutableSet.copyOf(onDiskBuildInfo.getValues(RES_DIRECTORIES_KEY).get()),
      FluentIterable.from(onDiskBuildInfo.getValues(NON_ENGLISH_STRING_FILES_KEY).get())
          .transform(MorePaths.TO_PATH)
          .toSet()
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

    public BuildOutput(ImmutableSet<String> resDirectories,
        ImmutableSet<Path> nonEnglishStringFiles) {
      this.resDirectories = Preconditions.checkNotNull(resDirectories);
      this.nonEnglishStringFiles = Preconditions.checkNotNull(nonEnglishStringFiles);
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
   */
  @VisibleForTesting
  FilterResourcesStep createFilterResourcesStep(Set<String> resourceDirectories) {
    ImmutableBiMap.Builder<String, String> filteredResourcesDirMapBuilder = ImmutableBiMap.builder();
    String resDestinationBasePath = getResDestinationBasePath();
    int count = 0;
    for (String resDir : resourceDirectories) {
      filteredResourcesDirMapBuilder.put(resDir,
          Paths.get(resDestinationBasePath, String.valueOf(count++)).toString());
    }

    ImmutableBiMap<String, String> resSourceToDestDirMap = filteredResourcesDirMapBuilder.build();
    FilterResourcesStep.Builder filterResourcesStepBuilder = FilterResourcesStep.builder()
        .setInResToOutResDirMap(resSourceToDestDirMap)
        .setResourceFilter(resourceFilter);

    if (isStoreStringsAsAssets()) {
      filterResourcesStepBuilder.enableStringsFilter();
    }

    return filterResourcesStepBuilder.build();
  }

  /**
   * Adds the commands to generate and compile the {@code R.java} files. The {@code R.class} files
   * will be written to {@link #getPathToCompiledRDotJavaFiles()}.
   */
  private void generateAndCompileRDotJavaFiles(
      Set<String> resDirectories,
      Set<String> rDotJavaPackages,
      ImmutableList.Builder<Step> commands,
      BuildableContext buildableContext) {
    // Create the path where the R.java files will be generated.
    String rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();
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
    String rDotJavaBin = getPathToCompiledRDotJavaFiles();
    commands.add(new MakeCleanDirectoryStep(rDotJavaBin));

    // Compile the R.java files.
    Set<String> javaSourceFilePaths = Sets.newHashSet();
    for (String rDotJavaPackage : rDotJavaPackages) {
      String path = rDotJavaSrc + "/" + rDotJavaPackage.replace('.', '/') + "/R.java";
      javaSourceFilePaths.add(path);
    }
    JavacInMemoryStep javac = UberRDotJavaUtil.createJavacInMemoryCommandForRDotJavaFiles(
        javaSourceFilePaths, rDotJavaBin);
    commands.add(javac);

    // Ensure the generated R.txt, R.java, and R.class files are also recorded.
    buildableContext.recordArtifactsInDirectory(Paths.get(rDotJavaSrc));
    buildableContext.recordArtifactsInDirectory(Paths.get(rDotJavaBin));
  }

  /**
   * @return path to the directory where the {@code R.class} files can be found after this rule is
   *     built.
   */
  public String getPathToCompiledRDotJavaFiles() {
    return String.format("%s/%s__%s_uber_rdotjava_bin__",
        BuckConstant.BIN_DIR,
        buildTarget.getBasePathWithSlash(),
        buildTarget.getShortName());
  }

  /**
   * This directory contains both the generated {@code R.java} and {@code R.txt} files.
   * The {@code R.txt} file will be in the root of the directory whereas the {@code R.java} files
   * will be under a directory path that matches the corresponding package structure.
   */
  String getPathToGeneratedRDotJavaSrcFiles() {
    return String.format("%s/%s__%s_uber_rdotjava_src__",
        BuckConstant.BIN_DIR,
        buildTarget.getBasePathWithSlash(),
        buildTarget.getShortName());
  }

  private String getResDestinationBasePath() {
    return String.format("%s/%s__filtered__%s__",
        BuckConstant.BIN_DIR,
        buildTarget.getBasePathWithSlash(),
        buildTarget.getShortName());
  }

  public static Builder newUberRDotJavaBuildableBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  static class Builder extends AbstractBuildable.Builder {

    @Nullable private BuildTarget buildTarget;
    @Nullable private ResourceCompressionMode resourceCompressionMode;
    @Nullable private FilterResourcesStep.ResourceFilter resourceFilter;
    @Nullable private AndroidResourceDepsFinder androidResourceDepsFinder;

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

    public Builder setAllParams(BuildTarget buildTarget,
        ResourceCompressionMode resourceCompressionMode,
        ResourceFilter resourceFilter,
        AndroidResourceDepsFinder androidResourceDepsFinder) {
      this.buildTarget = buildTarget;
      this.resourceCompressionMode = resourceCompressionMode;
      this.resourceFilter = resourceFilter;
      this.androidResourceDepsFinder = androidResourceDepsFinder;

      // Add the android_resource rules as deps.
      for (HasAndroidResourceDeps dep : androidResourceDepsFinder.getAndroidResourcesUnsorted()) {
        addDep(dep.getBuildTarget());
      }
      return this;
    }

    @Override
    protected UberRDotJavaBuildable newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new UberRDotJavaBuildable(buildTarget,
          resourceCompressionMode,
          resourceFilter,
          androidResourceDepsFinder);
    }
  }
}
