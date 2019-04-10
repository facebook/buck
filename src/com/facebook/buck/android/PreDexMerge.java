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

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Buildable that is responsible for:
 *
 * <ul>
 *   <li>Bucketing pre-dexed jars into lists for primary and secondary dex files (if the app is
 *       split-dex).
 *   <li>Merging the pre-dexed jars into primary and secondary dex files.
 *   <li>Writing the split-dex "metadata.txt".
 * </ul>
 *
 * <p>Clients of this Buildable may need to know:
 *
 * <ul>
 *   <li>The locations of the zip files directories containing secondary dex files and metadata.
 * </ul>
 *
 * This uses a separate implementation from addDexingSteps. The differences in the splitting logic
 * are too significant to make it worth merging them.
 */
public class PreDexMerge extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  /** Options to use with {@link DxStep} when merging pre-dexed files. */
  private static final EnumSet<DxStep.Option> DX_MERGE_OPTIONS =
      EnumSet.of(
          DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
          DxStep.Option.RUN_IN_PROCESS,
          DxStep.Option.NO_DESUGAR,
          DxStep.Option.NO_OPTIMIZE);

  @AddToRuleKey private final DexSplitMode dexSplitMode;
  @AddToRuleKey private final String dexTool;

  private final AndroidPlatformTarget androidPlatformTarget;
  private final APKModuleGraph apkModuleGraph;
  private final ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexDeps;
  private final ListeningExecutorService dxExecutorService;
  private final OptionalInt xzCompressionLevel;
  private final Optional<String> dxMaxHeapSize;

  public PreDexMerge(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams params,
      DexSplitMode dexSplitMode,
      APKModuleGraph apkModuleGraph,
      ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexDeps,
      ListeningExecutorService dxExecutorService,
      OptionalInt xzCompressionLevel,
      Optional<String> dxMaxHeapSize,
      String dexTool) {
    super(buildTarget, projectFilesystem, params);
    this.androidPlatformTarget = androidPlatformTarget;
    this.dexSplitMode = dexSplitMode;
    this.apkModuleGraph = apkModuleGraph;
    this.preDexDeps = preDexDeps;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.dxMaxHeapSize = dxMaxHeapSize;
    this.dexTool = dexTool;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getPrimaryDexRoot())));

    if (dexSplitMode.isShouldSplitDex()) {
      addStepsForSplitDex(steps, context, buildableContext);
    } else {
      addStepsForSingleDex(steps, context, buildableContext);
    }
    return steps.build();
  }

  public DexFilesInfo getDexFilesInfo() {
    return new DexFilesInfo(
        getSourcePathToPrimaryDex(),
        getSecondaryDexSourcePaths(),
        Optional.empty(),
        getMapOfModuleToSecondaryDexSourcePaths());
  }

  /** Wrapper class for all the paths we need when merging for a split-dex APK. */
  private final class SplitDexPaths {
    private final Path metadataDir;
    private final Path jarfilesDir;
    private final Path scratchDir;
    private final Path successDir;
    private final Path metadataSubdir;
    private final Path jarfilesSubdir;
    private final Path additionalJarfilesDir;
    private final Path additionalJarfilesSubdir;
    private final Path metadataFile;

    private SplitDexPaths() {
      Path workDir = getSecondaryDexRoot();

      metadataDir = workDir.resolve("metadata");
      jarfilesDir = workDir.resolve("jarfiles");
      scratchDir = workDir.resolve("scratch");
      successDir = workDir.resolve("success");
      // These directories must use SECONDARY_DEX_SUBDIR because that mirrors the paths that
      // they will appear at in the APK.
      metadataSubdir = metadataDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);
      jarfilesSubdir = jarfilesDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);
      additionalJarfilesDir = workDir.resolve("additional_dexes");
      additionalJarfilesSubdir = additionalJarfilesDir.resolve("assets");
      metadataFile = metadataSubdir.resolve("metadata.txt");
    }
  }

  private Path getPrimaryDexRoot() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s_output/primary");
  }

  private Path getPrimaryDexPath() {
    return getPrimaryDexRoot().resolve("classes.dex");
  }

  public SourcePath getSourcePathToPrimaryDex() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPrimaryDexPath());
  }

  private Path getSecondaryDexRoot() {
    return BuildTargetPaths.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "%s_output/secondary");
  }

  ImmutableSortedSet<SourcePath> getSecondaryDexSourcePaths() {
    if (!dexSplitMode.isShouldSplitDex()) {
      return ImmutableSortedSet.of();
    }
    SplitDexPaths paths = new SplitDexPaths();

    ImmutableSortedSet.Builder<SourcePath> secondaryDexDirectories =
        ImmutableSortedSet.naturalOrder();
    if (dexSplitMode.getDexStore() == DexStore.RAW) {
      // Raw classes*.dex files go in the top-level of the APK.
      secondaryDexDirectories.add(
          ExplicitBuildTargetSourcePath.of(getBuildTarget(), paths.jarfilesSubdir));
    } else {
      // Otherwise, we want to include the metadata and jars as assets.
      secondaryDexDirectories.add(
          ExplicitBuildTargetSourcePath.of(getBuildTarget(), paths.metadataDir));
      secondaryDexDirectories.add(
          ExplicitBuildTargetSourcePath.of(getBuildTarget(), paths.jarfilesDir));
    }
    // always add additional dex stores and metadata as assets
    secondaryDexDirectories.add(
        ExplicitBuildTargetSourcePath.of(getBuildTarget(), paths.additionalJarfilesDir));
    return secondaryDexDirectories.build();
  }

  ImmutableMap<String, SourcePath> getMapOfModuleToSecondaryDexSourcePaths() {
    ImmutableMap.Builder<String, SourcePath> mapOfModuleToSecondaryDexSourcePaths =
        ImmutableMap.builder();
    SplitDexPaths paths = new SplitDexPaths();

    for (APKModule apkModule : apkModuleGraph.getAPKModules()) {
      if (apkModule.isRootModule()) {
        continue;
      }
      mapOfModuleToSecondaryDexSourcePaths.put(
          apkModule.getName(),
          ExplicitBuildTargetSourcePath.of(
              getBuildTarget(), paths.additionalJarfilesSubdir.resolve(apkModule.getName())));
    }

    return mapOfModuleToSecondaryDexSourcePaths.build();
  }

  private void addStepsForSplitDex(
      ImmutableList.Builder<Step> steps, BuildContext context, BuildableContext buildableContext) {

    // Collect all of the DexWithClasses objects to use for merging.
    ImmutableMultimap.Builder<APKModule, DexWithClasses> dexFilesToMergeBuilder =
        ImmutableMultimap.builder();
    dexFilesToMergeBuilder.putAll(
        FluentIterable.from(preDexDeps.entries())
            .transform(
                input ->
                    new AbstractMap.SimpleEntry<>(
                        input.getKey(), DexWithClasses.TO_DEX_WITH_CLASSES.apply(input.getValue())))
            .filter(input -> input.getValue() != null)
            .toSet());

    SplitDexPaths paths = new SplitDexPaths();

    // Do not clear existing directory which might contain secondary dex files that are not
    // re-merged (since their contents did not change).
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), paths.jarfilesSubdir)));
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                paths.additionalJarfilesSubdir)));
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), paths.successDir)));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), paths.metadataSubdir)));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), paths.scratchDir)));

    buildableContext.recordArtifact(getPrimaryDexRoot());
    buildableContext.recordArtifact(paths.jarfilesSubdir);
    buildableContext.recordArtifact(paths.metadataSubdir);
    buildableContext.recordArtifact(paths.successDir);
    buildableContext.recordArtifact(paths.additionalJarfilesSubdir);

    final ImmutableSet<String> primaryDexPatterns;
    if (dexSplitMode.isAllowRDotJavaInSecondaryDex()) {
      primaryDexPatterns = dexSplitMode.getPrimaryDexPatterns();
    } else {
      primaryDexPatterns =
          ImmutableSet.<String>builder()
              .addAll(dexSplitMode.getPrimaryDexPatterns())
              .add(
                  "/R^",
                  "/R$",
                  // Pin this to the primary for test apps with no primary dex classes.
                  // The exact match makes it fairly efficient.
                  "^com/facebook/buck_generated/AppWithoutResourcesStub^")
              .build();
    }
    PreDexedFilesSorter preDexedFilesSorter =
        new PreDexedFilesSorter(
            dexFilesToMergeBuilder.build(),
            primaryDexPatterns,
            apkModuleGraph,
            paths.scratchDir,
            // We kind of overload the "getLinearAllocHardLimit" parameter
            // to set the dex weight limit during pre-dex merging.
            dexSplitMode.getLinearAllocHardLimit(),
            dexSplitMode.getDexStore(),
            paths.jarfilesSubdir,
            paths.additionalJarfilesSubdir);
    ImmutableMap<String, PreDexedFilesSorter.Result> sortResults =
        preDexedFilesSorter.sortIntoPrimaryAndSecondaryDexes(getProjectFilesystem(), steps);

    PreDexedFilesSorter.Result rootApkModuleResult =
        sortResults.get(APKModuleGraph.ROOT_APKMODULE_NAME);
    if (rootApkModuleResult == null) {
      throw new HumanReadableException("No classes found in primary or secondary dexes");
    }

    SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
    Multimap<Path, SourcePath> aggregatedOutputToInputs = HashMultimap.create();
    ImmutableMap.Builder<Path, Sha1HashCode> dexInputHashesBuilder = ImmutableMap.builder();
    for (PreDexedFilesSorter.Result result : sortResults.values()) {
      if (!result.apkModule.equals(apkModuleGraph.getRootAPKModule())) {
        Path dexOutputPath = paths.additionalJarfilesSubdir.resolve(result.apkModule.getName());
        steps.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), dexOutputPath)));
      }
      aggregatedOutputToInputs.putAll(result.secondaryOutputToInputs);
      addResolvedPathsToBuilder(sourcePathResolver, dexInputHashesBuilder, result.dexInputHashes);
    }
    ImmutableMap<Path, Sha1HashCode> dexInputHashes = dexInputHashesBuilder.build();

    Path primaryDexPath = getPrimaryDexPath();
    steps.add(
        new SmartDexingStep(
            androidPlatformTarget,
            context,
            getProjectFilesystem(),
            primaryDexPath,
            Suppliers.ofInstance(
                rootApkModuleResult.primaryDexInputs.stream()
                    .map(path -> sourcePathResolver.getRelativePath(getProjectFilesystem(), path))
                    .collect(ImmutableSet.toImmutableSet())),
            Optional.of(paths.jarfilesSubdir),
            Optional.of(
                Suppliers.ofInstance(
                    Multimaps.transformValues(
                        aggregatedOutputToInputs,
                        path -> sourcePathResolver.getRelativePath(getProjectFilesystem(), path)))),
            () -> dexInputHashes,
            paths.successDir,
            DX_MERGE_OPTIONS,
            dxExecutorService,
            xzCompressionLevel,
            dxMaxHeapSize,
            dexTool,
            false,
            false,
            Optional.empty()));

    for (PreDexedFilesSorter.Result result : sortResults.values()) {
      if (!result.apkModule.equals(apkModuleGraph.getRootAPKModule())) {
        Path dexMetadataOutputPath =
            paths
                .additionalJarfilesSubdir
                .resolve(result.apkModule.getName())
                .resolve("metadata.txt");

        addMetadataWriteStep(result, steps, dexMetadataOutputPath);
      }
    }

    addMetadataWriteStep(rootApkModuleResult, steps, paths.metadataFile);
  }

  private void addResolvedPathsToBuilder(
      SourcePathResolver sourcePathResolver,
      ImmutableMap.Builder<Path, Sha1HashCode> builder,
      ImmutableMap<SourcePath, Sha1HashCode> dexInputHashes) {
    for (Map.Entry<SourcePath, Sha1HashCode> entry : dexInputHashes.entrySet()) {
      builder.put(
          sourcePathResolver.getRelativePath(getProjectFilesystem(), entry.getKey()),
          entry.getValue());
    }
  }

  private void addMetadataWriteStep(
      PreDexedFilesSorter.Result result, ImmutableList.Builder<Step> steps, Path metadataFilePath) {
    StringBuilder nameBuilder = new StringBuilder(30);
    boolean isRootModule = result.apkModule.equals(apkModuleGraph.getRootAPKModule());
    String storeId = result.apkModule.getName();
    nameBuilder.append("write_");
    if (!isRootModule) {
      nameBuilder.append(storeId);
      nameBuilder.append("_");
    }
    nameBuilder.append("metadata_txt");
    steps.add(
        new AbstractExecutionStep(nameBuilder.toString()) {
          @Override
          public StepExecutionResult execute(ExecutionContext executionContext) throws IOException {
            Map<Path, DexWithClasses> metadataTxtEntries = result.metadataTxtDexEntries;
            List<String> lines = Lists.newArrayListWithCapacity(metadataTxtEntries.size());

            lines.add(".id " + storeId);
            if (isRootModule) {
              if (dexSplitMode.getDexStore() == DexStore.RAW) {
                lines.add(".root_relative");
              }
            } else {
              for (APKModule dependency :
                  apkModuleGraph.getGraph().getOutgoingNodesFor(result.apkModule)) {
                lines.add(".requires " + dependency.getName());
              }
            }

            for (Map.Entry<Path, DexWithClasses> entry : metadataTxtEntries.entrySet()) {
              Path pathToSecondaryDex = entry.getKey();
              String containedClass = Iterables.get(entry.getValue().getClassNames(), 0);
              containedClass = containedClass.replace('/', '.');
              Sha1HashCode hash = getProjectFilesystem().computeSha1(pathToSecondaryDex);
              lines.add(
                  String.format(
                      "%s %s %s", pathToSecondaryDex.getFileName(), hash, containedClass));
            }
            getProjectFilesystem().writeLinesToPath(lines, metadataFilePath);
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  private void addStepsForSingleDex(
      ImmutableList.Builder<Step> steps, BuildContext context, BuildableContext buildableContext) {
    // For single-dex apps with pre-dexing, we just add the steps directly.

    Stream<SourcePath> sourcePathsToDex =
        preDexDeps.values().stream()
            .filter(DexProducedFromJavaLibrary::hasOutput)
            .map(DexProducedFromJavaLibrary::getSourcePathToDex);

    Path primaryDexPath = getPrimaryDexPath();
    buildableContext.recordArtifact(primaryDexPath);

    Iterable<Path> filesToDex =
        context
            .getSourcePathResolver()
            .getAllAbsolutePaths(sourcePathsToDex.collect(Collectors.toList()));

    // This will combine the pre-dexed files and the R.class files into a single classes.dex file.
    steps.add(
        new DxStep(
            getProjectFilesystem(),
            androidPlatformTarget,
            primaryDexPath,
            filesToDex,
            DX_MERGE_OPTIONS,
            dexTool));
  }

  public Path getMetadataTxtPath() {
    checkSplitDexEnabled();
    return new SplitDexPaths().metadataFile;
  }

  public Path getDexDirectory() {
    checkSplitDexEnabled();
    return new SplitDexPaths().jarfilesSubdir;
  }

  private void checkSplitDexEnabled() {
    if (!dexSplitMode.isShouldSplitDex()) {
      throw new HumanReadableException(
          "A multi-dex build was requested, but `use_split_dex=True` was not specified");
    }
  }

  /** @return the output directories for modular dex files */
  Stream<Path> getModuleDexPaths() {
    SplitDexPaths paths = new SplitDexPaths();
    return apkModuleGraph.getAPKModules().stream()
        .filter(module -> !module.isRootModule())
        .map(module -> paths.additionalJarfilesSubdir.resolve(module.getName()));
  }

  public SourcePath getMetadataTxtSourcePath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getMetadataTxtPath());
  }

  public SourcePath getDexDirectorySourcePath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getDexDirectory());
  }

  /**
   * @return a Stream containing pairs of: 1. the metadata describing the output dex files of a
   *     module 2. the directory containing the corresponding dex files
   */
  Stream<Pair<SourcePath, SourcePath>> getModuleMetadataAndDexSourcePaths() {
    return getModuleDexPaths()
        .map(
            directory ->
                new Pair<>(
                    ExplicitBuildTargetSourcePath.of(
                        getBuildTarget(), directory.resolve("metadata.txt")),
                    ExplicitBuildTargetSourcePath.of(getBuildTarget(), directory)));
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
