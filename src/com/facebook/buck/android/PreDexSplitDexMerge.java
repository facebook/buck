/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This is the top level rule responsible for producing multiple dexes for an apk from predexed
 * library rules.
 *
 * <p>This rule depends on 1 or more PreDexSplitDexGroup rules, which produce merged secondary dexes
 * and copies of the predexed rules to be merged into the primary dex. This rule merges the primary
 * dex, copies the merged secondary dexes from the PreDexSplitDexGroup(s), and merges the secondary
 * dex metadata.
 */
public class PreDexSplitDexMerge extends PreDexMerge {

  @AddToRuleKey private final DexSplitMode dexSplitMode;

  private final APKModuleGraph apkModuleGraph;
  private final ImmutableCollection<PreDexSplitDexGroup> preDexDeps;
  private final ListeningExecutorService dxExecutorService;
  private final int xzCompressionLevel;
  private final Optional<String> dxMaxHeapSize;

  public PreDexSplitDexMerge(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      AndroidPlatformTarget androidPlatformTarget,
      String dexTool,
      DexSplitMode dexSplitMode,
      APKModuleGraph apkModuleGraph,
      ImmutableCollection<PreDexSplitDexGroup> preDexDeps,
      ListeningExecutorService dxExecutorService,
      int xzCompressionLevel,
      Optional<String> dxMaxHeapSize) {
    super(buildTarget, projectFilesystem, params, androidPlatformTarget, dexTool);
    this.dexSplitMode = dexSplitMode;
    this.apkModuleGraph = apkModuleGraph;
    this.preDexDeps = preDexDeps;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.dxMaxHeapSize = dxMaxHeapSize;
  }

  private ImmutableMap<Path, Sha1HashCode> resolvePrimaryDexInputHashPaths() {
    ImmutableMap.Builder<Path, Sha1HashCode> dexInputHashesBuilder = ImmutableMap.builder();
    for (PreDexSplitDexGroup rule : preDexDeps) {
      for (Map.Entry<String, PreDexedFilesSorter.DexMetadata> entry :
          rule.getPrimaryDexInputMetadata().getMetadata().entrySet()) {
        dexInputHashesBuilder.put(
            rule.getPrimaryDexRoot().resolve(entry.getKey()),
            Sha1HashCode.of(entry.getValue().getHash()));
      }
    }
    return dexInputHashesBuilder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    SplitDexPaths paths = new SplitDexPaths();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getPrimaryDexRoot())));

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
                context.getBuildCellRootPath(), getProjectFilesystem(), paths.jarfilesSubdir)));
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                paths.additionalJarfilesSubdir)));

    buildableContext.recordArtifact(getPrimaryDexRoot());
    buildableContext.recordArtifact(paths.jarfilesSubdir);
    buildableContext.recordArtifact(paths.metadataSubdir);
    buildableContext.recordArtifact(paths.successDir);
    buildableContext.recordArtifact(paths.additionalJarfilesSubdir);

    Path primaryDexPath = getPrimaryDexPath();
    steps.add(
        new SmartDexingStep(
            androidPlatformTarget,
            context,
            getProjectFilesystem(),
            Optional.of(primaryDexPath),
            Optional.of(this::getPrimaryDexInputs),
            Optional.of(this::getPrimaryDexWeightDetails),
            Optional.empty(),
            Optional.empty(),
            this::resolvePrimaryDexInputHashPaths,
            paths.successDir,
            DX_MERGE_OPTIONS,
            dxExecutorService,
            xzCompressionLevel,
            dxMaxHeapSize,
            dexTool,
            false,
            false,
            Optional.empty(),
            getBuildTarget(),
            Optional.empty() /* minSdkVersion */));

    ImmutableSet.Builder<APKModule> modulesWithDexesBuilder = ImmutableSet.builder();
    for (PreDexSplitDexGroup partialDex : preDexDeps) {
      modulesWithDexesBuilder.add(partialDex.apkModule);
    }
    ImmutableSet<APKModule> modulesWithDexes = modulesWithDexesBuilder.build();

    for (APKModule apkModule : modulesWithDexes) {
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  paths.getSecondaryDexPathForModule(apkModule))));
    }

    for (PreDexSplitDexGroup partialDex : preDexDeps) {
      steps.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              partialDex.getSecondaryDexRoot(),
              paths.getSecondaryDexPathForModule(partialDex.apkModule),
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    steps.add(
        new AbstractExecutionStep("merge_metadata") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            // Read metadata from all groups, combine into one metadata.txt per APK module
            ImmutableMultimap.Builder<APKModule, String> mergedDexEntriesBuilder =
                ImmutableMultimap.builder();
            mergedDexEntriesBuilder.orderValuesBy(Ordering.natural());
            for (PreDexSplitDexGroup partialDex : preDexDeps) {
              for (String line :
                  getProjectFilesystem().readLines(partialDex.getMetadataTxtPath())) {
                mergedDexEntriesBuilder.put(partialDex.apkModule, line);
              }
            }
            ImmutableMultimap<APKModule, String> mergedDexEntries = mergedDexEntriesBuilder.build();

            for (APKModule apkModule : modulesWithDexes) {
              Collection<String> dexEntries = mergedDexEntries.get(apkModule);
              List<String> lines = Lists.newArrayListWithCapacity(dexEntries.size());

              lines.add(".id " + apkModule.getName());
              if (apkModule.isRootModule()) {
                if (dexSplitMode.getDexStore() == DexStore.RAW) {
                  lines.add(".root_relative");
                }
              } else {
                for (APKModule dependency :
                    apkModuleGraph.getGraph().getOutgoingNodesFor(apkModule)) {
                  lines.add(".requires " + dependency.getName());
                }
              }
              lines.addAll(dexEntries);
              getProjectFilesystem()
                  .writeLinesToPath(lines, paths.getMetadataTxtPathForModule(apkModule));
            }
            return StepExecutionResults.SUCCESS;
          }
        });

    return steps.build();
  }

  private List<String> getPrimaryDexWeightDetails() {
    ImmutableMap.Builder<String, PreDexedFilesSorter.DexMetadata> primaryDexInputs =
        ImmutableMap.builder();
    for (PreDexSplitDexGroup partialDex : preDexDeps) {
      primaryDexInputs.putAll(partialDex.getPrimaryDexInputMetadata().getMetadata());
    }
    Comparator<PreDexedFilesSorter.DexMetadata> bySizeDescending =
        (o1, o2) -> Integer.compare(o2.getWeight(), o1.getWeight());
    primaryDexInputs.orderEntriesByValue(bySizeDescending);

    ImmutableMap<String, PreDexedFilesSorter.DexMetadata> sortedDexContents =
        primaryDexInputs.build();

    ImmutableList.Builder<String> dexContentsBuilder = ImmutableList.builder();
    for (Map.Entry<String, PreDexedFilesSorter.DexMetadata> entry : sortedDexContents.entrySet()) {
      dexContentsBuilder.add(String.format("%s\t%s", entry.getValue().getWeight(), entry.getKey()));
    }
    return dexContentsBuilder.build();
  }

  private Set<Path> getPrimaryDexInputs() {
    return RichStream.from(preDexDeps)
        .flatMap(
            rule -> {
              try {
                return getProjectFilesystem().asView()
                    .getFilesUnderPath(
                        rule.getPrimaryDexRoot(), EnumSet.noneOf(FileVisitOption.class))
                    .stream();
              } catch (IOException e) {
                e.printStackTrace();
              }
              return Stream.empty();
            })
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
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
      successDir = workDir.resolve("success");
      // These directories must use SECONDARY_DEX_SUBDIR because that mirrors the paths that
      // they will appear at in the APK.
      metadataSubdir = metadataDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);
      jarfilesSubdir = jarfilesDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);
      additionalJarfilesDir = workDir.resolve("additional_dexes");
      additionalJarfilesSubdir = additionalJarfilesDir.resolve("assets");
      metadataFile = metadataSubdir.resolve("metadata.txt");
    }

    Path getSecondaryDexPathForModule(APKModule module) {
      return module.isRootModule()
          ? jarfilesSubdir
          : additionalJarfilesSubdir.resolve(module.getName());
    }

    Path getMetadataTxtPathForModule(APKModule module) {
      return module.isRootModule()
          ? metadataFile
          : additionalJarfilesSubdir.resolve(module.getName()).resolve("metadata.txt");
    }
  }

  private Path getSecondaryDexRoot() {
    return BuildPaths.getScratchDir(getProjectFilesystem(), getBuildTarget()).resolve("secondary");
  }

  ImmutableSortedSet<SourcePath> getSecondaryDexSourcePaths() {
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
              getBuildTarget(), paths.getSecondaryDexPathForModule(apkModule)));
    }

    return mapOfModuleToSecondaryDexSourcePaths.build();
  }

  public Path getMetadataTxtPath() {
    return new SplitDexPaths().metadataFile;
  }

  public Path getDexDirectory() {
    return new SplitDexPaths().jarfilesSubdir;
  }

  /** @return the output directories for modular dex files */
  Stream<Path> getModuleDexPaths() {
    SplitDexPaths paths = new SplitDexPaths();
    return apkModuleGraph.getAPKModules().stream()
        .filter(module -> !module.isRootModule())
        .map(module -> paths.additionalJarfilesSubdir.resolve(module.getName()));
  }

  public SourcePath getMetadataTxtSourcePath() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        new SplitDexPaths().getMetadataTxtPathForModule(apkModuleGraph.getRootAPKModule()));
  }

  public SourcePath getDexDirectorySourcePath() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        new SplitDexPaths().getSecondaryDexPathForModule(apkModuleGraph.getRootAPKModule()));
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
}
