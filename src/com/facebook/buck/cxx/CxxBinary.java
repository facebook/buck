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
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ForwardingBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.stream.Stream;

public class CxxBinary extends AbstractBuildRule
    implements BinaryBuildRule,
        NativeTestable,
        HasRuntimeDeps,
        ProvidesLinkedBinaryDeps,
        SupportsInputBasedRuleKey {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final CxxPlatform cxxPlatform;
  private final BuildRule linkRule;
  private final Tool executable;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final ImmutableSortedSet<FrameworkPath> frameworks;
  private final BuildTarget platformlessTarget;

  public CxxBinary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      BuildRule linkRule,
      Tool executable,
      Iterable<FrameworkPath> frameworks,
      Iterable<BuildTarget> tests,
      BuildTarget platformlessTarget) {
    super(params);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.ruleFinder = ruleFinder;
    this.cxxPlatform = cxxPlatform;
    this.linkRule = linkRule;
    this.executable = executable;
    this.tests = ImmutableSortedSet.copyOf(tests);
    this.frameworks = ImmutableSortedSet.copyOf(frameworks);
    this.platformlessTarget = platformlessTarget;
    performChecks();
  }

  private void performChecks() {
    Preconditions.checkArgument(
        linkRule instanceof CxxLink || linkRule instanceof CxxStrip,
        "CxxBinary (%s) link rule (%s) is expected to be instance of either CxxLink or CxxStrip",
        this,
        linkRule);
    Preconditions.checkArgument(
        getBuildDeps().contains(linkRule),
        "CxxBinary (%s) must depend on its link rule (%s) via deps",
        this,
        linkRule);
    Preconditions.checkArgument(
        !params.getBuildTarget().getFlavors().contains(CxxStrip.RULE_FLAVOR),
        "CxxBinary (%s) build target should not contain CxxStrip rule flavor %s. Otherwise "
            + "it may be not possible to distinguish CxxBinary (%s) and link rule (%s) in graph.",
        this,
        CxxStrip.RULE_FLAVOR,
        this,
        linkRule);
    Preconditions.checkArgument(
        this.platformlessTarget
            .getUnflavoredBuildTarget()
            .equals(this.params.getBuildTarget().getUnflavoredBuildTarget()));
  }

  @Override
  public Tool getExecutableCommand() {
    return executable;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ForwardingBuildTargetSourcePath(
        getBuildTarget(), Preconditions.checkNotNull(linkRule.getSourcePathToOutput()));
  }

  public BuildRule getLinkRule() {
    return linkRule;
  }

  @Override
  public boolean isTestedBy(BuildTarget testRule) {
    return tests.contains(testRule);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
      throws NoSuchBuildTargetException {
    return CxxPreprocessables.getCxxPreprocessorInput(
        params.withBuildTarget(platformlessTarget),
        ruleResolver,
        /* hasHeaderSymlinkTree */ true,
        cxxPlatform,
        headerVisibility,
        CxxPreprocessables.IncludeType.LOCAL,
        ImmutableMultimap.of(),
        frameworks);
  }

  @Override
  public ImmutableSet<BuildRule> getStaticLibraryDeps() {
    if (linkRule instanceof ProvidesLinkedBinaryDeps) {
      return ((ProvidesLinkedBinaryDeps) linkRule).getStaticLibraryDeps();
    } else {
      return ImmutableSet.of();
    }
  }

  @Override
  public ImmutableSet<BuildRule> getCompileDeps() {
    if (linkRule instanceof ProvidesLinkedBinaryDeps) {
      return ((ProvidesLinkedBinaryDeps) linkRule).getCompileDeps();
    } else {
      return ImmutableSet.of();
    }
  }

  // This rule just delegates to the output of the `CxxLink` rule and so needs that available at
  // runtime.  Model this via `HasRuntimeDeps`.
  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return Stream.concat(getDeclaredDeps().stream(), executable.getDeps(ruleFinder).stream())
        .map(BuildRule::getBuildTarget);
  }

  public CxxPlatform getCxxPlatform() {
    return cxxPlatform;
  }
}
