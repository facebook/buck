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

package com.facebook.buck.cxx.toolchain.nativelink;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable.Linkage;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Test;

public class NativeLinkablesTest {

  private static class FakeNativeLinkable extends FakeBuildRule implements NativeLinkable {

    private final Iterable<NativeLinkable> deps;
    private final Iterable<NativeLinkable> exportedDeps;
    private final NativeLinkable.Linkage preferredLinkage;
    private final NativeLinkableInput nativeLinkableInput;
    private final ImmutableMap<String, SourcePath> sharedLibraries;

    FakeNativeLinkable(
        String target,
        Iterable<? extends NativeLinkable> deps,
        Iterable<? extends NativeLinkable> exportedDeps,
        NativeLinkable.Linkage preferredLinkage,
        NativeLinkableInput nativeLinkableInput,
        ImmutableMap<String, SourcePath> sharedLibraries,
        BuildRule... ruleDeps) {
      super(BuildTargetFactory.newInstance(target), ImmutableSortedSet.copyOf(ruleDeps));
      this.deps = ImmutableList.copyOf(deps);
      this.exportedDeps = ImmutableList.copyOf(exportedDeps);
      this.preferredLinkage = preferredLinkage;
      this.nativeLinkableInput = nativeLinkableInput;
      this.sharedLibraries = sharedLibraries;
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
      return deps;
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableExportedDeps(BuildRuleResolver ruleResolver) {
      return exportedDeps;
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType type,
        boolean forceLinkWhole,
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration) {
      return nativeLinkableInput;
    }

    @Override
    public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return preferredLinkage;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
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
                new TestActionGraphBuilder(),
                ImmutableList.of(a),
                Linker.LinkableDepType.SHARED)
            .stream()
            .map(NativeLinkable::getBuildTarget)
            .collect(Collectors.toSet()),
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
                new TestActionGraphBuilder(),
                ImmutableList.of(a),
                Linker.LinkableDepType.SHARED)
            .stream()
            .map(NativeLinkable::getBuildTarget)
            .collect(Collectors.toSet()),
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
                new TestActionGraphBuilder(),
                ImmutableList.of(a),
                Linker.LinkableDepType.SHARED)
            .stream()
            .map(NativeLinkable::getBuildTarget)
            .collect(Collectors.toSet()),
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
                new TestActionGraphBuilder(),
                ImmutableList.of(a),
                Linker.LinkableDepType.STATIC)
            .stream()
            .map(NativeLinkable::getBuildTarget)
            .collect(Collectors.toSet()),
        Matchers.hasItem(b.getBuildTarget()));
  }

  @Test
  public void gatherTransitiveSharedLibraries() {
    FakeNativeLinkable c =
        new FakeNativeLinkable(
            "//:c",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libc.so", FakeSourcePath.of("libc.so")));
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(c),
            ImmutableList.of(),
            NativeLinkable.Linkage.STATIC,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libb.so", FakeSourcePath.of("libb.so")));
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(b),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", FakeSourcePath.of("liba.so")));
    ImmutableSortedMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            new TestActionGraphBuilder(),
            ImmutableList.of(a),
            r -> Optional.empty(),
            true);
    assertThat(
        sharedLibs,
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "liba.so", FakeSourcePath.of("liba.so"),
                "libc.so", FakeSourcePath.of("libc.so"))));
  }

  @Test
  public void nonNativeLinkableDepsAreIgnored() {
    FakeNativeLinkable c =
        new FakeNativeLinkable(
            "//:c",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().addAllArgs(StringArg.from("c")).build(),
            ImmutableMap.of());
    FakeBuildRule b = new FakeBuildRule("//:b", c);
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
                new TestActionGraphBuilder(),
                ImmutableList.of(a),
                Linker.LinkableDepType.STATIC)
            .stream()
            .map(NativeLinkable::getBuildTarget)
            .collect(Collectors.toSet()),
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
  public void duplicateDifferentLibsConflict() {
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", FakeSourcePath.of("liba1.so")));
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkable.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", FakeSourcePath.of("liba2.so")));
    NativeLinkables.getTransitiveSharedLibraries(
        CxxPlatformUtils.DEFAULT_PLATFORM,
        new TestActionGraphBuilder(),
        ImmutableList.of(a, b),
        n -> Optional.empty(),
        true);
  }

  @Test
  public void duplicateIdenticalLibsDoNotConflict() {
    PathSourcePath path = FakeSourcePath.of("libc.so");
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
            new TestActionGraphBuilder(),
            ImmutableList.of(a, b),
            n -> Optional.empty(),
            true);
    assertThat(
        sharedLibs, Matchers.equalTo(ImmutableSortedMap.<String, SourcePath>of("libc.so", path)));
  }

  @Test
  public void traversePredicate() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
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
                graphBuilder,
                ImmutableList.of(a),
                Linker.LinkableDepType.STATIC,
                n -> true)
            .stream()
            .map(NativeLinkable::getBuildTarget)
            .collect(Collectors.toSet()),
        Matchers.equalTo(ImmutableSet.of(a.getBuildTarget(), b.getBuildTarget())));
    assertThat(
        NativeLinkables.getNativeLinkables(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                graphBuilder,
                ImmutableList.of(a),
                Linker.LinkableDepType.STATIC,
                a::equals)
            .stream()
            .map(NativeLinkable::getBuildTarget)
            .collect(Collectors.toSet()),
        Matchers.equalTo(ImmutableSet.of(a.getBuildTarget())));
  }

  @Test
  public void transitiveSharedLibrariesDynamicallyLinksStaticRoots() {
    FakeNativeLinkable b =
        new FakeNativeLinkable(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libb.so", FakeSourcePath.of("libb.so")));
    FakeNativeLinkable a =
        new FakeNativeLinkable(
            "//:a",
            ImmutableList.of(b),
            ImmutableList.of(),
            Linkage.STATIC,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", FakeSourcePath.of("liba.so")));
    ImmutableSortedMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            new TestActionGraphBuilder(),
            ImmutableList.of(a),
            r -> Optional.empty(),
            true);
    assertThat(
        sharedLibs,
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "liba.so", FakeSourcePath.of("liba.so"),
                "libb.so", FakeSourcePath.of("libb.so"))));
  }
}
