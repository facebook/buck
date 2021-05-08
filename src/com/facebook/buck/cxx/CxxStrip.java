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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Controls how strip tool is invoked. To have better understanding please refer to `man strip`. If
 * you don't want stripping, you should depend on CxxLink directly.
 */
public class CxxStrip extends ModernBuildRule<CxxStrip.Impl> implements SupportsInputBasedRuleKey {

  /**
   * Used to identify this rule in the graph. This should be appended ONLY to build target that is
   * passed to the CxxStrip constructor when you create instance of this class. Appending it in
   * other places is does nothing except adds a unnecessary flavor that will skew output paths of
   * other build rules.
   */
  public static final Flavor RULE_FLAVOR = InternalFlavor.of("stripped");

  private final boolean isCacheable;

  public CxxStrip(
      CxxPlatform cxxPlatform,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePath unstrippedBinary,
      SourcePathRuleFinder ruleFinder,
      StripStyle stripStyle,
      Tool strip,
      boolean isCacheable,
      OutputPath output,
      boolean withDownwardApi) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            output,
            unstrippedBinary,
            strip,
            getStripArgs(cxxPlatform, stripStyle),
            withDownwardApi));
    this.isCacheable = isCacheable;

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

  private static ImmutableList<Arg> getStripArgs(CxxPlatform cxxPlatform, StripStyle stripStyle) {
    switch (stripStyle) {
      case DEBUGGING_SYMBOLS:
        return cxxPlatform.getStripDebugFlags();
      case NON_GLOBAL_SYMBOLS:
        return cxxPlatform.getStripNonGlobalFlags();
      case ALL_SYMBOLS:
        return cxxPlatform.getStripAllFlags();
      default:
        throw new HumanReadableException("Invalid strip style %s", stripStyle.toString());
    }
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

  public ImmutableList<Arg> getStripFlags() {
    return getBuildable().stripArgs;
  }

  @Override
  public boolean isCacheable() {
    return isCacheable;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  static class Impl implements Buildable {

    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final SourcePath unstrippedBinary;
    @AddToRuleKey private final Tool strip;
    @AddToRuleKey private final ImmutableList<Arg> stripArgs;
    @AddToRuleKey private final boolean withDownwardApi;

    public Impl(
        OutputPath output,
        SourcePath unstrippedBinary,
        Tool strip,
        ImmutableList<Arg> stripArgs,
        boolean withDownwardApi) {
      this.output = output;
      this.unstrippedBinary = unstrippedBinary;
      this.stripArgs = stripArgs;
      this.strip = strip;
      this.withDownwardApi = withDownwardApi;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Path output = outputPathResolver.resolvePath(this.output).getPath();
      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      // Modern build rules will automatically create the output resolver root dir, so handle the
      // weird case when a passed in `PublicOutputPath` wants to write a file to the same location.
      if (output.equals(outputPathResolver.getRootPath().getPath())) {
        steps.add(RmStep.of(BuildCellRelativePath.of(output), true));
      }
      SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();
      steps.add(
          new StripSymbolsStep(
              buildContext.getSourcePathResolver().getAbsolutePath(unstrippedBinary).getPath(),
              output,
              strip.getCommandPrefix(resolver),
              strip.getEnvironment(resolver),
              Arg.stringify(stripArgs, resolver),
              filesystem,
              withDownwardApi));
      return steps.build();
    }
  }
}
