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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;

import org.hamcrest.Matchers;
import org.junit.Test;

public class NdkLibraryDescriptionTest {

  private static class FakeNativeLinkable extends FakeBuildRule implements NativeLinkable {

    private final BuildTargetSourcePath input;

    public FakeNativeLinkable(
        String target,
        SourcePathResolver resolver,
        BuildTargetSourcePath input,
        BuildRule... deps) {
      super(target, resolver, deps);
      this.input = input;
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType type) {
      return NativeLinkableInput.builder()
          .addInputs(input)
          .build();
    }

    @Override
    public Optional<Linker.LinkableDepType> getPreferredLinkage(CxxPlatform cxxPlatform) {
      return Optional.absent();
    }

  }

  @Test
  public void transitiveCxxLibraryDepsBecomeFirstOrderDepsOfNdkBuildRule() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    FakeBuildRule transitiveInput = resolver.addToIndex(
        new FakeBuildRule("//:transitive_input", pathResolver));
    FakeNativeLinkable transitiveDep =
        resolver.addToIndex(
            new FakeNativeLinkable(
                "//:transitive_dep",
                pathResolver,
                new BuildTargetSourcePath(filesystem, transitiveInput.getBuildTarget())));
    FakeBuildRule firstOrderInput = resolver.addToIndex(
        new FakeBuildRule("//:first_order_input", pathResolver));
    FakeNativeLinkable firstOrderDep =
        resolver.addToIndex(
            new FakeNativeLinkable(
                "//:first_order_dep",
                pathResolver,
                new BuildTargetSourcePath(filesystem, firstOrderInput.getBuildTarget()),
                transitiveDep));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule ndkLibrary = new NdkLibraryBuilder(target)
        .addDep(firstOrderDep.getBuildTarget())
        .build(resolver);

    assertThat(
        ndkLibrary.getDeps(),
        Matchers.allOf(
            Matchers.<BuildRule>hasItem(firstOrderInput),
            Matchers.<BuildRule>hasItem(transitiveInput)));
  }

}
