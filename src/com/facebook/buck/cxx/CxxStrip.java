/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;

/**
 * Controls how strip tool is invoked. To have better understanding please refer to `man strip`. If
 * you don't want stripping, you should depend on CxxLink directly.
 */
public class CxxStrip extends AbstractBuildRule implements SupportsInputBasedRuleKey {

  /**
   * Used to identify this rule in the graph. This should be appended ONLY to build target that is
   * passed to the CxxStrip constructor when you create instance of this class. Appending it in
   * other places is does nothing except adds a unnecessary flavor that will skew output paths of
   * other build rules.
   */
  public static final Flavor RULE_FLAVOR = InternalFlavor.of("stripped");

  @AddToRuleKey private final SourcePath unstrippedBinary;
  @AddToRuleKey private final StripStyle stripStyle;
  @AddToRuleKey private final Tool strip;

  @AddToRuleKey(stringify = true)
  private final Path output;

  private SourcePathRuleFinder ruleFinder;

  public CxxStrip(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePath unstrippedBinary,
      SourcePathRuleFinder ruleFinder,
      StripStyle stripStyle,
      Tool strip,
      Path output) {
    super(buildTarget, projectFilesystem);
    this.unstrippedBinary = unstrippedBinary;
    this.ruleFinder = ruleFinder;
    this.stripStyle = stripStyle;
    this.strip = strip;
    this.output = output;

    Preconditions.checkArgument(
        buildTarget.getFlavors().contains(RULE_FLAVOR),
        "CxxStrip rule %s should contain %s flavor",
        this,
        RULE_FLAVOR);
    Preconditions.checkArgument(
        StripStyle.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "CxxStrip rule %s should contain one of the strip style flavors (%s)",
        this,
        StripStyle.FLAVOR_DOMAIN.getFlavors());
  }

  public static BuildTarget removeStripStyleFlavorInTarget(
      BuildTarget buildTarget, Optional<StripStyle> flavoredStripStyle) {
    Preconditions.checkState(
        !buildTarget.getFlavors().contains(CxxStrip.RULE_FLAVOR),
        "This function used to strip "
            + RULE_FLAVOR
            + ", which masked errors in constructing "
            + "build targets and caused the returned rule's build target to differ from the "
            + "requested one. This is now explicitly disallowed to catch existing and future "
            + "programming errors of this kind. (Applied to target "
            + buildTarget
            + ")");
    if (flavoredStripStyle.isPresent()) {
      return buildTarget.withoutFlavors(flavoredStripStyle.get().getFlavor());
    }
    return buildTarget;
  }

  public static BuildTarget restoreStripStyleFlavorInTarget(
      BuildTarget buildTarget, Optional<StripStyle> flavoredStripStyle) {
    if (flavoredStripStyle.isPresent()) {
      // we should not append CxxStrip.RULE_FLAVOR here because it must be appended
      // to CxxStrip rule only. Other users of CxxStrip flavors must not append it.
      return buildTarget.withAppendedFlavors(flavoredStripStyle.get().getFlavor());
    }
    return buildTarget;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.copyOf(ruleFinder.filterBuildRuleInputs(unstrippedBinary));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        CopyStep.forFile(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(unstrippedBinary),
            output),
        new StripSymbolsStep(
            getBuildTarget(),
            output,
            strip.getCommandPrefix(context.getSourcePathResolver()),
            strip.getEnvironment(context.getSourcePathResolver()),
            stripStyle.getStripToolArgs(),
            getProjectFilesystem()));
  }

  public StripStyle getStripStyle() {
    return stripStyle;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver sourcePathResolver) {
    this.ruleFinder = ruleFinder;
  }
}
