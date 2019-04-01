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

package com.facebook.buck.android;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class NdkLibraryDescriptionTest {

  private static class FakeNativeLinkable extends FakeBuildRule implements NativeLinkable {

    private final SourcePath input;

    public FakeNativeLinkable(String target, SourcePath input, BuildRule... deps) {
      super(target, deps);
      this.input = input;
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
      return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableExportedDeps(BuildRuleResolver ruleResolver) {
      return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType type,
        boolean forceLinkWhole,
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration) {
      return NativeLinkableInput.builder().addArgs(SourcePathArg.of(input)).build();
    }

    @Override
    public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return Linkage.ANY;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      return ImmutableMap.of();
    }
  }

  @Test
  public void transitiveCxxLibraryDepsBecomeFirstOrderDepsOfNdkBuildRule() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    FakeBuildRule transitiveInput =
        graphBuilder.addToIndex(new FakeBuildRule("//:transitive_input"));
    transitiveInput.setOutputFile("out");
    FakeNativeLinkable transitiveDep =
        graphBuilder.addToIndex(
            new FakeNativeLinkable("//:transitive_dep", transitiveInput.getSourcePathToOutput()));
    FakeBuildRule firstOrderInput =
        graphBuilder.addToIndex(new FakeBuildRule("//:first_order_input"));
    firstOrderInput.setOutputFile("out");
    FakeNativeLinkable firstOrderDep =
        graphBuilder.addToIndex(
            new FakeNativeLinkable(
                "//:first_order_dep", firstOrderInput.getSourcePathToOutput(), transitiveDep));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule ndkLibrary =
        new NdkLibraryBuilder(target).addDep(firstOrderDep.getBuildTarget()).build(graphBuilder);

    assertThat(
        ndkLibrary.getBuildDeps(),
        Matchers.allOf(
            Matchers.<BuildRule>hasItem(firstOrderInput),
            Matchers.<BuildRule>hasItem(transitiveInput)));
  }
}
