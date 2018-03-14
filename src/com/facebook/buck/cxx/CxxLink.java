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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.HasImportLibrary;
import com.facebook.buck.cxx.toolchain.linker.HasLinkerMap;
import com.facebook.buck.cxx.toolchain.linker.HasThinLTO;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CacheableBuildRule;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasSupplementaryOutputs;
import com.facebook.buck.rules.OverrideScheduleRule;
import com.facebook.buck.rules.RuleScheduleInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class CxxLink extends AbstractBuildRule
    implements SupportsInputBasedRuleKey,
        HasAppleDebugSymbolDeps,
        OverrideScheduleRule,
        HasSupplementaryOutputs,
        CacheableBuildRule {

  private final Supplier<? extends SortedSet<BuildRule>> buildDeps;

  @AddToRuleKey private final Linker linker;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey(stringify = true)
  private final ImmutableSortedMap<String, Path> extraOutputs;

  @AddToRuleKey private final ImmutableList<Arg> args;
  @AddToRuleKey private final Optional<LinkOutputPostprocessor> postprocessor;
  private final Optional<RuleScheduleInfo> ruleScheduleInfo;
  private final boolean cacheable;
  @AddToRuleKey private final ImmutableSortedSet<String> relativeCellRoots;
  @AddToRuleKey private boolean thinLto;

  public CxxLink(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Supplier<? extends SortedSet<BuildRule>> buildDepsSupplier,
      CellPathResolver cellPathResolver,
      Linker linker,
      Path output,
      ImmutableMap<String, Path> extraOutputs,
      ImmutableList<Arg> args,
      Optional<LinkOutputPostprocessor> postprocessor,
      Optional<RuleScheduleInfo> ruleScheduleInfo,
      boolean cacheable,
      boolean thinLto) {
    super(buildTarget, projectFilesystem);
    this.buildDeps = MoreSuppliers.memoize(buildDepsSupplier);
    this.linker = linker;
    this.output = output;
    this.extraOutputs = ImmutableSortedMap.copyOf(extraOutputs);
    this.args = args;
    this.postprocessor = postprocessor;
    this.ruleScheduleInfo = ruleScheduleInfo;
    this.cacheable = cacheable;
    this.thinLto = thinLto;
    this.relativeCellRoots =
        computeCellRoots(cellPathResolver, buildTarget.getCell())
            .stream()
            .map(Object::toString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    performChecks(buildTarget);
  }

  private static ImmutableSortedSet<Path> computeCellRoots(
      CellPathResolver cellResolver, Optional<String> cell) {
    ImmutableSortedSet.Builder<Path> builder = ImmutableSortedSet.naturalOrder();
    Path cellPath = cellResolver.getCellPathOrThrow(cell);
    builder.add(cellPath.relativize(cellPath));
    cellResolver.getCellPaths().forEach((name, path) -> builder.add(cellPath.relativize(path)));
    return builder.build();
  }

  private void performChecks(BuildTarget buildTarget) {
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxLink should not be created with CxxStrip flavors");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    extraOutputs.forEach((key, path) -> buildableContext.recordArtifact(path));
    Optional<Path> linkerMapPath = getLinkerMapPath();
    if (linkerMapPath.isPresent()
        && LinkerMapMode.isLinkerMapEnabledForBuildTarget(getBuildTarget())) {
      buildableContext.recordArtifact(linkerMapPath.get());
    }
    if (linker instanceof HasThinLTO && thinLto) {
      buildableContext.recordArtifact(((HasThinLTO) linker).thinLTOPath(output));
    }
    if (isSharedLib() && linker instanceof HasImportLibrary) {
      HasImportLibrary impLibLinker = (HasImportLibrary) this.linker;
      buildableContext.recordArtifact(impLibLinker.importLibraryPath(output));
    }
    Path scratchDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-tmp");
    Path argFilePath =
        getProjectFilesystem()
            .getRootPath()
            .resolve(
                BuildTargets.getScratchPath(
                    getProjectFilesystem(), getBuildTarget(), "%s.argsfile"));
    Path fileListPath =
        getProjectFilesystem()
            .getRootPath()
            .resolve(
                BuildTargets.getScratchPath(
                    getProjectFilesystem(), getBuildTarget(), "%s__filelist.txt"));

    boolean requiresPostprocessing = postprocessor.isPresent();
    Path linkOutput = requiresPostprocessing ? scratchDir.resolve("link-output") : output;

    ImmutableMap<Path, Path> cellRootMap =
        this.relativeCellRoots
            .stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(),
                    root -> MorePaths.normalize(getProjectFilesystem().getRootPath().resolve(root)),
                    root -> Paths.get(root)));

    Builder<Step> builder = new Builder<>();
    builder
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), scratchDir)))
        .addAll(
            extraOutputs
                .values()
                .stream()
                .map(
                    path ->
                        RmStep.of(
                            BuildCellRelativePath.fromCellRelativePath(
                                context.getBuildCellRootPath(), getProjectFilesystem(), path)))
                .iterator())
        .add(
            RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), argFilePath)))
        .add(
            RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), fileListPath)))
        .addAll(
            CxxPrepareForLinkStep.create(
                argFilePath,
                fileListPath,
                linker.fileList(fileListPath),
                linkOutput,
                args,
                linker,
                getBuildTarget().getCellPath(),
                context.getSourcePathResolver()))
        .add(
            new CxxLinkStep(
                getBuildTarget(),
                getProjectFilesystem().getRootPath(),
                linker.getEnvironment(context.getSourcePathResolver()),
                linker.getCommandPrefix(context.getSourcePathResolver()),
                argFilePath,
                getProjectFilesystem().getRootPath().resolve(scratchDir)))
        .addAll(
            postprocessor
                .map(
                    p ->
                        p.getSteps(
                            context,
                            getProjectFilesystem().resolve(linkOutput),
                            getProjectFilesystem().resolve(output)))
                .orElse(ImmutableList.of()))
        .add(
            new FileScrubberStep(getProjectFilesystem(), output, linker.getScrubbers(cellRootMap)));
    if (isSharedLib() && linker instanceof HasImportLibrary) {
      // In some case (when there are no `dll_export`s eg) an import library is not produced by
      // link.exe. An empty file is produced in this case (since an import library was already
      // promised to `buildableContext`).
      HasImportLibrary hasImportLibraryLinker = (HasImportLibrary) this.linker;
      builder.add(
          new TouchStep(getProjectFilesystem(), hasImportLibraryLinker.importLibraryPath(output)));
    }
    return builder.build();
  }

  @Override
  public Stream<BuildRule> getAppleDebugSymbolDeps() {
    return getBuildDeps()
        .stream()
        .filter(x -> x instanceof Archive || x instanceof CxxPreprocessAndCompile);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  /** @return The source path to be used to link against this binary. */
  SourcePath getSourcePathToOutputForLinking() {
    if (isSharedLib() && linker instanceof HasImportLibrary) {
      HasImportLibrary impLibLinker = (HasImportLibrary) this.linker;
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

  public Optional<Path> getLinkerMapPath() {
    if (linker instanceof HasLinkerMap) {
      return Optional.of(((HasLinkerMap) linker).linkerMapPath(output));
    } else {
      return Optional.empty();
    }
  }

  public Linker getLinker() {
    return linker;
  }

  public ImmutableList<Arg> getArgs() {
    return args;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps.get();
  }
}
