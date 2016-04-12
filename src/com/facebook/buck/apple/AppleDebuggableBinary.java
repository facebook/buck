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
package com.facebook.buck.apple;

import com.facebook.buck.cxx.BuildRuleWithBinary;
import com.facebook.buck.cxx.ProvidesStaticLibraryDeps;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * This build rule wraps the usual build rule and should be treated as top-level binary rule for
 * apple platform. Depending on the debug format, it should depend on dsym and stripped cxx binary,
 * or just on stripped binary.
 */
public class AppleDebuggableBinary
    extends AbstractBuildRule
    implements BuildRuleWithBinary, SupportsInputBasedRuleKey, HasRuntimeDeps {

  public static final Flavor RULE_FLAVOR = ImmutableFlavor.of("apple-debuggable-binary");

  private final BuildRule strippedBinaryRule;

  @AddToRuleKey
  private final SourcePath binarySourcePath;

  public AppleDebuggableBinary(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      BuildRule strippedBinaryRule) {
    super(buildRuleParams, resolver);
    this.strippedBinaryRule = strippedBinaryRule;
    this.binarySourcePath = new BuildTargetSourcePath(strippedBinaryRule.getBuildTarget());
    performChecks(buildRuleParams, strippedBinaryRule);
  }

  private void performChecks(BuildRuleParams buildRuleParams, BuildRule cxxStrip) {
    Preconditions.checkArgument(
        buildRuleParams.getBuildTarget().getFlavors().contains(RULE_FLAVOR),
        "Rule %s should contain flavor %s", this, RULE_FLAVOR);
    Preconditions.checkArgument(
        AppleDebugFormat.FLAVOR_DOMAIN.containsAnyOf(buildRuleParams.getBuildTarget().getFlavors()),
        "Rule %s should contain some of AppleDebugFormat flavors", this);
    Preconditions.checkArgument(
        getDeps().contains(cxxStrip),
        "Rule %s should depend on its stripped rule %s", this, cxxStrip);
  }

  /**
   * Indicates whether its possible to wrap given _binary_ rule.
   */
  public static boolean canWrapBinaryBuildRule(BuildRule binaryBuildRule) {
    return binaryBuildRule instanceof ProvidesStaticLibraryDeps;
  }

  public static boolean isBuildRuleDebuggable(BuildRule buildRule) {
    // stub binary files cannot have dSYMs
    if (buildRule instanceof WriteFile) {
      return false;
    }

    // fat/thin binaries and dynamic libraries may have dSYMs
    if (buildRule instanceof ProvidesStaticLibraryDeps ||
        buildRule instanceof AppleDebuggableBinary) {
      return true;
    }

    return false;
  }

  public static ImmutableSortedSet<BuildRule> getRequiredDeps(
      AppleDebugFormat debugFormat,
      BuildRule strippedBinaryRule,
      ProvidesStaticLibraryDeps unstrippedBinaryRule,
      Optional<AppleDsym> appleDsym) {
    if (debugFormat == AppleDebugFormat.NONE) {
      return ImmutableSortedSet.of(strippedBinaryRule);
    }
    Preconditions.checkArgument(
        appleDsym.isPresent(),
        "debugFormat %s expects AppleDsym rule to be present", debugFormat);
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(strippedBinaryRule)
        .addAll(unstrippedBinaryRule.getStaticLibraryDeps())
        .add(appleDsym.get())
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return getResolver().getRelativePath(binarySourcePath);
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return strippedBinaryRule;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.of(strippedBinaryRule);
  }
}
