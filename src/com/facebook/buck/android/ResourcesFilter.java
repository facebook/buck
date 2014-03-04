/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
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
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Buildable that is responsible for taking a set of res/ directories and applying an optional
 * resource filter to them, ultimately generating the final set of res/ directories whose contents
 * should be included in an APK.
 * <p>
 * Clients of this Buildable may need to know:
 * <ul>
 *   <li>The set of res/ directories that was used to calculate the R.java file. (These are needed
 *       as arguments to aapt to create the unsigned APK, as well as arguments to create a
 *       ProGuard config, if appropriate.)
 *   <li>The set of non-english {@code strings.xml} files identified by the resource filter.
 * </ul>
 */
public class ResourcesFilter extends AbstractBuildable
    implements InitializableFromDisk<ResourcesFilter.BuildOutput> {

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
  private final AndroidResourceDepsFinder androidResourceDepsFinder;
  private final ResourceCompressionMode resourceCompressionMode;
  private final FilterResourcesStep.ResourceFilter resourceFilter;

  @Nullable
  private BuildOutput buildOutput;

  public ResourcesFilter(
      BuildTarget buildTarget,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      ResourceCompressionMode resourceCompressionMode,
      FilterResourcesStep.ResourceFilter resourceFilter) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
    this.resourceCompressionMode = Preconditions.checkNotNull(resourceCompressionMode);
    this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
  }

  public ImmutableSet<Path> getResDirectories() {
    return getBuildOutput().resDirectories;
  }

  public ImmutableSet<Path> getNonEnglishStringFiles() {
    return getBuildOutput().nonEnglishStringFiles;
  }

  AndroidTransitiveDependencies getAndroidTransitiveDependencies() {
    return androidResourceDepsFinder.getAndroidTransitiveDependencies();
  }

  BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder
        .set("resourceCompressionMode", resourceCompressionMode.toString())
        .set("resourceFilter", resourceFilter.getDescription());
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    AndroidResourceDetails androidResourceDetails =
        androidResourceDepsFinder.getAndroidResourceDetails();
    final ImmutableSet<Path> resDirectories;
    final Supplier<ImmutableSet<Path>> nonEnglishStringFiles;
    if (requiresResourceFilter()) {
      final FilterResourcesStep filterResourcesStep = createFilterResourcesStep(
          androidResourceDetails.resDirectories,
          androidResourceDetails.whitelistedStringDirs);
      steps.add(filterResourcesStep);

      resDirectories = filterResourcesStep.getOutputResourceDirs();
      nonEnglishStringFiles = new Supplier<ImmutableSet<Path>>() {
        @Override
        public ImmutableSet<Path> get() {
          return filterResourcesStep.getNonEnglishStringFiles();
        }
      };
      for (Path outputResourceDir : resDirectories) {
        buildableContext.recordArtifactsInDirectory(outputResourceDir);
      }
    } else {
      resDirectories = androidResourceDetails.resDirectories;
      nonEnglishStringFiles = Suppliers.ofInstance(ImmutableSet.<Path>of());
    }

    steps.add(new AbstractExecutionStep("record_build_output") {
      @Override
      public int execute(ExecutionContext context) {
        buildableContext.addMetadata(
            RES_DIRECTORIES_KEY,
            Iterables.transform(resDirectories, Functions.toStringFunction()));
        buildableContext.addMetadata(
            NON_ENGLISH_STRING_FILES_KEY,
            Iterables.transform(nonEnglishStringFiles.get(), Functions.toStringFunction()));
        return 0;
      }
    });

    return steps.build();
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

  private boolean requiresResourceFilter() {
    return resourceFilter.isEnabled() || isStoreStringsAsAssets();
  }

  boolean isStoreStringsAsAssets() {
    return resourceCompressionMode.isStoreStringsAsAssets();
  }

  private String getResDestinationBasePath() {
    return BuildTargets.getBinPath(buildTarget, "__filtered__%s__").toString();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    ImmutableSet<Path> resDirectories =
        FluentIterable.from(onDiskBuildInfo.getValues(RES_DIRECTORIES_KEY).get())
            .transform(MorePaths.TO_PATH)
            .toSet();
    ImmutableSet<Path> nonEnglishStringFiles =
        FluentIterable.from(onDiskBuildInfo.getValues(NON_ENGLISH_STRING_FILES_KEY).get())
            .transform(MorePaths.TO_PATH)
            .toSet();

    return new BuildOutput(resDirectories, nonEnglishStringFiles);
  }

  @Override
  public void setBuildOutput(BuildOutput buildOutput) throws IllegalStateException {
    Preconditions.checkState(this.buildOutput == null,
        "buildOutput should not already be set for %s.",
        this);
    this.buildOutput = buildOutput;
  }

  @Override
  public BuildOutput getBuildOutput() throws IllegalStateException {
    Preconditions.checkState(buildOutput != null, "buildOutput must already be set for %s.", this);
    return buildOutput;
  }

  public static class BuildOutput {
    private final ImmutableSet<Path> resDirectories;
    private final ImmutableSet<Path> nonEnglishStringFiles;

    public BuildOutput(
        ImmutableSet<Path> resDirectories,
        ImmutableSet<Path> nonEnglishStringFiles) {
      this.resDirectories = Preconditions.checkNotNull(resDirectories);
      this.nonEnglishStringFiles = Preconditions.checkNotNull(nonEnglishStringFiles);
    }
  }

  public static Builder newResourcesFilterBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  static class Builder extends AbstractBuildable.Builder {

    @Nullable private ResourceCompressionMode resourceCompressionMode;
    @Nullable private FilterResourcesStep.ResourceFilter resourceFilter;
    @Nullable private AndroidResourceDepsFinder androidResourceDepsFinder;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType.RESOURCES_FILTER;
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

    public Builder setResourceFilter(FilterResourcesStep.ResourceFilter resourceFilter) {
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

    @Override
    protected Buildable newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new ResourcesFilter(
          buildTarget,
          androidResourceDepsFinder,
          resourceCompressionMode,
          resourceFilter);
    }
  }
}
