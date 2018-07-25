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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.stream.IntStream;
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

  private final ImmutableSortedSet<BuildRule> resourceRules;
  private final ImmutableCollection<BuildRule> rulesWithResourceDirectories;
  private final SourcePathRuleFinder ruleFinder;
  // Rule key correctness is ensured by depping on all android_resource rules in
  // Builder.setAndroidResourceDepsFinder(). See getBuildDeps().
  private final ImmutableList<SourcePath> resDirectories;
  private final ImmutableSet<SourcePath> whitelistedStringDirs;
  @AddToRuleKey private final ImmutableSet<String> locales;
  @AddToRuleKey private final Optional<String> localizedStringFileName;
  @AddToRuleKey private final ResourceCompressionMode resourceCompressionMode;
  @AddToRuleKey private final FilterResourcesSteps.ResourceFilter resourceFilter;
  @AddToRuleKey private final Optional<Arg> postFilterResourcesCmd;

  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  public ResourcesFilter(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> resourceRules,
      ImmutableCollection<BuildRule> rulesWithResourceDirectories,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<SourcePath> resDirectories,
      ImmutableSet<SourcePath> whitelistedStringDirs,
      ImmutableSet<String> locales,
      Optional<String> localizedStringFileName,
      ResourceCompressionMode resourceCompressionMode,
      FilterResourcesSteps.ResourceFilter resourceFilter,
      Optional<Arg> postFilterResourcesCmd) {
    super(buildTarget, projectFilesystem);
    this.resourceRules = resourceRules;
    this.rulesWithResourceDirectories = rulesWithResourceDirectories;
    this.ruleFinder = ruleFinder;
    this.resDirectories = resDirectories;
    this.whitelistedStringDirs = whitelistedStringDirs;
    this.locales = locales;
    this.localizedStringFileName = localizedStringFileName;
    this.resourceCompressionMode = resourceCompressionMode;
    this.resourceFilter = resourceFilter;
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
    this.postFilterResourcesCmd = postFilterResourcesCmd;
  }

  @Override
  public ImmutableList<SourcePath> getResDirectories() {
    return RichStream.from(getRawResDirectories())
        .map(p -> (SourcePath) ExplicitBuildTargetSourcePath.of(getBuildTarget(), p))
        .toImmutableList();
  }

  private ImmutableList<Path> getRawResDirectories() {
    Path resDestinationBasePath =
        BuildTargetPaths.getScratchPath(
            getProjectFilesystem(), getBuildTarget(), "__filtered__%s__");

    return IntStream.range(0, resDirectories.size())
        .mapToObj(count -> resDestinationBasePath.resolve(String.valueOf(count)))
        .collect(ImmutableList.toImmutableList());
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
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(resourceRules)
        .addAll(rulesWithResourceDirectories)
        .addAll(
            RichStream.from(postFilterResourcesCmd)
                .flatMap(a -> BuildableSupport.getDeps(a, ruleFinder))
                .toOnceIterable())
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    ImmutableList.Builder<Path> filteredResDirectoriesBuilder = ImmutableList.builder();
    ImmutableSet<Path> whitelistedStringPaths =
        whitelistedStringDirs
            .stream()
            .map(
                sourcePath ->
                    getProjectFilesystem()
                        .relativize(context.getSourcePathResolver().getAbsolutePath(sourcePath)))
            .collect(ImmutableSet.toImmutableSet());
    ImmutableList<Path> resPaths =
        resDirectories
            .stream()
            .map(
                sourcePath ->
                    getProjectFilesystem()
                        .relativize(context.getSourcePathResolver().getAbsolutePath(sourcePath)))
            .collect(ImmutableList.toImmutableList());
    ImmutableBiMap<Path, Path> inResDirToOutResDirMap =
        createInResDirToOutResDirMap(resPaths, filteredResDirectoriesBuilder);
    FilterResourcesSteps filterResourcesSteps =
        createFilterResourcesSteps(
            whitelistedStringPaths, locales, localizedStringFileName, inResDirToOutResDirMap);
    steps.add(filterResourcesSteps.getCopyStep());
    maybeAddPostFilterCmdStep(context, buildableContext, steps, inResDirToOutResDirMap);
    steps.add(filterResourcesSteps.getScaleStep());

    ImmutableList.Builder<Path> stringFilesBuilder = ImmutableList.builder();
    // The list of strings.xml files is only needed to build string assets
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      GetStringsFilesStep getStringsFilesStep =
          new GetStringsFilesStep(getProjectFilesystem(), resPaths, stringFilesBuilder);
      steps.add(getStringsFilesStep);
    }

    ImmutableList<Path> filteredResDirectories = filteredResDirectoriesBuilder.build();
    for (Path outputResourceDir : filteredResDirectories) {
      buildableContext.recordArtifact(outputResourceDir);
    }

    steps.add(
        new AbstractExecutionStep("record_build_output") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            if (postFilterResourcesCmd.isPresent()) {
              buildableContext.recordArtifact(getRDotJsonPath());
            }
            Path stringFiles = getStringFilesPath();
            getProjectFilesystem().mkdirs(stringFiles.getParent());
            getProjectFilesystem()
                .writeLinesToPath(
                    stringFilesBuilder.build().stream().map(Object::toString)::iterator,
                    stringFiles);
            buildableContext.recordArtifact(stringFiles);
            return StepExecutionResults.SUCCESS;
          }
        });

    return steps.build();
  }

  private Path getStringFilesPath() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/string_files");
  }

  private void maybeAddPostFilterCmdStep(
      BuildContext context,
      BuildableContext buildableContext,
      ImmutableList.Builder<Step> steps,
      ImmutableBiMap<Path, Path> inResDirToOutResDirMap) {
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
            addPostFilterCommandSteps(cmd, context.getSourcePathResolver(), steps);
          } catch (IOException e) {
            throw new RuntimeException("Could not generate/save filter resources data json", e);
          } finally {
            IOUtils.closeQuietly(filterResourcesDataOutputStream);
          }
        });
  }

  @VisibleForTesting
  void addPostFilterCommandSteps(
      Arg command, SourcePathResolver sourcePathResolver, ImmutableList.Builder<Step> steps) {
    ImmutableList.Builder<String> commandLineBuilder = new ImmutableList.Builder<>();
    command.appendToCommandLine(commandLineBuilder::add, sourcePathResolver);
    commandLineBuilder.add(Escaper.escapeAsBashString(getFilterResourcesDataPath()));
    commandLineBuilder.add(Escaper.escapeAsBashString(getRDotJsonPath()));
    String commandLine = Joiner.on(' ').join(commandLineBuilder.build());
    steps.add(new BashStep(getProjectFilesystem().getRootPath(), commandLine));
  }

  private Path getFilterResourcesDataPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/post_filter_resources_data.json");
  }

  @Override
  public Optional<SourcePath> getOverrideSymbolsPath() {
    if (postFilterResourcesCmd.isPresent()) {
      return Optional.of(ExplicitBuildTargetSourcePath.of(getBuildTarget(), getRDotJsonPath()));
    }
    return Optional.empty();
  }

  private Path getRDotJsonPath() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/R.json");
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
   * <p>{@link FilterResourcesSteps.ResourceFilter} {@code resourceFilter} determines which
   * drawables end up in the APK (based on density - mdpi, hdpi etc), and also whether higher
   * density drawables get scaled down to the specified density (if not present).
   *
   * <p>{@link #resourceCompressionMode} determines whether non-english string resources are
   * packaged separately as assets (and not bundled together into the {@code resources.arsc} file).
   *
   * @param whitelistedStringDirs overrides storing non-english strings as assets for resources
   *     inside these directories.
   */
  @VisibleForTesting
  FilterResourcesSteps createFilterResourcesSteps(
      ImmutableSet<Path> whitelistedStringDirs,
      ImmutableSet<String> locales,
      Optional<String> localizedStringFileName,
      ImmutableBiMap<Path, Path> resSourceToDestDirMap) {
    FilterResourcesSteps.Builder filterResourcesStepBuilder =
        FilterResourcesSteps.builder()
            .setProjectFilesystem(getProjectFilesystem())
            .setInResToOutResDirMap(resSourceToDestDirMap)
            .setResourceFilter(resourceFilter);

    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      filterResourcesStepBuilder.enableStringWhitelisting();
      filterResourcesStepBuilder.setWhitelistedStringDirs(whitelistedStringDirs);
    }

    filterResourcesStepBuilder.setLocales(locales);
    filterResourcesStepBuilder.setLocalizedStringFileName(localizedStringFileName);

    return filterResourcesStepBuilder.build();
  }

  @VisibleForTesting
  ImmutableBiMap<Path, Path> createInResDirToOutResDirMap(
      ImmutableList<Path> resourceDirectories, ImmutableList.Builder<Path> filteredResDirectories) {
    ImmutableBiMap.Builder<Path, Path> filteredResourcesDirMapBuilder = ImmutableBiMap.builder();

    List<Path> outputDirs = getRawResDirectories();
    Preconditions.checkState(
        outputDirs.size() == resourceDirectories.size(),
        "Directory list sizes don't match.  This is a bug.");
    Iterator<Path> outIter = outputDirs.iterator();
    for (Path resDir : resourceDirectories) {
      Path filteredResourceDir = outIter.next();
      filteredResourcesDirMapBuilder.put(resDir, filteredResourceDir);
      filteredResDirectories.add(filteredResourceDir);
    }
    return filteredResourcesDirMapBuilder.build();
  }

  @Override
  public BuildOutput initializeFromDisk(SourcePathResolver pathResolver) throws IOException {
    ImmutableList<Path> stringFiles =
        getProjectFilesystem()
            .readLines(getStringFilesPath())
            .stream()
            .map(Paths::get)
            .collect(ImmutableList.toImmutableList());
    return new BuildOutput(stringFiles);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  public static class BuildOutput {
    private final ImmutableList<Path> stringFiles;

    public BuildOutput(ImmutableList<Path> stringFiles) {
      this.stringFiles = stringFiles;
    }
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
