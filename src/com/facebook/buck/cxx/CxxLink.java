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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.schedule.OverrideScheduleRule;
import com.facebook.buck.core.rules.schedule.RuleScheduleInfo;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.HasImportLibrary;
import com.facebook.buck.cxx.toolchain.linker.HasLinkerMap;
import com.facebook.buck.cxx.toolchain.linker.HasThinLTO;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.HasSupplementaryOutputs;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
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
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** A BuildRule for linking c++ objects. */
public class CxxLink extends ModernBuildRule<CxxLink.Impl>
    implements SupportsInputBasedRuleKey,
        HasAppleDebugSymbolDeps,
        OverrideScheduleRule,
        HasSupplementaryOutputs {

  private final Optional<RuleScheduleInfo> ruleScheduleInfo;
  private final boolean cacheable;
  // Stored here so we can access it without an OutputPathResolver.
  private final Path output;
  private final ImmutableMap<String, Path> extraOutputs;

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
      boolean cacheable,
      boolean thinLto) {
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
            thinLto,
            buildTarget,
            computeCellRoots(cellResolver, buildTarget.getCell())));
    this.output = output;
    this.ruleScheduleInfo = ruleScheduleInfo;
    this.cacheable = cacheable;
    this.extraOutputs = extraOutputs;
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

  /** Buildable implementation of CxxLink. */
  public static class Impl implements Buildable {
    @AddToRuleKey private final Linker linker;
    @AddToRuleKey private final ImmutableList<Arg> args;
    @AddToRuleKey private final Optional<LinkOutputPostprocessor> postprocessor;
    @AddToRuleKey private final boolean thinLto;
    @AddToRuleKey private final ImmutableSortedSet<String> relativeCellRoots;
    @AddToRuleKey private final PublicOutputPath output;
    @AddToRuleKey private final Optional<PublicOutputPath> linkerMapPath;
    @AddToRuleKey private final Optional<PublicOutputPath> thinLTOPath;
    @AddToRuleKey private final ImmutableList<PublicOutputPath> extraOutputs;

    public Impl(
        Linker linker,
        Path output,
        ImmutableMap<String, Path> extraOutputs,
        ImmutableList<Arg> args,
        Optional<LinkOutputPostprocessor> postprocessor,
        boolean thinLto,
        BuildTarget buildTarget,
        ImmutableSortedSet<Path> relativeCellRoots) {
      this.linker = linker;
      this.output = new PublicOutputPath(output);
      this.extraOutputs =
          extraOutputs
              .values()
              .stream()
              .map(PublicOutputPath::new)
              .collect(ImmutableList.toImmutableList());
      Optional<Path> linkerMapPath = getLinkerMapPath(linker, output);
      if (linkerMapPath.isPresent()
          && LinkerMapMode.isLinkerMapEnabledForBuildTarget(buildTarget)) {
        this.linkerMapPath = Optional.of(new PublicOutputPath(linkerMapPath.get()));
      } else {
        this.linkerMapPath = Optional.empty();
      }
      if (linker instanceof HasThinLTO && thinLto) {
        this.thinLTOPath =
            Optional.of(new PublicOutputPath(((HasThinLTO) linker).thinLTOPath(output)));
      } else {
        this.thinLTOPath = Optional.empty();
      }

      this.args = args;
      this.postprocessor = postprocessor;
      this.thinLto = thinLto;
      this.relativeCellRoots =
          relativeCellRoots
              .stream()
              .map(Object::toString)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Path scratchDir = filesystem.resolve(outputPathResolver.getTempPath());
      Path argFilePath = scratchDir.resolve("linker.argsfile");
      Path fileListPath = scratchDir.resolve("filelist.txt");

      Path outputPath = outputPathResolver.resolvePath(output);

      boolean requiresPostprocessing = postprocessor.isPresent();
      Path linkOutput = requiresPostprocessing ? scratchDir.resolve("link-output") : outputPath;

      ImmutableMap<Path, Path> cellRootMap =
          this.relativeCellRoots
              .stream()
              .collect(
                  ImmutableSortedMap.toImmutableSortedMap(
                      Ordering.natural(),
                      root -> MorePaths.normalize(filesystem.getRootPath().resolve(root)),
                      root -> Paths.get(root)));

      Builder<Step> stepsBuilder =
          new Builder<Step>()
              .add(MkdirStep.of(buildCellPathFactory.from(outputPath.getParent())))
              .addAll(
                  CxxPrepareForLinkStep.create(
                      argFilePath,
                      fileListPath,
                      linker.fileList(fileListPath),
                      linkOutput,
                      args,
                      linker,
                      filesystem.getRootPath(),
                      context.getSourcePathResolver()))
              .add(
                  new CxxLinkStep(
                      filesystem.getRootPath(),
                      linker.getEnvironment(context.getSourcePathResolver()),
                      linker.getCommandPrefix(context.getSourcePathResolver()),
                      argFilePath,
                      scratchDir))
              .addAll(
                  postprocessor
                      .map(
                          p ->
                              p.getSteps(
                                  context,
                                  filesystem.resolve(linkOutput),
                                  filesystem.resolve(outputPath)))
                      .orElse(ImmutableList.of()))
              .add(new FileScrubberStep(filesystem, outputPath, linker.getScrubbers(cellRootMap)));
      if (linkerMapPath.isPresent()) {
        // In some case (when there are no `dll_export`s eg) an import library is not produced by
        // link.exe. An empty file is produced in this case (since an import library was already
        // promised to `buildableContext`).
        stepsBuilder.add(
            new TouchStep(filesystem, outputPathResolver.resolvePath(linkerMapPath.get())));
      }
      return stepsBuilder.build();
    }
  }

  @Override
  public Stream<BuildRule> getAppleDebugSymbolDeps() {
    return getBuildDeps()
        .stream()
        .filter(x -> x instanceof Archive || x instanceof CxxPreprocessAndCompile);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
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
