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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import org.hamcrest.Matchers;
import org.junit.Test;

public class NativeLinkablesTest {

  private static class FakeNativeLinkable extends FakeBuildRule implements NativeLinkable {

    private final NativeLinkableInput nativeLinkableInput;
    private final NativeLinkable.Linkage preferredLinkage;
    private final ImmutableMap<String, SourcePath> sharedLibraries;

    public FakeNativeLinkable(
        String target,
        SourcePathResolver resolver,
        NativeLinkableInput nativeLinkableInput,
        NativeLinkable.Linkage preferredLinkage,
        ImmutableMap<String, SourcePath> sharedLibraries,
        BuildRule... deps) {
      super(target, resolver, deps);
      this.nativeLinkableInput = nativeLinkableInput;
      this.preferredLinkage = preferredLinkage;
      this.sharedLibraries =  sharedLibraries;
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        TargetGraph targetGraph,
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType type) {
      return nativeLinkableInput;
    }

    @Override
    public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return preferredLinkage;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(
        TargetGraph targetGraph,
        CxxPlatform cxxPlatform) {
      return sharedLibraries;
    }

  }

  /**
   * Consider the following graph of C/C++ library dependencies of a python binary rule.  In this
   * case we dynamically link all C/C++ library deps except for ones that request static linkage
   * via the `force_static` parameter (e.g. library `C` in this example):
   *
   *           Python Binary
   *                |
   *                |
   *           A (shared)
   *               / \
   *              /   \
   *             /     \
   *            /       \
   *       B (shared)   ...
   *         /    \
   *        /      \
   *       /      ...
   *   C (static)
   *       \
   *        \
   *         \
   *          \
   *        D (shared)
   *
   * Handling this force static dep is tricky -- we need to make sure we *only* statically link it
   * into the shared lib `B` and that it does *not* contribute to the link line formed for `A`.
   * What's more, we need to make sure `D` still contributes to the link for `A`.
   *
   */
  @Test
  public void doNotPullInStaticLibsAcrossSharedLibs() {
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());

    BuildRule d = new FakeNativeLinkable(
        "//:d",
        resolver,
        NativeLinkableInput.builder()
            .addArgs("d")
            .build(),
        NativeLinkable.Linkage.ANY,
        ImmutableMap.<String, SourcePath>of());

    BuildRule c = new FakeNativeLinkable(
        "//:c",
        resolver,
        NativeLinkableInput.builder()
            .addArgs("c")
            .build(),
        NativeLinkable.Linkage.STATIC,
        ImmutableMap.<String, SourcePath>of(),
        d);

    BuildRule b = new FakeNativeLinkable(
        "//:b",
        resolver,
        NativeLinkableInput.builder()
            .addArgs("b")
            .build(),
        NativeLinkable.Linkage.ANY,
        ImmutableMap.<String, SourcePath>of(),
        c);

    BuildRule a = new FakeNativeLinkable(
        "//:a",
        resolver,
        NativeLinkableInput.builder()
            .addArgs("a")
            .build(),
        NativeLinkable.Linkage.ANY,
        ImmutableMap.<String, SourcePath>of(),
        b);

    // Collect the transitive native linkable input for the top-level rule (e.g. the imaginary
    // python binary rule) and verify that we do *not* pull in input from `C`.
    NativeLinkableInput inputForTop =
        NativeLinkables.getTransitiveNativeLinkableInput(
            TargetGraph.EMPTY,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(a),
            Linker.LinkableDepType.SHARED,
            /* reverse */ false);
    assertThat(inputForTop.getArgs(), Matchers.containsInAnyOrder("a", "b", "d"));
    assertThat(inputForTop.getArgs(), Matchers.not(Matchers.contains("c")));

    // However, when collecting the transitive native linkable input for `B`, we *should* have
    // input from `C`.
    NativeLinkableInput inputForB =
        NativeLinkables.getTransitiveNativeLinkableInput(
            TargetGraph.EMPTY,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(c),
            Linker.LinkableDepType.SHARED,
            /* reverse */ false);
    assertThat(inputForB.getArgs(), Matchers.containsInAnyOrder("c", "d"));
  }

  @Test
  public void gatherTransitiveSharedLibraries() {
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());

    BuildRule c =
        new FakeNativeLinkable(
            "//:c",
            resolver,
            NativeLinkableInput.builder().build(),
            NativeLinkable.Linkage.ANY,
            ImmutableMap.<String, SourcePath>of("libc.so", new TestSourcePath("libc.so")));

    BuildRule b =
        new FakeNativeLinkable(
            "//:b",
            resolver,
            NativeLinkableInput.builder().build(),
            NativeLinkable.Linkage.STATIC,
            // Should be ignored, since this library supposed to be linked statically.
            ImmutableMap.<String, SourcePath>of("libb.so", new TestSourcePath("libb.so")),
            c);

    BuildRule a =
        new FakeNativeLinkable(
            "//:a",
            resolver,
            NativeLinkableInput.builder().build(),
            NativeLinkable.Linkage.ANY,
            ImmutableMap.<String, SourcePath>of("liba.so", new TestSourcePath("liba.so")),
            b);

    // However, when collecting the transitive native linkable input for `B`, we *should* have
    // input from `C`.
    ImmutableSortedMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            TargetGraph.EMPTY,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(a),
            Linker.LinkableDepType.SHARED,
            Predicates.instanceOf(NativeLinkable.class));
    assertThat(
        sharedLibs,
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "liba.so", new TestSourcePath("liba.so"),
                "libc.so", new TestSourcePath("libc.so"))));
  }

}
