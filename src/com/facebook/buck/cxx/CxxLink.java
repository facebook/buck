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
import com.facebook.buck.cxx.toolchain.linker.HasLinkerMap;
import com.facebook.buck.cxx.toolchain.linker.HasThinLTO;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
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
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public class CxxLink extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements SupportsInputBasedRuleKey, HasAppleDebugSymbolDeps, OverrideScheduleRule {

  @AddToRuleKey private final Linker linker;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final ImmutableList<Arg> args;
  @AddToRuleKey private final Optional<LinkOutputPostprocessor> postprocessor;
  private final Optional<RuleScheduleInfo> ruleScheduleInfo;
  private final boolean cacheable;
  @AddToRuleKey private boolean thinLto;

  public CxxLink(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Linker linker,
      Path output,
      ImmutableList<Arg> args,
      Optional<LinkOutputPostprocessor> postprocessor,
      Optional<RuleScheduleInfo> ruleScheduleInfo,
      boolean cacheable,
      boolean thinLto) {
    super(buildTarget, projectFilesystem, params);
    this.linker = linker;
    this.output = output;
    this.args = args;
    this.postprocessor = postprocessor;
    this.ruleScheduleInfo = ruleScheduleInfo;
    this.cacheable = cacheable;
    this.thinLto = thinLto;
    performChecks(buildTarget);
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
    Optional<Path> linkerMapPath = getLinkerMapPath();
    if (linkerMapPath.isPresent()
        && LinkerMapMode.isLinkerMapEnabledForBuildTarget(getBuildTarget())) {
      buildableContext.recordArtifact(linkerMapPath.get());
    }
    if (linker instanceof HasThinLTO && thinLto) {
      buildableContext.recordArtifact(((HasThinLTO) linker).thinLTOPath(output));
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

    // Try to find all the cell roots used during the link.  This isn't technically correct since,
    // in theory not all inputs need to come from build rules, but it probably works in practice.
    // One way that we know would work is exposing every known cell root paths, since the only rules
    // that we built (and therefore need to scrub) will be in one of those roots.
    Path currentRuleCellRoot = getProjectFilesystem().getRootPath();
    ImmutableMap<Path, Path> cellRootMap =
        getBuildDeps()
            .stream()
            .map(dep -> dep.getProjectFilesystem().getRootPath())
            .distinct()
            .collect(MoreCollectors.toImmutableMap(x -> x, currentRuleCellRoot::relativize));

    return new ImmutableList.Builder<Step>()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), scratchDir)))
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
        .add(new FileScrubberStep(getProjectFilesystem(), output, linker.getScrubbers(cellRootMap)))
        .build();
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
}
