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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class CxxBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule,
        NativeTestable,
        HasRuntimeDeps,
        HasAppleDebugSymbolDeps,
        SupportsInputBasedRuleKey,
        HasSupplementaryOutputs {

  private final CxxPlatform cxxPlatform;
  private final BuildRule linkRule;
  private final Tool executable;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final ImmutableSortedSet<FrameworkPath> frameworks;
  private final BuildTarget platformlessTarget;
  private final boolean cacheable;

  public CxxBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      CxxPlatform cxxPlatform,
      BuildRule linkRule,
      Tool executable,
      Iterable<FrameworkPath> frameworks,
      Iterable<BuildTarget> tests,
      BuildTarget platformlessTarget,
      boolean cacheable) {
    super(buildTarget, projectFilesystem, params);
    this.cxxPlatform = cxxPlatform;
    this.linkRule = linkRule;
    this.executable = executable;
    this.tests = ImmutableSortedSet.copyOf(tests);
    this.frameworks = ImmutableSortedSet.copyOf(frameworks);
    this.platformlessTarget = platformlessTarget;
    this.cacheable = cacheable;
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
        !getBuildTarget().getFlavors().contains(CxxStrip.RULE_FLAVOR),
        "CxxBinary (%s) build target should not contain CxxStrip rule flavor %s. Otherwise "
            + "it may be not possible to distinguish CxxBinary (%s) and link rule (%s) in graph.",
        this,
        CxxStrip.RULE_FLAVOR,
        this,
        linkRule);
    Preconditions.checkArgument(
        this.platformlessTarget
            .getUnflavoredBuildTarget()
            .equals(getBuildTarget().getUnflavoredBuildTarget()));
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
    return ForwardingBuildTargetSourcePath.of(
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
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return CxxPreprocessables.getCxxPreprocessorInput(
        platformlessTarget,
        graphBuilder,
        /* hasHeaderSymlinkTree */ true,
        cxxPlatform,
        HeaderVisibility.PRIVATE,
        CxxPreprocessables.IncludeType.LOCAL,
        ImmutableMultimap.of(),
        frameworks);
  }

  @Override
  public Stream<BuildRule> getAppleDebugSymbolDeps() {
    if (linkRule instanceof HasAppleDebugSymbolDeps) {
      return ((HasAppleDebugSymbolDeps) linkRule).getAppleDebugSymbolDeps();
    } else {
      return Stream.empty();
    }
  }

  @Override
  public boolean isCacheable() {
    return cacheable;
  }

  // This rule just delegates to the output of the `CxxLink` rule and so needs that available at
  // runtime.  Model this via `HasRuntimeDeps`.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.concat(
            getDeclaredDeps().stream(), BuildableSupport.getDeps(executable, ruleFinder))
        .map(BuildRule::getBuildTarget);
  }

  public CxxPlatform getCxxPlatform() {
    return cxxPlatform;
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToSupplementaryOutput(String name) {
    if (linkRule instanceof HasSupplementaryOutputs) {
      SourcePath path =
          ((HasSupplementaryOutputs) linkRule).getSourcePathToSupplementaryOutput(name);
      if (path != null) {
        return ForwardingBuildTargetSourcePath.of(getBuildTarget(), path);
      }
    }
    return null;
  }
}
