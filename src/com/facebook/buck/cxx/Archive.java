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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/**
 * A {@link com.facebook.buck.rules.BuildRule} which builds an "ar" archive from input files
 * represented as {@link com.facebook.buck.rules.SourcePath}.
 */
public class Archive extends AbstractBuildRule implements SupportsInputBasedRuleKey {

  @AddToRuleKey private final Archiver archiver;
  @AddToRuleKey private ImmutableList<String> archiverFlags;
  @AddToRuleKey private final Tool ranlib;
  @AddToRuleKey private ImmutableList<String> ranlibFlags;
  @AddToRuleKey private final Contents contents;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final ImmutableList<SourcePath> inputs;

  private Archive(
      BuildRuleParams params,
      Archiver archiver,
      ImmutableList<String> archiverFlags,
      Tool ranlib,
      ImmutableList<String> ranlibFlags,
      Contents contents,
      Path output,
      ImmutableList<SourcePath> inputs) {
    super(params);
    Preconditions.checkState(
        contents == Contents.NORMAL || archiver.supportsThinArchives(),
        "%s: archive tool for this platform does not support thin archives",
        getBuildTarget());
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors()),
        "Static archive rule %s should not have any Linker Map Mode flavors",
        this);
    this.archiver = archiver;
    this.archiverFlags = archiverFlags;
    this.ranlib = ranlib;
    this.ranlibFlags = ranlibFlags;
    this.contents = contents;
    this.output = output;
    this.inputs = inputs;
  }

  public static Archive from(
      BuildTarget target,
      BuildRuleParams baseParams,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform platform,
      Contents contents,
      Path output,
      ImmutableList<SourcePath> inputs) {
    return from(
        target,
        baseParams,
        ruleFinder,
        platform.getAr(),
        platform.getArflags(),
        platform.getRanlib(),
        platform.getRanlibflags(),
        contents,
        output,
        inputs);
  }

  /**
   * Construct an {@link com.facebook.buck.cxx.Archive} from a {@link
   * com.facebook.buck.rules.BuildRuleParams} object representing a target node. In particular, make
   * sure to trim dependencies to *only* those that provide the input {@link
   * com.facebook.buck.rules.SourcePath}.
   */
  public static Archive from(
      BuildTarget target,
      BuildRuleParams baseParams,
      SourcePathRuleFinder ruleFinder,
      Archiver archiver,
      ImmutableList<String> arFlags,
      Tool ranlib,
      ImmutableList<String> ranlibFlags,
      Contents contents,
      Path output,
      ImmutableList<SourcePath> inputs) {

    // Convert the input build params into ones specialized for this archive build rule.
    // In particular, we only depend on BuildRules directly from the input file SourcePaths.
    BuildRuleParams archiveParams =
        baseParams
            .withBuildTarget(target)
            .copyReplacingDeclaredAndExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.of()),
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(ruleFinder.filterBuildRuleInputs(inputs))
                        .addAll(archiver.getDeps(ruleFinder))
                        .build()));

    return new Archive(
        archiveParams, archiver, arFlags, ranlib, ranlibFlags, contents, output, inputs);
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
        MkdirStep.of(getProjectFilesystem(), output.getParent()),
        RmStep.of(getProjectFilesystem(), output),
        new ArchiveStep(
            getProjectFilesystem(),
            archiver.getEnvironment(resolver),
            archiver.getCommandPrefix(resolver),
            archiverFlags,
            archiver.getArchiveOptions(contents == Contents.THIN),
            output,
            inputs
                .stream()
                .map(resolver::getRelativePath)
                .collect(MoreCollectors.toImmutableList()),
            archiver));

    if (archiver.isRanLibStepRequired()) {
      builder.add(
          new RanlibStep(
              getProjectFilesystem(),
              ranlib.getEnvironment(resolver),
              ranlib.getCommandPrefix(resolver),
              ranlibFlags,
              output));
    }

    if (!archiver.getScrubbers().isEmpty()) {
      builder.add(new FileScrubberStep(getProjectFilesystem(), output, archiver.getScrubbers()));
    }

    return builder.build();
  }

  /**
   * @return the {@link Arg} to use when using this archive. When thin archives are used, this will
   *     ensure that the inputs are also propagated as build time deps to whatever rule uses this
   *     archive.
   */
  public Arg toArg() {
    SourcePath archive = getSourcePathToOutput();
    return contents == Contents.NORMAL
        ? SourcePathArg.of(archive)
        : ThinArchiveArg.of(archive, inputs);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }

  public Contents getContents() {
    return contents;
  }

  /** How this archive packages its contents. */
  public enum Contents {

    /** This archive packages a copy of its inputs and can be used independently of its inputs. */
    NORMAL,

    /**
     * This archive only packages the relative paths to its inputs and so can only be used when its
     * inputs are available.
     */
    THIN,
  }
}
