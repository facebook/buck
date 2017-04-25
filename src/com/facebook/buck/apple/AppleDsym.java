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

import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** Creates dSYM bundle for the given _unstripped_ binary. */
public class AppleDsym extends AbstractBuildRule
    implements HasPostBuildSteps, SupportsInputBasedRuleKey {

  public static final Flavor RULE_FLAVOR = InternalFlavor.of("apple-dsym");
  public static final String DSYM_DWARF_FILE_FOLDER = "Contents/Resources/DWARF/";

  @AddToRuleKey private final Tool lldb;

  @AddToRuleKey private final Tool dsymutil;

  @AddToRuleKey private final SourcePath unstrippedBinarySourcePath;

  @AddToRuleKey(stringify = true)
  private final Path dsymOutputPath;

  public AppleDsym(
      BuildRuleParams params,
      Tool dsymutil,
      Tool lldb,
      SourcePath unstrippedBinarySourcePath,
      Path dsymOutputPath) {
    super(params);
    this.dsymutil = dsymutil;
    this.lldb = lldb;
    this.unstrippedBinarySourcePath = unstrippedBinarySourcePath;
    this.dsymOutputPath = dsymOutputPath;
    checkFlavorCorrectness(params.getBuildTarget());
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
        RmStep.of(getProjectFilesystem(), dsymOutputPath).withRecursive(true),
        new DsymStep(
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
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), dsymOutputPath);
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(BuildContext context) {
    return ImmutableList.of(
        new RegisterDebugSymbolsStep(
            unstrippedBinarySourcePath, lldb, context.getSourcePathResolver(), dsymOutputPath));
  }
}
