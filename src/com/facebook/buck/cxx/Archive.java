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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.Archiver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;

/**
 * A {@link BuildRule} which builds an "ar" archive from input files represented as {@link
 * SourcePath}.
 */
public class Archive extends AbstractBuildRule implements SupportsInputBasedRuleKey {

  @AddToRuleKey private final Archiver archiver;
  @AddToRuleKey private ImmutableList<String> archiverFlags;
  @AddToRuleKey private final Optional<Tool> ranlib;
  @AddToRuleKey private ImmutableList<String> ranlibFlags;
  @AddToRuleKey private final ArchiveContents contents;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final ImmutableList<SourcePath> inputs;

  // Not added to RuleKey because it's a view over things already in the RuleKey.
  private final ImmutableSortedSet<BuildRule> deps;

  private final boolean cacheable;

  private Archive(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<BuildRule> deps,
      Archiver archiver,
      ImmutableList<String> archiverFlags,
      Optional<Tool> ranlib,
      ImmutableList<String> ranlibFlags,
      ArchiveContents contents,
      Path output,
      ImmutableList<SourcePath> inputs,
      boolean cacheable) {
    super(buildTarget, projectFilesystem);
    Preconditions.checkState(
        contents == ArchiveContents.NORMAL || archiver.supportsThinArchives(),
        "%s: archive tool for this platform does not support thin archives",
        getBuildTarget());
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "Static archive rule %s should not have any Linker Map Mode flavors",
        this);
    if (archiver.isRanLibStepRequired()) {
      Preconditions.checkArgument(ranlib.isPresent(), "ranlib is required", this);
    }
    this.deps = deps;
    this.archiver = archiver;
    this.archiverFlags = archiverFlags;
    this.ranlib = ranlib;
    this.ranlibFlags = ranlibFlags;
    this.contents = contents;
    this.output = output;
    this.inputs = inputs;
    this.cacheable = cacheable;
  }

  public static Archive from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform platform,
      ArchiveContents contents,
      Path output,
      ImmutableList<SourcePath> inputs,
      boolean cacheable) {
    return from(
        target,
        projectFilesystem,
        ruleFinder,
        platform.getAr().resolve(resolver),
        platform.getArflags(),
        platform.getRanlib().map(r -> r.resolve(resolver)),
        platform.getRanlibflags(),
        contents,
        output,
        inputs,
        cacheable);
  }

  /**
   * Construct an {@link com.facebook.buck.cxx.Archive} from a {@link BuildRuleParams} object
   * representing a target node. In particular, make sure to trim dependencies to *only* those that
   * provide the input {@link SourcePath}.
   */
  public static Archive from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Archiver archiver,
      ImmutableList<String> arFlags,
      Optional<Tool> ranlib,
      ImmutableList<String> ranlibFlags,
      ArchiveContents contents,
      Path output,
      ImmutableList<SourcePath> inputs,
      boolean cacheable) {

    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

    deps.addAll(ruleFinder.filterBuildRuleInputs(inputs))
        .addAll(BuildableSupport.getDepsCollection(archiver, ruleFinder));

    ranlib.ifPresent(r -> deps.addAll(BuildableSupport.getDepsCollection(r, ruleFinder)));

    return new Archive(
        target,
        projectFilesystem,
        deps.build(),
        archiver,
        arFlags,
        ranlib,
        ranlibFlags,
        contents,
        output,
        inputs,
        cacheable);
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return deps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    // Cache the archive we built.
    buildableContext.recordArtifact(output);

    SourcePathResolver resolver = context.getSourcePathResolver();

    // We only support packaging inputs that use the same filesystem root as the output, as thin
    // archives embed relative paths from output to input inside the archive.  If this becomes a
    // limitation, we could make this rule uncacheable and allow thin archives to embed absolute
    // paths.
    for (SourcePath input : inputs) {
      Preconditions.checkState(
          resolver.getFilesystem(input).getRootPath().equals(getProjectFilesystem().getRootPath()));
    }

    ImmutableList.Builder<Step> builder = ImmutableList.builder();

    builder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())),
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output)));

    if (archiver.isArgfileRequired()) {
      builder.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), getScratchPath())));
    }

    builder.add(
        new ArchiveStep(
            getProjectFilesystem(),
            archiver.getEnvironment(resolver),
            archiver.getCommandPrefix(resolver),
            archiverFlags,
            archiver.getArchiveOptions(contents == ArchiveContents.THIN),
            output,
            inputs.stream().map(resolver::getRelativePath).collect(ImmutableList.toImmutableList()),
            archiver,
            getScratchPath()));

    if (archiver.isRanLibStepRequired()) {
      builder.add(
          new RanlibStep(
              getBuildTarget(),
              getProjectFilesystem(),
              ranlib.get().getEnvironment(resolver),
              ranlib.get().getCommandPrefix(resolver),
              ranlibFlags,
              output));
    }

    if (!archiver.getScrubbers().isEmpty()) {
      builder.add(new FileScrubberStep(getProjectFilesystem(), output, archiver.getScrubbers()));
    }

    return builder.build();
  }

  private Path getScratchPath() {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-tmp");
  }

  /**
   * @return the {@link Arg} to use when using this archive. When thin archives are used, this will
   *     ensure that the inputs are also propagated as build time deps to whatever rule uses this
   *     archive.
   */
  public Arg toArg() {
    SourcePath archive = getSourcePathToOutput();
    return contents == ArchiveContents.NORMAL
        ? SourcePathArg.of(archive)
        : ThinArchiveArg.of(archive, inputs);
  }

  @Override
  public boolean isCacheable() {
    return cacheable;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  public ArchiveContents getContents() {
    return contents;
  }
}
