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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
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
import com.facebook.buck.step.fs.LogContentsOfFileStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;

public class CxxLink extends AbstractBuildRule
    implements SupportsInputBasedRuleKey, ProvidesLinkedBinaryDeps, OverrideScheduleRule {

  @AddToRuleKey private final Linker linker;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final ImmutableList<Arg> args;
  @AddToRuleKey private final Optional<LinkOutputPostprocessor> postprocessor;
  private final Optional<RuleScheduleInfo> ruleScheduleInfo;
  private final boolean cacheable;
  @AddToRuleKey private boolean thinLto;

  public CxxLink(
      BuildRuleParams params,
      Linker linker,
      Path output,
      ImmutableList<Arg> args,
      Optional<LinkOutputPostprocessor> postprocessor,
      Optional<RuleScheduleInfo> ruleScheduleInfo,
      boolean cacheable,
      boolean thinLto) {
    super(params);
    this.linker = linker;
    this.output = output;
    this.args = args;
    this.postprocessor = postprocessor;
    this.ruleScheduleInfo = ruleScheduleInfo;
    this.cacheable = cacheable;
    this.thinLto = thinLto;
    performChecks(params);
  }

  private void performChecks(BuildRuleParams params) {
    Preconditions.checkArgument(
        !params.getBuildTarget().getFlavors().contains(CxxStrip.RULE_FLAVOR)
            || !StripStyle.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
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
    ImmutableSet.Builder<Path> cellRoots = ImmutableSet.builder();
    for (BuildRule dep : getBuildDeps()) {
      cellRoots.add(dep.getProjectFilesystem().getRootPath());
    }

    return new ImmutableList.Builder<Step>()
        .add(MkdirStep.of(getProjectFilesystem(), output.getParent()))
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), scratchDir))
        .add(RmStep.of(getProjectFilesystem(), argFilePath))
        .add(RmStep.of(getProjectFilesystem(), fileListPath))
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
        .add(
            new FileScrubberStep(
                getProjectFilesystem(), output, linker.getScrubbers(cellRoots.build())))
        .add(new LogContentsOfFileStep(getProjectFilesystem().resolve(argFilePath), Level.FINEST))
        .add(RmStep.of(getProjectFilesystem(), argFilePath))
        .add(new LogContentsOfFileStep(getProjectFilesystem().resolve(fileListPath), Level.FINEST))
        .add(RmStep.of(getProjectFilesystem(), fileListPath))
        .add(RmStep.of(getProjectFilesystem(), scratchDir).withRecursive(true))
        .build();
  }

  @Override
  public ImmutableSet<BuildRule> getStaticLibraryDeps() {
    return FluentIterable.from(getBuildDeps()).filter(Archive.class::isInstance).toSet();
  }

  @Override
  public ImmutableSet<BuildRule> getCompileDeps() {
    return FluentIterable.from(getBuildDeps())
        .filter(CxxPreprocessAndCompile.class::isInstance)
        .toSet();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
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
