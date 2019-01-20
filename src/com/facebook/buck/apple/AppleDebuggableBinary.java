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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.stream.Stream;

/**
 * This build rule wraps the usual build rule and should be treated as top-level binary rule for
 * apple platform. Depending on the debug format, it should depend on dsym and stripped cxx binary,
 * or just on stripped binary.
 */
public class AppleDebuggableBinary extends AbstractBuildRule
    implements BuildRuleWithBinary, HasRuntimeDeps, HasDeclaredAndExtraDeps {

  public static final Flavor RULE_FLAVOR = InternalFlavor.of("apple-debuggable-binary");

  /**
   * Binary rule could be stripped and unstripped, depending on requirements. See AppleDebugFormat.
   */
  private final BuildRule binaryRule;

  private final Optional<AppleDsym> appleDsym;
  private final ImmutableSortedSet<BuildRule> runtimeDeps;

  /**
   * Create with runtime dependencies to the binary itself and its static library constituents.
   *
   * <p>This is necessary as macho binaries have pointers to debug information stored in object
   * files, rather than copying the debug info into the binary itself.
   */
  static AppleDebuggableBinary createFromUnstrippedBinary(
      ProjectFilesystem filesystem,
      BuildTarget baseTarget,
      HasAppleDebugSymbolDeps unstrippedBinaryRule) {
    return new AppleDebuggableBinary(
        filesystem,
        baseTarget.withAppendedFlavors(RULE_FLAVOR, AppleDebugFormat.DWARF.getFlavor()),
        unstrippedBinaryRule,
        Optional.empty(),
        unstrippedBinaryRule.getAppleDebugSymbolDeps());
  }

  /**
   * Create with runtime dependencies including the dsym rule. Dsym holds all the debug info
   * collected from object files, so object files are not needed for debugging at runtime.
   */
  static AppleDebuggableBinary createWithDsym(
      ProjectFilesystem filesystem,
      BuildTarget baseTarget,
      BuildRule strippedBinaryRule,
      AppleDsym dsym) {
    return new AppleDebuggableBinary(
        filesystem,
        baseTarget.withAppendedFlavors(RULE_FLAVOR, AppleDebugFormat.DWARF_AND_DSYM.getFlavor()),
        strippedBinaryRule,
        Optional.of(dsym),
        Stream.empty());
  }

  /** Create with no additional runtime deps. */
  static AppleDebuggableBinary createWithoutDebugging(
      ProjectFilesystem filesystem, BuildTarget baseTarget, BuildRule binaryRule) {
    return new AppleDebuggableBinary(
        filesystem,
        baseTarget.withAppendedFlavors(RULE_FLAVOR, AppleDebugFormat.NONE.getFlavor()),
        binaryRule,
        Optional.empty(),
        Stream.empty());
  }

  private AppleDebuggableBinary(
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      BuildRule binaryRule,
      Optional<AppleDsym> dsymRule,
      Stream<BuildRule> otherDeps) {
    super(buildTarget, projectFilesystem);
    this.binaryRule = binaryRule;
    this.appleDsym = dsymRule;
    this.runtimeDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .add(binaryRule)
            .addAll(dsymRule.map(ImmutableList::of).orElse(ImmutableList.of()))
            .addAll(otherDeps.iterator())
            .build();

    Preconditions.checkArgument(
        buildTarget.getFlavors().contains(RULE_FLAVOR),
        "Rule %s should contain flavor %s",
        this,
        RULE_FLAVOR);
    Preconditions.checkArgument(
        AppleDebugFormat.FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors()),
        "Rule %s should contain some of AppleDebugFormat flavors",
        this);
    Preconditions.checkArgument(
        getBuildDeps().contains(binaryRule),
        "Rule %s should depend on its stripped rule %s",
        this,
        binaryRule);
  }

  /** Indicates whether its possible to wrap given _binary_ rule. */
  public static boolean canWrapBinaryBuildRule(BuildRule binaryBuildRule) {
    return binaryBuildRule instanceof HasAppleDebugSymbolDeps;
  }

  public static boolean isBuildRuleDebuggable(BuildRule buildRule) {
    // stub binary files cannot have dSYMs
    if (buildRule instanceof WriteFile) {
      return false;
    }

    // fat/thin binaries and dynamic libraries may have dSYMs
    return buildRule instanceof HasAppleDebugSymbolDeps
        || buildRule instanceof AppleDebuggableBinary;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(
        getBuildTarget(),
        Objects.requireNonNull(
            binaryRule.getSourcePathToOutput(), "binary should always have an output path"));
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return binaryRule;
  }

  public Optional<AppleDsym> getAppleDsym() {
    return appleDsym;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return runtimeDeps.stream().map(BuildRule::getBuildTarget);
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return runtimeDeps;
  }

  @Override
  public SortedSet<BuildRule> getDeclaredDeps() {
    return runtimeDeps;
  }

  @Override
  public SortedSet<BuildRule> deprecatedGetExtraDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getTargetGraphOnlyDeps() {
    return ImmutableSortedSet.of();
  }
}
