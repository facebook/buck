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
import com.facebook.buck.cxx.ProvidesLinkedBinaryDeps;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ForwardingBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * This build rule wraps the usual build rule and should be treated as top-level binary rule for
 * apple platform. Depending on the debug format, it should depend on dsym and stripped cxx binary,
 * or just on stripped binary.
 */
public class AppleDebuggableBinary extends AbstractBuildRule
    implements BuildRuleWithBinary, SupportsInputBasedRuleKey, HasRuntimeDeps {

  public static final Flavor RULE_FLAVOR = InternalFlavor.of("apple-debuggable-binary");

  /**
   * Binary rule could be stripped and unstripped, depending on requirements. See AppleDebugFormat.
   */
  private final BuildRule binaryRule;

  @AddToRuleKey private final SourcePath binarySourcePath;

  public AppleDebuggableBinary(BuildRuleParams buildRuleParams, BuildRule binaryRule) {
    super(buildRuleParams);
    this.binaryRule = binaryRule;
    this.binarySourcePath = Preconditions.checkNotNull(binaryRule.getSourcePathToOutput());
    performChecks(buildRuleParams, binaryRule);
  }

  private void performChecks(BuildRuleParams buildRuleParams, BuildRule cxxStrip) {
    Preconditions.checkArgument(
        buildRuleParams.getBuildTarget().getFlavors().contains(RULE_FLAVOR),
        "Rule %s should contain flavor %s",
        this,
        RULE_FLAVOR);
    Preconditions.checkArgument(
        AppleDebugFormat.FLAVOR_DOMAIN.containsAnyOf(buildRuleParams.getBuildTarget().getFlavors()),
        "Rule %s should contain some of AppleDebugFormat flavors",
        this);
    Preconditions.checkArgument(
        getBuildDeps().contains(cxxStrip),
        "Rule %s should depend on its stripped rule %s",
        this,
        cxxStrip);
  }

  /** Indicates whether its possible to wrap given _binary_ rule. */
  public static boolean canWrapBinaryBuildRule(BuildRule binaryBuildRule) {
    return binaryBuildRule instanceof ProvidesLinkedBinaryDeps;
  }

  public static boolean isBuildRuleDebuggable(BuildRule buildRule) {
    // stub binary files cannot have dSYMs
    if (buildRule instanceof WriteFile) {
      return false;
    }

    // fat/thin binaries and dynamic libraries may have dSYMs
    if (buildRule instanceof ProvidesLinkedBinaryDeps
        || buildRule instanceof AppleDebuggableBinary) {
      return true;
    }

    return false;
  }

  // Defines all rules that AppleDebuggableBinary should depend on, depending on debug format.
  // For no-debug:  only stripped binary.
  // For dwarf:     on unstripped binary and all static libs, because these files must be accessible
  //                during debug session.
  // For dsym:      on stripped binary and dsym rule, because dSYM must be accessible during debug
  //                session.
  public static ImmutableSortedSet<BuildRule> getRequiredRuntimeDeps(
      AppleDebugFormat debugFormat,
      BuildRule strippedBinaryRule,
      ProvidesLinkedBinaryDeps unstrippedBinaryRule,
      Optional<AppleDsym> appleDsym) {
    if (debugFormat == AppleDebugFormat.NONE) {
      return ImmutableSortedSet.of(strippedBinaryRule);
    }
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    if (debugFormat == AppleDebugFormat.DWARF) {
      builder.add(unstrippedBinaryRule);
      builder.addAll(unstrippedBinaryRule.getCompileDeps());
      builder.addAll(unstrippedBinaryRule.getStaticLibraryDeps());
    } else if (debugFormat == AppleDebugFormat.DWARF_AND_DSYM) {
      Preconditions.checkArgument(
          appleDsym.isPresent(),
          "debugFormat %s expects AppleDsym rule to be present",
          AppleDebugFormat.DWARF_AND_DSYM);
      builder.add(strippedBinaryRule);
      builder.add(appleDsym.get());
    }
    return builder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ForwardingBuildTargetSourcePath(getBuildTarget(), binarySourcePath);
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return binaryRule;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return getDeclaredDeps().stream().map(BuildRule::getBuildTarget);
  }
}
