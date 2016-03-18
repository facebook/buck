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

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Controls how strip tool is invoked. To have better understanding please refer to `man strip`.
 * If you don't want stripping, you should depend on CxxLink directly.
 */
public class CxxStrip extends AbstractBuildRule implements SupportsInputBasedRuleKey {

  /**
   * Used to identify this rule in the graph. This should be appended ONLY to build target
   * that is passed to the CxxStrip constructor when you create instance of this class.
   * Appending it in other places is does nothing except adds a unnecessary flavor that will skew
   * output paths of other build rules.
   */
  public static final Flavor RULE_FLAVOR = ImmutableFlavor.of("stripped");

  /**
   * Defines the level of symbol stripping to be performed on the linked product of the build.
   */
  public enum StripStyle implements FlavorConvertible {
    /**
     * Strips debugging symbols, but saves local and global symbols.
     */
    DEBUGGING_SYMBOLS(ImmutableFlavor.of("strip-debug"), ImmutableList.of("-S")),

    /**
     * Strips non-global symbols, but saves external symbols.
     * This is preferred for dynamic shared libraries.
     */
    NON_GLOBAL_SYMBOLS(ImmutableFlavor.of("strip-non-global"), ImmutableList.of("-x")),

    /**
     * Completely strips the binary, removing the symbol table and relocation information.
     * This is preferred for executable files.
     */
    ALL_SYMBOLS(ImmutableFlavor.of("strip-all"), ImmutableList.<String>of()),
    ;

    private final Flavor flavor;
    private final ImmutableList<String> stripToolArgs;

    StripStyle(Flavor flavor, ImmutableList<String> stripToolArgs) {
      this.flavor = flavor;
      this.stripToolArgs = stripToolArgs;
    }

    public ImmutableList<String> getStripToolArgs() {
      return stripToolArgs;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

    public static final FlavorDomain<StripStyle> FLAVOR_DOMAIN =
        FlavorDomain.from("Strip Style", StripStyle.class);
  }

  @AddToRuleKey
  private final StripStyle stripStyle;
  @AddToRuleKey
  private final SourcePath cxxLinkSourcePath;
  @AddToRuleKey
  private final Tool strip;
  @AddToRuleKey(stringify = true)
  private final Path output;

  public CxxStrip(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      StripStyle stripStyle,
      SourcePath cxxLinkSourcePath,
      Tool strip,
      Path output) {
    super(buildRuleParams, resolver);
    Preconditions.checkArgument(
        buildRuleParams.getBuildTarget().getFlavors().contains(RULE_FLAVOR),
        "CxxStrip rule %s should contain %s flavor", this, RULE_FLAVOR);
    this.stripStyle = stripStyle;
    this.cxxLinkSourcePath = cxxLinkSourcePath;
    this.strip = strip;
    this.output = output;
  }

  public static BuildRuleParams removeStripStyleFlavorInParams(
      BuildRuleParams params,
      Optional<StripStyle> flavoredStripStyle) {
    params = params.withoutFlavor(CxxStrip.RULE_FLAVOR);
    if (flavoredStripStyle.isPresent()) {
      params = params.withoutFlavor(flavoredStripStyle.get().getFlavor());
    }
    return params;
  }

  public static BuildRuleParams restoreStripStyleFlavorInParams(
      BuildRuleParams params,
      Optional<StripStyle> flavoredStripStyle) {
    if (flavoredStripStyle.isPresent()) {
      // we should not append CxxStrip.RULE_FLAVOR here because it must be appended
      // to CxxStrip rule only. Other users of CxxStrip flavors must not append it.
      params = params.withFlavor(flavoredStripStyle.get().getFlavor());
    }
    return params;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        CopyStep.forFile(
            getProjectFilesystem(),
            getResolver().getAbsolutePath(cxxLinkSourcePath),
            output),
        new StripSymbolsStep(
            output,
            strip,
            stripStyle.getStripToolArgs(),
            getProjectFilesystem(),
            getResolver()));
  }

  public StripStyle getStripStyle() {
    return stripStyle;
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return output;
  }
}
