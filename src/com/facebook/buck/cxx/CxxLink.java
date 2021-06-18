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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.rules.schedule.OverrideScheduleRule;
import com.facebook.buck.core.rules.schedule.RuleScheduleInfo;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.HasImportLibrary;
import com.facebook.buck.cxx.toolchain.linker.HasLTO;
import com.facebook.buck.cxx.toolchain.linker.HasLinkerMap;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.rules.modern.RemoteExecutionEnabled;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.isolatedsteps.common.TouchStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** A BuildRule for linking c++ objects. */
public class CxxLink extends ModernBuildRule<CxxLink.Impl>
    implements HasAppleDebugSymbolDeps, OverrideScheduleRule, HasSupplementaryOutputs {

  private static final Logger LOG = Logger.get(CxxLink.class);

  private final Optional<RuleScheduleInfo> ruleScheduleInfo;
  private final boolean cacheable;
  private final boolean incremental;

  @CustomFieldBehavior(RemoteExecutionEnabled.class)
  private final boolean remoteExecutionEnabled;
  // Stored here so we can access it without an OutputPathResolver.
  private final Path output;
  private final ImmutableMap<String, Path> extraOutputs;
  private final Optional<String> pathNormalizationPrefix;

  public CxxLink(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Linker linker,
      Path output,
      ImmutableMap<String, Path> extraOutputs,
      ImmutableList<Arg> args,
      Optional<LinkOutputPostprocessor> postprocessor,
      Optional<RuleScheduleInfo> ruleScheduleInfo,
      Optional<Tool> strip,
      boolean linkerMapEnabled,
      boolean cacheable,
      boolean thinLto,
      boolean fatLto,
      boolean withDownwardApi) {
    this(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        cellResolver,
        linker,
        output,
        extraOutputs,
        args,
        postprocessor,
        ruleScheduleInfo,
        linkerMapEnabled,
        cacheable,
        thinLto,
        fatLto,
        withDownwardApi,
        Optional.empty(),
        strip,
        CxxConditionalLinkStrategyAlwaysLink.STRATEGY,
        CxxDebugSymbolLinkStrategyAlwaysDebug.STRATEGY);
  }

  public CxxLink(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Linker linker,
      Path output,
      ImmutableMap<String, Path> extraOutputs,
      ImmutableList<Arg> args,
      Optional<LinkOutputPostprocessor> postprocessor,
      Optional<RuleScheduleInfo> ruleScheduleInfo,
      boolean linkerMapEnabled,
      boolean cacheable,
      boolean thinLto,
      boolean fatLto,
      boolean withDownwardApi,
      Optional<SourcePath> filteredFocusedTargets,
      Optional<Tool> strip,
      CxxConditionalLinkStrategy linkStrategy,
      CxxDebugSymbolLinkStrategy debugSymbolLinkStrategy) {
    this(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        linker,
        output,
        extraOutputs,
        args,
        postprocessor,
        ruleScheduleInfo,
        linkerMapEnabled,
        cacheable,
        thinLto,
        fatLto,
        withDownwardApi,
        filteredFocusedTargets,
        strip,
        linkStrategy,
        debugSymbolLinkStrategy,
        computeCellRoots(cellResolver, buildTarget.getCell()));
  }

  private CxxLink(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Linker linker,
      Path output,
      ImmutableMap<String, Path> extraOutputs,
      ImmutableList<Arg> args,
      Optional<LinkOutputPostprocessor> postprocessor,
      Optional<RuleScheduleInfo> ruleScheduleInfo,
      boolean linkerMapEnabled,
      boolean cacheable,
      boolean thinLto,
      boolean fatLto,
      boolean withDownwardApi,
      Optional<SourcePath> filteredFocusedTargets,
      Optional<Tool> strip,
      CxxConditionalLinkStrategy linkStrategy,
      CxxDebugSymbolLinkStrategy debugSymbolLinkStrategy,
      ImmutableSortedSet<String> cellRoots) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            linker,
            output,
            extraOutputs,
            args,
            postprocessor,
            linkerMapEnabled,
            thinLto,
            fatLto,
            buildTarget,
            cellRoots,
            withDownwardApi,
            filteredFocusedTargets,
            strip,
            linkStrategy,
            debugSymbolLinkStrategy));
    this.output = output;
    this.ruleScheduleInfo = ruleScheduleInfo;
    this.incremental = linkStrategy.isIncremental();
    this.remoteExecutionEnabled = !this.incremental;
    this.cacheable = cacheable;
    this.extraOutputs = extraOutputs;
    this.pathNormalizationPrefix =
        linker.pathNormalizationPrefix(getCellRootMap(cellRoots, projectFilesystem));
    performChecks(buildTarget);
  }

  @Override
  public ImmutableList<OutputPath> getExcludedOutputPathsFromAutomaticSetup() {
    if (!incremental) {
      return ImmutableList.of();
    }

    CxxLink.Impl buildable = getBuildable();
    ImmutableList.Builder<OutputPath> excludedPaths = ImmutableList.builder();
    excludedPaths.add(buildable.output);
    buildable.linkerMapPath.ifPresent(path -> excludedPaths.add(path));
    excludedPaths.addAll(buildable.linkStrategy.getExcludedOutpathPathsFromAutomaticRemoval());

    return excludedPaths.build();
  }

  private static ImmutableSortedSet<String> computeCellRoots(
      CellPathResolver cellResolver, CanonicalCellName cell) {
    ImmutableSortedSet.Builder<String> builder = ImmutableSortedSet.naturalOrder();
    AbsPath cellPath = cellResolver.getNewCellPathResolver().getCellPath(cell);
    builder.add(cellPath.relativize(cellPath).toString());
    cellResolver
        .getKnownRoots()
        .forEach(path -> builder.add(cellPath.relativize(path.getPath()).toString()));
    return builder.build();
  }

  private static ImmutableSortedMap<Path, Path> getCellRootMap(
      ImmutableSortedSet<String> relativeCellRoots, ProjectFilesystem filesystem) {
    return relativeCellRoots.stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Ordering.natural(),
                root -> MorePaths.normalize(filesystem.getRootPath().resolve(root).getPath()),
                root -> Paths.get(root)));
  }

  private void performChecks(BuildTarget buildTarget) {
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxLink should not be created with CxxStrip flavors");
  }

  @Override
  public Optional<String> getPathNormalizationPrefix() {
    return this.pathNormalizationPrefix;
  }

  /** Buildable implementation of CxxLink. */
  public static class Impl implements Buildable {
    @AddToRuleKey private final Linker linker;
    @AddToRuleKey private final ImmutableList<Arg> args;
    @AddToRuleKey private final Optional<LinkOutputPostprocessor> postprocessor;
    @AddToRuleKey private final boolean thinLto;
    @AddToRuleKey private final boolean fatLto;
    @AddToRuleKey private final ImmutableSortedSet<String> relativeCellRoots;
    @AddToRuleKey private final PublicOutputPath output;
    @AddToRuleKey private final Optional<PublicOutputPath> linkerMapPath;
    @AddToRuleKey private final Optional<PublicOutputPath> thinLTOPath;
    @AddToRuleKey private final ImmutableList<PublicOutputPath> extraOutputs;
    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final boolean withDownwardApi;
    @AddToRuleKey private final Optional<SourcePath> filteredFocusedTargets;
    @AddToRuleKey private final CxxConditionalLinkStrategy linkStrategy;
    @AddToRuleKey private final CxxDebugSymbolLinkStrategy debugStrategy;
    @AddToRuleKey private final Optional<Tool> strip;

    public Impl(
        Linker linker,
        Path output,
        ImmutableMap<String, Path> extraOutputs,
        ImmutableList<Arg> args,
        Optional<LinkOutputPostprocessor> postprocessor,
        boolean linkerMapEnabled,
        boolean thinLto,
        boolean fatLto,
        BuildTarget buildTarget,
        ImmutableSortedSet<String> relativeCellRoots,
        boolean withDownwardApi,
        Optional<SourcePath> filteredFocusedTargets,
        Optional<Tool> strip,
        CxxConditionalLinkStrategy linkStrategy,
        CxxDebugSymbolLinkStrategy debugSymbolLinkStrategy) {
      this.linker = linker;
      this.strip = strip;
      this.output = new PublicOutputPath(output);
      this.extraOutputs =
          extraOutputs.values().stream()
              .map(PublicOutputPath::new)
              .collect(ImmutableList.toImmutableList());
      this.withDownwardApi = withDownwardApi;
      Optional<Path> linkerMapPath = getLinkerMapPath(linker, output);
      if (linkerMapEnabled
          && linkerMapPath.isPresent()
          && LinkerMapMode.isLinkerMapEnabledForBuildTarget(buildTarget)) {
        this.linkerMapPath = Optional.of(new PublicOutputPath(linkerMapPath.get()));
      } else {
        this.linkerMapPath = Optional.empty();
      }
      if (linker instanceof HasLTO && (thinLto || fatLto)) {
        this.thinLTOPath = Optional.of(new PublicOutputPath(((HasLTO) linker).ltoPath(output)));
      } else {
        this.thinLTOPath = Optional.empty();
      }

      this.args = args;
      this.postprocessor = postprocessor;
      this.thinLto = thinLto;
      this.fatLto = fatLto;
      this.relativeCellRoots = relativeCellRoots;
      this.buildTarget = buildTarget;
      this.linkStrategy = linkStrategy;
      this.debugStrategy = debugSymbolLinkStrategy;
      this.filteredFocusedTargets = filteredFocusedTargets;
    }

    public boolean isLinkerMapEnabled() {
      return this.linkerMapPath.isPresent();
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      AbsPath scratchDir = filesystem.resolve(outputPathResolver.getTempPath());
      AbsPath argFilePath = scratchDir.resolve("linker.argsfile");
      AbsPath fileListPath = scratchDir.resolve("filelist.txt");

      RelPath outputPath = outputPathResolver.resolvePath(output);

      boolean requiresPostprocessing = postprocessor.isPresent();
      Path linkOutput =
          requiresPostprocessing
              ? scratchDir.resolve("link-output").getPath()
              : outputPath.getPath();
      ImmutableMap<String, String> env = linker.getEnvironment(context.getSourcePathResolver());
      ImmutableList<String> commandPrefix =
          linker.getCommandPrefix(context.getSourcePathResolver());
      Optional<AbsPath> focusedTargetsPath =
          filteredFocusedTargets.map(context.getSourcePathResolver()::getAbsolutePath);

      AbsPath skipLinkingPath = scratchDir.resolve("relink.skip-linking");
      ImmutableList<Step> relinkCheckSteps = ImmutableList.of();
      ImmutableList<Step> relinkWriteSteps = ImmutableList.of();
      if (linkStrategy.isIncremental()) {
        relinkCheckSteps =
            linkStrategy.createConditionalLinkCheckSteps(
                filesystem,
                outputPathResolver,
                context.getSourcePathResolver(),
                argFilePath,
                fileListPath,
                skipLinkingPath,
                outputPath,
                env,
                commandPrefix,
                focusedTargetsPath);
        relinkWriteSteps =
            linkStrategy.createConditionalLinkWriteSteps(
                filesystem,
                outputPathResolver,
                context.getSourcePathResolver(),
                argFilePath,
                fileListPath,
                skipLinkingPath,
                outputPath,
                env,
                commandPrefix,
                focusedTargetsPath);
      }

      Optional<ImmutableSet<AbsPath>> focusedBuildOutputPaths =
          debugStrategy.getFocusedBuildOutputPaths();
      Supplier<Boolean> skipScrubbingCheck =
          () -> {
            // Skip scrubbing if the executable was not modified, as there's no need to re-scrub
            return filesystem.exists(skipLinkingPath.getPath());
          };

      ImmutableSortedMap<Path, Path> cellRootMap = getCellRootMap(relativeCellRoots, filesystem);
      ImmutableList<FileScrubber> fileScrubbers;

      ImmutableList<Step> debugSymbolStrippingSteps = ImmutableList.of();
      if (focusedTargetsPath.isPresent()) {
        ImmutableMultimap<String, AbsPath> targetToOutputPathMap =
            makeTargetToOutputPathsMap(context.getSourcePathResolver(), args);

        fileScrubbers =
            linker.getScrubbers(
                cellRootMap,
                Optional.empty(),
                Optional.of(targetToOutputPathMap),
                focusedTargetsPath);

        if (strip.isPresent()) {
          // Add the debug symbol stripping step to strip debug symbols on link outputs without
          // focused targets.
          debugSymbolStrippingSteps =
              ImmutableList.of(
                  new StripNonFocusedDebugSymbolStep(
                      focusedTargetsPath.get(),
                      outputPath.getPath(),
                      strip.get(),
                      context.getSourcePathResolver(),
                      filesystem,
                      filesystem.getRootPath(),
                      ProjectFilesystemUtils.relativize(
                          filesystem.getRootPath(), context.getBuildCellRootPath()),
                      true));
        } else {
          LOG.warn("`strip` tool isn't available, skipping debug symbol stripping.");
        }
      } else {
        fileScrubbers =
            linker.getScrubbers(
                cellRootMap, focusedBuildOutputPaths, Optional.empty(), Optional.empty());
      }

      Builder<Step> stepsBuilder =
          new Builder<Step>()
              .add(MkdirStep.of(buildCellPathFactory.from(outputPath.getParent())))
              // The signal file must _always_ be deleted because scratch pad dir cleaning can be
              // controlled by the user.
              .add(RmStep.of(buildCellPathFactory.from(skipLinkingPath.getPath())))
              .addAll(
                  CxxPrepareForLinkStep.create(
                      argFilePath.getPath(),
                      fileListPath.getPath(),
                      linker.fileList(fileListPath),
                      linkOutput,
                      args,
                      linker,
                      buildTarget.getCell(),
                      filesystem.getRootPath().getPath(),
                      context.getSourcePathResolver(),
                      cellRootMap))
              .addAll(relinkCheckSteps)
              .add(
                  new CxxLinkStep(
                      filesystem.getRootPath(),
                      ProjectFilesystemUtils.relativize(
                          filesystem.getRootPath(), context.getBuildCellRootPath()),
                      env,
                      commandPrefix,
                      argFilePath.getPath(),
                      scratchDir.getPath(),
                      withDownwardApi,
                      linkStrategy.isIncremental()
                          ? Optional.of(skipLinkingPath)
                          : Optional.empty()))
              .addAll(
                  postprocessor
                      .map(
                          p ->
                              p.getSteps(
                                  context,
                                  filesystem.resolve(linkOutput),
                                  filesystem.resolve(outputPath).getPath()))
                      .orElse(ImmutableList.of()))
              .add(
                  new FileScrubberStep(
                      filesystem, outputPath.getPath(), fileScrubbers, skipScrubbingCheck))
              .addAll(debugSymbolStrippingSteps)
              .addAll(relinkWriteSteps);
      if (linkerMapPath.isPresent()) {
        // In some case (when there are no `dll_export`s eg) an import library is not produced by
        // link.exe. An empty file is produced in this case (since an import library was already
        // promised to `buildableContext`).
        stepsBuilder.add(new TouchStep(outputPathResolver.resolvePath(linkerMapPath.get())));
      }
      return stepsBuilder.build();
    }

    /**
     * Creates a multimap from build target strings to their output paths. This is used to acquire
     * output paths for the focused debug targets. The output paths are used in file scrubbing.
     *
     * <p>A multimap is used here because a target can create multiple outputs. For example: if a
     * target `sample_target` has libA.c and libB.c, it'll have sample_target_path#libA.o and
     * sample_target_path#libB.o under its output paths.
     */
    private ImmutableMultimap<String, AbsPath> makeTargetToOutputPathsMap(
        SourcePathResolverAdapter sourcePathResolver, ImmutableList<Arg> linkerArgs) {
      Multimap<String, AbsPath> targetToOutputPathsMap = ArrayListMultimap.create();

      for (Arg arg : linkerArgs) {
        if (!(arg instanceof HasSourcePath)) {
          continue;
        }
        SourcePath sourcePath = ((HasSourcePath) arg).getPath();

        if (!(sourcePath instanceof BuildTargetSourcePath)) {
          continue;
        }

        BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) sourcePath;
        String targetString =
            buildTargetSourcePath.getTarget().getUnflavoredBuildTarget().toString();

        targetToOutputPathsMap.put(targetString, sourcePathResolver.getAbsolutePath(sourcePath));
      }

      return ImmutableMultimap.copyOf(targetToOutputPathsMap);
    }
  }

  @Override
  public Stream<BuildRule> getAppleDebugSymbolDeps() {
    return getBuildDeps().stream()
        .filter(x -> x instanceof Archive || x instanceof CxxPreprocessAndCompile);
  }

  @Override
  public ImmutableSet<OutputLabel> getOutputLabels() {
    ImmutableSet.Builder<OutputLabel> builder = ImmutableSet.builder();
    builder.add(OutputLabel.defaultLabel());
    builder.addAll(extraOutputs.keySet().stream().map(s -> OutputLabel.of(s)).iterator());
    return builder.build();
  }

  @Override
  public BuildTargetSourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** @return The source path to be used to link against this binary. */
  SourcePath getSourcePathToOutputForLinking() {
    if (isSharedLib() && getBuildable().linker instanceof HasImportLibrary) {
      HasImportLibrary impLibLinker = (HasImportLibrary) getBuildable().linker;
      return ExplicitBuildTargetSourcePath.of(
          getBuildTarget(), impLibLinker.importLibraryPath(output));
    }
    return getSourcePathToOutput();
  }

  private boolean isSharedLib() {
    return getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToSupplementaryOutput(String name) {
    Path extraOutput = extraOutputs.get(name);
    if (extraOutput != null) {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), extraOutput);
    }
    return null;
  }

  @Override
  public RuleScheduleInfo getRuleScheduleInfo() {
    return ruleScheduleInfo.orElse(RuleScheduleInfo.DEFAULT);
  }

  @Override
  public boolean isCacheable() {
    return cacheable;
  }

  public boolean isLinkerMapEnabled() {
    return getBuildable().isLinkerMapEnabled();
  }

  public Optional<Path> getLinkerMapPath() {
    return getLinkerMapPath(getLinker(), output);
  }

  private static Optional<Path> getLinkerMapPath(Linker linker, Path output) {
    if (linker instanceof HasLinkerMap) {
      return Optional.of(((HasLinkerMap) linker).linkerMapPath(output));
    } else {
      return Optional.empty();
    }
  }

  public Linker getLinker() {
    return getBuildable().linker;
  }

  public ImmutableList<Arg> getArgs() {
    return getBuildable().args;
  }
}
