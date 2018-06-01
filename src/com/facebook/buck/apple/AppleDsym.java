/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.apple;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasPostBuildSteps;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.stream.Stream;

/** Creates dSYM bundle for the given _unstripped_ binary. */
public class AppleDsym extends AbstractBuildRule
    implements HasPostBuildSteps, SupportsInputBasedRuleKey {

  public static final Flavor RULE_FLAVOR = InternalFlavor.of("apple-dsym");
  public static final String DSYM_DWARF_FILE_FOLDER = "Contents/Resources/DWARF/";

  @AddToRuleKey private final Tool lldb;

  @AddToRuleKey private final Tool dsymutil;

  @AddToRuleKey private final SourcePath unstrippedBinarySourcePath;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> additionalSymbolDeps;

  @AddToRuleKey(stringify = true)
  private final Path dsymOutputPath;

  private ImmutableSortedSet<BuildRule> buildDeps;

  public AppleDsym(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder sourcePathRuleFinder,
      Tool dsymutil,
      Tool lldb,
      SourcePath unstrippedBinarySourcePath,
      ImmutableSortedSet<SourcePath> additionalSymbolDeps,
      Path dsymOutputPath) {
    super(buildTarget, projectFilesystem);
    this.dsymutil = dsymutil;
    this.lldb = lldb;
    this.unstrippedBinarySourcePath = unstrippedBinarySourcePath;
    this.additionalSymbolDeps = additionalSymbolDeps;
    this.dsymOutputPath = dsymOutputPath;
    this.buildDeps =
        Stream.concat(
                Stream.of(this.unstrippedBinarySourcePath), this.additionalSymbolDeps.stream())
            .flatMap(sourcePathRuleFinder.FILTER_BUILD_RULE_INPUTS)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
    checkFlavorCorrectness(buildTarget);
  }

  public static Path getDsymOutputPath(BuildTarget target, ProjectFilesystem filesystem) {
    AppleDsym.checkFlavorCorrectness(target);
    return BuildTargets.getGenPath(
        filesystem, target, "%s." + AppleBundleExtension.DSYM.toFileExtension());
  }

  public static String getDwarfFilenameForDsymTarget(BuildTarget dsymTarget) {
    AppleDsym.checkFlavorCorrectness(dsymTarget);
    return dsymTarget.getShortName();
  }

  private static void checkFlavorCorrectness(BuildTarget buildTarget) {
    Preconditions.checkArgument(
        buildTarget.getFlavors().contains(RULE_FLAVOR),
        "Rule %s must be identified by %s flavor",
        buildTarget,
        RULE_FLAVOR);
    Preconditions.checkArgument(
        !AppleDebugFormat.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "Rule %s must not contain any debug format flavors (%s), only rule flavor %s",
        buildTarget,
        AppleDebugFormat.FLAVOR_DOMAIN.getFlavors(),
        RULE_FLAVOR);
    Preconditions.checkArgument(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR),
        "Rule %s must not contain strip flavor %s: %s works only with unstripped binaries!",
        buildTarget,
        CxxStrip.RULE_FLAVOR,
        AppleDsym.class.toString());
    Preconditions.checkArgument(
        !StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "Rule %s must not contain strip style flavors: %s works only with unstripped binaries!",
        buildTarget,
        AppleDsym.class.toString());
    Preconditions.checkArgument(
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "Rule %s must not contain linker map mode flavors.",
        buildTarget);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(dsymOutputPath);

    Path unstrippedBinaryPath =
        context.getSourcePathResolver().getAbsolutePath(unstrippedBinarySourcePath);
    Path dwarfFileFolder = dsymOutputPath.resolve(DSYM_DWARF_FILE_FOLDER);
    return ImmutableList.of(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), dsymOutputPath))
            .withRecursive(true),
        new DsymStep(
            getBuildTarget(),
            getProjectFilesystem(),
            dsymutil.getEnvironment(context.getSourcePathResolver()),
            dsymutil.getCommandPrefix(context.getSourcePathResolver()),
            unstrippedBinaryPath,
            dsymOutputPath),
        new MoveStep(
            getProjectFilesystem(),
            dwarfFileFolder.resolve(unstrippedBinaryPath.getFileName()),
            dwarfFileFolder.resolve(getDwarfFilenameForDsymTarget(getBuildTarget()))));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), dsymOutputPath);
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(BuildContext context) {
    return ImmutableList.of(
        new RegisterDebugSymbolsStep(
            unstrippedBinarySourcePath, lldb, context.getSourcePathResolver(), dsymOutputPath));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }
}
