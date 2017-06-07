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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.commons.compress.utils.IOUtils;

/**
 * Buildable that is responsible for taking a set of res/ directories and applying an optional
 * resource filter to them, ultimately generating the final set of res/ directories whose contents
 * should be included in an APK.
 *
 * <p>Clients of this Buildable may need to know:
 *
 * <ul>
 *   <li>The set of res/ directories that was used to calculate the R.java file. (These are needed
 *       as arguments to aapt to create the unsigned APK, as well as arguments to create a ProGuard
 *       config, if appropriate.)
 *   <li>The set of non-english {@code strings.xml} files identified by the resource filter.
 * </ul>
 */
public class ResourcesFilter extends AbstractBuildRule
    implements FilteredResourcesProvider, InitializableFromDisk<ResourcesFilter.BuildOutput> {

  private static final String RES_DIRECTORIES_KEY = "res_directories";
  private static final String STRING_FILES_KEY = "string_files";

  enum ResourceCompressionMode {
    DISABLED(/* isCompressResources */ false, /* isStoreStringsAsAssets */ false),
    ENABLED(/* isCompressResources */ true, /* isStoreStringsAsAssets */ false),
    ENABLED_STRINGS_ONLY(/* isCompressResources */ false, /* isStoreStringsAsAssets */ true),
    ENABLED_WITH_STRINGS_AS_ASSETS(
        /* isCompressResources */ true, /* isStoreStringsAsAssets */ true),
    ;

    private final boolean isCompressResources;
    private final boolean isStoreStringsAsAssets;

    ResourceCompressionMode(boolean isCompressResources, boolean isStoreStringsAsAssets) {
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

  // Rule key correctness is ensured by depping on all android_resource rules in
  // Builder.setAndroidResourceDepsFinder()
  private final ImmutableList<SourcePath> resDirectories;
  private final ImmutableSet<SourcePath> whitelistedStringDirs;
  @AddToRuleKey private final ImmutableSet<String> locales;
  @AddToRuleKey private final ResourceCompressionMode resourceCompressionMode;
  @AddToRuleKey private final FilterResourcesStep.ResourceFilter resourceFilter;
  @AddToRuleKey private final Optional<Arg> postFilterResourcesCmd;

  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  public ResourcesFilter(
      BuildRuleParams params,
      ImmutableList<SourcePath> resDirectories,
      ImmutableSet<SourcePath> whitelistedStringDirs,
      ImmutableSet<String> locales,
      ResourceCompressionMode resourceCompressionMode,
      FilterResourcesStep.ResourceFilter resourceFilter,
      Optional<Arg> postFilterResourcesCmd) {
    super(params);
    this.resDirectories = resDirectories;
    this.whitelistedStringDirs = whitelistedStringDirs;
    this.locales = locales;
    this.resourceCompressionMode = resourceCompressionMode;
    this.resourceFilter = resourceFilter;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
    this.postFilterResourcesCmd = postFilterResourcesCmd;
  }

  @Override
  public ImmutableList<Path> getResDirectories() {
    return buildOutputInitializer.getBuildOutput().resDirectories;
  }

  @Override
  public ImmutableList<Path> getStringFiles() {
    return buildOutputInitializer.getBuildOutput().stringFiles;
  }

  @Override
  public Optional<BuildRule> getResourceFilterRule() {
    return Optional.of(this);
  }

  @Override
  public boolean hasResources() {
    return !resDirectories.isEmpty();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    final ImmutableList.Builder<Path> filteredResDirectoriesBuilder = ImmutableList.builder();
    ImmutableSet<Path> whitelistedStringPaths =
        whitelistedStringDirs
            .stream()
            .map(
                sourcePath ->
                    getProjectFilesystem()
                        .relativize(context.getSourcePathResolver().getAbsolutePath(sourcePath)))
            .collect(MoreCollectors.toImmutableSet());
    ImmutableList<Path> resPaths =
        resDirectories
            .stream()
            .map(
                sourcePath ->
                    getProjectFilesystem()
                        .relativize(context.getSourcePathResolver().getAbsolutePath(sourcePath)))
            .collect(MoreCollectors.toImmutableList());
    ImmutableBiMap<Path, Path> inResDirToOutResDirMap =
        createInResDirToOutResDirMap(resPaths, filteredResDirectoriesBuilder);
    final FilterResourcesStep filterResourcesStep =
        createFilterResourcesStep(whitelistedStringPaths, locales, inResDirToOutResDirMap);
    steps.add(filterResourcesStep);

    final ImmutableList.Builder<Path> stringFilesBuilder = ImmutableList.builder();
    // The list of strings.xml files is only needed to build string assets
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      GetStringsFilesStep getStringsFilesStep =
          new GetStringsFilesStep(getProjectFilesystem(), resPaths, stringFilesBuilder);
      steps.add(getStringsFilesStep);
    }

    final ImmutableList<Path> filteredResDirectories = filteredResDirectoriesBuilder.build();
    for (Path outputResourceDir : filteredResDirectories) {
      buildableContext.recordArtifact(outputResourceDir);
    }

    postFilterResourcesCmd.ifPresent(
        cmd -> {
          OutputStream filterResourcesDataOutputStream = null;
          try {
            Path filterResourcesDataPath = getFilterResourcesDataPath();
            getProjectFilesystem().createParentDirs(filterResourcesDataPath);
            filterResourcesDataOutputStream =
                getProjectFilesystem().newFileOutputStream(filterResourcesDataPath);
            writeFilterResourcesData(filterResourcesDataOutputStream, inResDirToOutResDirMap);
            buildableContext.recordArtifact(filterResourcesDataPath);
            addPostFilterCommandSteps(
                cmd, context.getSourcePathResolver(), steps, filterResourcesDataPath);
          } catch (IOException e) {
            throw new RuntimeException("Could not generate/save filter resources data json", e);
          } finally {
            IOUtils.closeQuietly(filterResourcesDataOutputStream);
          }
        });

    steps.add(
        new AbstractExecutionStep("record_build_output") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) {
            buildableContext.addMetadata(
                RES_DIRECTORIES_KEY,
                filteredResDirectories
                    .stream()
                    .map(Object::toString)
                    .collect(MoreCollectors.toImmutableList()));
            buildableContext.addMetadata(
                STRING_FILES_KEY,
                stringFilesBuilder
                    .build()
                    .stream()
                    .map(Object::toString)
                    .collect(MoreCollectors.toImmutableList()));
            return StepExecutionResult.SUCCESS;
          }
        });

    return steps.build();
  }

  @VisibleForTesting
  void addPostFilterCommandSteps(
      Arg command,
      SourcePathResolver sourcePathResolver,
      ImmutableList.Builder<Step> steps,
      Path dataPath) {
    ImmutableList.Builder<String> commandLineBuilder = new ImmutableList.Builder<>();
    command.appendToCommandLine(commandLineBuilder, sourcePathResolver);
    commandLineBuilder.add(Escaper.escapeAsBashString(dataPath));
    String commandLine = Joiner.on(' ').join(commandLineBuilder.build());
    steps.add(new BashStep(getProjectFilesystem().getRootPath(), commandLine));
  }

  private Path getFilterResourcesDataPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/post_filter_resources_data.json");
  }

  @VisibleForTesting
  void writeFilterResourcesData(
      OutputStream outputStream, ImmutableBiMap<Path, Path> inResDirToOutResDirMap)
      throws IOException {
    ObjectMappers.WRITER.writeValue(
        outputStream, ImmutableMap.of("res_dir_map", inResDirToOutResDirMap));
  }

  /**
   * Sets up filtering of resources, images/drawables and strings in particular, based on build rule
   * parameters {@link #resourceFilter} and {@link #resourceCompressionMode}.
   *
   * <p>{@link com.facebook.buck.android.FilterResourcesStep.ResourceFilter} {@code resourceFilter}
   * determines which drawables end up in the APK (based on density - mdpi, hdpi etc), and also
   * whether higher density drawables get scaled down to the specified density (if not present).
   *
   * <p>{@link #resourceCompressionMode} determines whether non-english string resources are
   * packaged separately as assets (and not bundled together into the {@code resources.arsc} file).
   *
   * @param whitelistedStringDirs overrides storing non-english strings as assets for resources
   *     inside these directories.
   */
  @VisibleForTesting
  FilterResourcesStep createFilterResourcesStep(
      ImmutableSet<Path> whitelistedStringDirs,
      ImmutableSet<String> locales,
      ImmutableBiMap<Path, Path> resSourceToDestDirMap) {
    FilterResourcesStep.Builder filterResourcesStepBuilder =
        FilterResourcesStep.builder()
            .setProjectFilesystem(getProjectFilesystem())
            .setInResToOutResDirMap(resSourceToDestDirMap)
            .setResourceFilter(resourceFilter);

    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      filterResourcesStepBuilder.enableStringWhitelisting();
      filterResourcesStepBuilder.setWhitelistedStringDirs(whitelistedStringDirs);
    }

    filterResourcesStepBuilder.setLocales(locales);

    return filterResourcesStepBuilder.build();
  }

  @VisibleForTesting
  ImmutableBiMap<Path, Path> createInResDirToOutResDirMap(
      ImmutableList<Path> resourceDirectories, ImmutableList.Builder<Path> filteredResDirectories) {
    ImmutableBiMap.Builder<Path, Path> filteredResourcesDirMapBuilder = ImmutableBiMap.builder();
    String resDestinationBasePath = getResDestinationBasePath();
    int count = 0;
    for (Path resDir : resourceDirectories) {
      Path filteredResourceDir = Paths.get(resDestinationBasePath, String.valueOf(count++));
      filteredResourcesDirMapBuilder.put(resDir, filteredResourceDir);
      filteredResDirectories.add(filteredResourceDir);
    }
    return filteredResourcesDirMapBuilder.build();
  }

  private String getResDestinationBasePath() {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "__filtered__%s__")
        .toString();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    ImmutableList<Path> resDirectories =
        onDiskBuildInfo
            .getValues(RES_DIRECTORIES_KEY)
            .get()
            .stream()
            .map(Paths::get)
            .collect(MoreCollectors.toImmutableList());
    ImmutableList<Path> stringFiles =
        onDiskBuildInfo
            .getValues(STRING_FILES_KEY)
            .get()
            .stream()
            .map(Paths::get)
            .collect(MoreCollectors.toImmutableList());

    return new BuildOutput(resDirectories, stringFiles);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  public static class BuildOutput {
    private final ImmutableList<Path> resDirectories;
    private final ImmutableList<Path> stringFiles;

    public BuildOutput(ImmutableList<Path> resDirectories, ImmutableList<Path> stringFiles) {
      this.resDirectories = resDirectories;
      this.stringFiles = stringFiles;
    }
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
