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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.schedule.OverrideScheduleRule;
import com.facebook.buck.core.rules.schedule.RuleScheduleInfo;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.HasImportLibrary;
import com.facebook.buck.cxx.toolchain.linker.HasLinkerMap;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/** A BuildRule for linking c++ objects. */
public class CxxThinLTOIndex extends ModernBuildRule<CxxThinLTOIndex.Impl>
    implements SupportsInputBasedRuleKey, HasAppleDebugSymbolDeps, OverrideScheduleRule {

  private final Optional<RuleScheduleInfo> ruleScheduleInfo;
  private final boolean cacheable;
  // Stored here so we can access it without an OutputPathResolver.
  private final Path output;

  public CxxThinLTOIndex(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Linker linker,
      Path output,
      ImmutableList<Arg> args,
      Optional<RuleScheduleInfo> ruleScheduleInfo,
      boolean cacheable) {
    super(buildTarget, projectFilesystem, ruleFinder, new Impl(linker, output, args, buildTarget));
    this.output = output;
    this.ruleScheduleInfo = ruleScheduleInfo;
    this.cacheable = cacheable;
    performChecks(buildTarget);
  }

  private void performChecks(BuildTarget buildTarget) {
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxLink should not be created with CxxStrip flavors");
  }

  /** Buildable implementation of CxxLink. */
  public static class Impl implements Buildable {
    @AddToRuleKey private final BuildTarget targetName;
    @AddToRuleKey private final Linker linker;
    @AddToRuleKey private final ImmutableList<Arg> args;
    @AddToRuleKey private final PublicOutputPath output;
    @AddToRuleKey private final Optional<PublicOutputPath> linkerMapPath;

    public Impl(Linker linker, Path output, ImmutableList<Arg> args, BuildTarget buildTarget) {
      this.linker = linker;
      this.output = new PublicOutputPath(output);
      Optional<Path> linkerMapPath = getLinkerMapPath(linker, output);
      if (linkerMapPath.isPresent()
          && LinkerMapMode.isLinkerMapEnabledForBuildTarget(buildTarget)) {
        this.linkerMapPath = Optional.of(new PublicOutputPath(linkerMapPath.get()));
      } else {
        this.linkerMapPath = Optional.empty();
      }

      this.args = args;
      this.targetName = buildTarget;
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
      Path linkOutput = outputPath.getParent().resolve("thinlto.objects");

      Builder<Step> stepsBuilder =
          new Builder<Step>()
              .add(MkdirStep.of(buildCellPathFactory.from(outputPath.getParent())))
              .add(MkdirStep.of(buildCellPathFactory.from(outputPath)))
              .addAll(
                  CxxPrepareForLinkStep.create(
                      argFilePath,
                      fileListPath,
                      linker.fileList(fileListPath),
                      linkOutput,
                      args,
                      linker,
                      targetName.getCell(),
                      filesystem.getRootPath().getPath(),
                      context.getSourcePathResolver()))
              .add(
                  new CxxLinkStep(
                      filesystem.getRootPath(),
                      linker.getEnvironment(context.getSourcePathResolver()),
                      linker.getCommandPrefix(context.getSourcePathResolver()),
                      argFilePath,
                      scratchDir));

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
    return getBuildDeps().stream()
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
