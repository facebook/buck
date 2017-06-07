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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class NativeLinkablesTest {

  private static class FakeNativeLinkable extends FakeBuildRule implements NativeLinkable {

    private final Iterable<NativeLinkable> deps;
    private final Iterable<NativeLinkable> exportedDeps;
    private final NativeLinkable.Linkage preferredLinkage;
    private final NativeLinkableInput nativeLinkableInput;
    private final ImmutableMap<String, SourcePath> sharedLibraries;

    public FakeNativeLinkable(
        String target,
        Iterable<? extends NativeLinkable> deps,
        Iterable<? extends NativeLinkable> exportedDeps,
        NativeLinkable.Linkage preferredLinkage,
        NativeLinkableInput nativeLinkableInput,
        ImmutableMap<String, SourcePath> sharedLibraries,
        BuildRule... ruleDeps) {
      super(
          BuildTargetFactory.newInstance(target),
          new SourcePathResolver(
              new SourcePathRuleFinder(
                  new BuildRuleResolver(
                      TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()))),
          ImmutableSortedSet.copyOf(ruleDeps));
      this.deps = ImmutableList.copyOf(deps);
      this.exportedDeps = ImmutableList.copyOf(exportedDeps);
      this.preferredLinkage = preferredLinkage;
      this.nativeLinkableInput = nativeLinkableInput;
      this.sharedLibraries = sharedLibraries;
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableDeps() {
      return deps;
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableExportedDeps() {
      return exportedDeps;
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform, Linker.LinkableDepType type) {
      return nativeLinkableInput;
    }

    @Override
    public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return preferredLinkage;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
      return sharedLibraries;
    }
  }

  @Test
  public void regularDepsUsingSharedLinkageAreNotTransitive() {
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("b")).build(),
            ImmutableMap.of());
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(b),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("a")).build(),
            ImmutableMap.of());
    assertThat(
        NativeLinkables.getNativeLinkables(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(a),
                Linker.LinkableDepType.SHARED)
            .keySet(),
        Matchers.not(Matchers.hasItem(b.getBuildTarget())));
  }

  @Test
  public void exportedDepsUsingSharedLinkageAreTransitive() {
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("b")).build(),
            ImmutableMap.of());
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(),
            ImmutableList.of(b),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("a")).build(),
            ImmutableMap.of());
    assertThat(
        NativeLinkables.getNativeLinkables(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(a),
                Linker.LinkableDepType.SHARED)
            .keySet(),
        Matchers.hasItem(b.getBuildTarget()));
  }

  @Test
  public void regularDepsFromStaticLibsUsingSharedLinkageAreTransitive() {
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("b")).build(),
            ImmutableMap.of());
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(b),
            ImmutableList.of(),
            NativeLinkable.Linkage.STATIC,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("a")).build(),
            ImmutableMap.of());
    assertThat(
        NativeLinkables.getNativeLinkables(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(a),
                Linker.LinkableDepType.SHARED)
            .keySet(),
        Matchers.hasItem(b.getBuildTarget()));
  }

  @Test
  public void regularDepsUsingStaticLinkageAreTransitive() {
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("b")).build(),
            ImmutableMap.of());
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(b),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("a")).build(),
            ImmutableMap.of());
    assertThat(
        NativeLinkables.getNativeLinkables(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(a),
                Linker.LinkableDepType.STATIC)
            .keySet(),
        Matchers.hasItem(b.getBuildTarget()));
  }

  @Test
  public void gatherTransitiveSharedLibraries() throws Exception {
    FakeNativeLinkable c =
        new FakeNativeLinkable(
            "//:c",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libc.so", new FakeSourcePath("libc.so")));
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(c),
            ImmutableList.of(),
            NativeLinkable.Linkage.STATIC,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libb.so", new FakeSourcePath("libb.so")));
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(b),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", new FakeSourcePath("liba.so")));
    ImmutableSortedMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(a),
            NativeLinkable.class::isInstance);
    assertThat(
        sharedLibs,
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "liba.so", new FakeSourcePath("liba.so"),
                "libc.so", new FakeSourcePath("libc.so"))));
  }

  @Test
  public void nonNativeLinkableDepsAreIgnored() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    FakeNativeLinkable c =
        new FakeNativeLinkable(
            "//:c",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("c")).build(),
            ImmutableMap.of());
    FakeBuildRule b = new FakeBuildRule("//:b", pathResolver, c);
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("a")).build(),
            ImmutableMap.of(),
            b);
    assertThat(a.getBuildDeps(), Matchers.hasItem(b));
    assertThat(
        NativeLinkables.getNativeLinkables(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(a),
                Linker.LinkableDepType.STATIC)
            .keySet(),
        Matchers.not(Matchers.hasItem(c.getBuildTarget())));
  }

  @Test
  public void getLinkStyle() {
    assertThat(
        NativeLinkables.getLinkStyle(NativeLinkable.Linkage.STATIC, Linker.LinkableDepType.SHARED),
        Matchers.equalTo(Linker.LinkableDepType.STATIC_PIC));
    assertThat(
        NativeLinkables.getLinkStyle(NativeLinkable.Linkage.SHARED, Linker.LinkableDepType.STATIC),
        Matchers.equalTo(Linker.LinkableDepType.SHARED));
    assertThat(
        NativeLinkables.getLinkStyle(NativeLinkable.Linkage.ANY, Linker.LinkableDepType.STATIC),
        Matchers.equalTo(Linker.LinkableDepType.STATIC));
  }

  @Test(expected = HumanReadableException.class)
  public void duplicateDifferentLibsConflict() throws Exception {
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", new FakeSourcePath("liba1.so")));
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", new FakeSourcePath("liba2.so")));
    NativeLinkables.getTransitiveSharedLibraries(
        CxxPlatformUtils.DEFAULT_PLATFORM,
        ImmutableList.of(a, b),
        NativeLinkable.class::isInstance);
  }

  @Test
  public void duplicateIdenticalLibsDoNotConflict() throws Exception {
    FakeSourcePath path = new FakeSourcePath("libc.so");
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libc.so", path));
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libc.so", path));
    ImmutableSortedMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(a, b),
            NativeLinkable.class::isInstance);
    assertThat(
        sharedLibs, Matchers.equalTo(ImmutableSortedMap.<String, SourcePath>of("libc.so", path)));
  }

  @Test
  public void traversePredicate() throws Exception {
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of());
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.<NativeLinkable>of(b),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of());
    assertThat(
        NativeLinkables.getNativeLinkables(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(a),
            Linker.LinkableDepType.STATIC,
            x -> true),
        Matchers.equalTo(
            ImmutableMap.<BuildTarget, NativeLinkable>of(
                a.getBuildTarget(), a,
                b.getBuildTarget(), b)));
    assertThat(
        NativeLinkables.getNativeLinkables(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(a),
            Linker.LinkableDepType.STATIC,
            a::equals),
        Matchers.equalTo(ImmutableMap.<BuildTarget, NativeLinkable>of(a.getBuildTarget(), a)));
  }
}
