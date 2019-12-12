/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx.toolchain.nativelink;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
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
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup.Linkage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class NativeLinkableGroupTest {

  private static class FakeNativeLinkableGroup extends FakeBuildRule
      implements LegacyNativeLinkableGroup {

    private final Iterable<NativeLinkableGroup> deps;
    private final Iterable<NativeLinkableGroup> exportedDeps;
    private final NativeLinkableGroup.Linkage preferredLinkage;
    private final NativeLinkableInput nativeLinkableInput;
    private final ImmutableMap<String, SourcePath> sharedLibraries;
    private final PlatformLockedNativeLinkableGroup.Cache linkableCache =
        LegacyNativeLinkableGroup.getNativeLinkableCache(this);

    FakeNativeLinkableGroup(
        String target,
        Iterable<? extends NativeLinkableGroup> deps,
        Iterable<? extends NativeLinkableGroup> exportedDeps,
        NativeLinkableGroup.Linkage preferredLinkage,
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
    public PlatformLockedNativeLinkableGroup.Cache getNativeLinkableCompatibilityCache() {
      return linkableCache;
    }

    @Override
    public Iterable<NativeLinkableGroup> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
      return deps;
    }

    @Override
    public Iterable<NativeLinkableGroup> getNativeLinkableExportedDeps(
        BuildRuleResolver ruleResolver) {
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
    public NativeLinkableGroup.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return preferredLinkage;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      return sharedLibraries;
    }
  }

  @Test
  public void gatherTransitiveSharedLibraries() {
    FakeNativeLinkableGroup c =
        new FakeNativeLinkableGroup(
            "//:c",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkableGroup.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libc.so", FakeSourcePath.of("libc.so")));
    FakeNativeLinkableGroup b =
        new FakeNativeLinkableGroup(
            "//:b",
            ImmutableList.of(c),
            ImmutableList.of(),
            NativeLinkableGroup.Linkage.STATIC,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libb.so", FakeSourcePath.of("libb.so")));
    FakeNativeLinkableGroup a =
        new FakeNativeLinkableGroup(
            "//:a",
            ImmutableList.of(b),
            ImmutableList.of(),
            NativeLinkableGroup.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", FakeSourcePath.of("liba.so")));
    ImmutableMap<BuildTarget, NativeLinkableGroup> roots =
        NativeLinkableGroups.getNativeLinkableRoots(ImmutableList.of(a), r -> Optional.empty());
    TestActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ImmutableSortedMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            graphBuilder,
            Iterables.transform(
                roots.values(),
                g -> g.getNativeLinkable(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)),
            true);
    assertThat(
        sharedLibs,
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "liba.so", FakeSourcePath.of("liba.so"),
                "libc.so", FakeSourcePath.of("libc.so"))));
  }

  @Test
  public void getLinkStyle() {
    assertThat(
        NativeLinkableGroups.getLinkStyle(
            NativeLinkableGroup.Linkage.STATIC, Linker.LinkableDepType.SHARED),
        Matchers.equalTo(Linker.LinkableDepType.STATIC_PIC));
    assertThat(
        NativeLinkableGroups.getLinkStyle(
            NativeLinkableGroup.Linkage.SHARED, Linker.LinkableDepType.STATIC),
        Matchers.equalTo(Linker.LinkableDepType.SHARED));
    assertThat(
        NativeLinkableGroups.getLinkStyle(
            NativeLinkableGroup.Linkage.ANY, Linker.LinkableDepType.STATIC),
        Matchers.equalTo(Linker.LinkableDepType.STATIC));
  }

  @Test(expected = HumanReadableException.class)
  public void duplicateDifferentLibsConflict() {
    FakeNativeLinkableGroup a =
        new FakeNativeLinkableGroup(
            "//:a",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkableGroup.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", FakeSourcePath.of("liba1.so")));
    FakeNativeLinkableGroup b =
        new FakeNativeLinkableGroup(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkableGroup.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", FakeSourcePath.of("liba2.so")));
    ImmutableMap<BuildTarget, NativeLinkableGroup> roots =
        NativeLinkableGroups.getNativeLinkableRoots(ImmutableList.of(a, b), n -> Optional.empty());
    TestActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    NativeLinkables.getTransitiveSharedLibraries(
        graphBuilder,
        Iterables.transform(
            roots.values(),
            g -> g.getNativeLinkable(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)),
        true);
  }

  @Test
  public void duplicateIdenticalLibsDoNotConflict() {
    PathSourcePath path = FakeSourcePath.of("libc.so");
    FakeNativeLinkableGroup a =
        new FakeNativeLinkableGroup(
            "//:a",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkableGroup.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libc.so", path));
    FakeNativeLinkableGroup b =
        new FakeNativeLinkableGroup(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            NativeLinkableGroup.Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libc.so", path));
    ImmutableMap<BuildTarget, NativeLinkableGroup> roots =
        NativeLinkableGroups.getNativeLinkableRoots(ImmutableList.of(a, b), n -> Optional.empty());
    TestActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ImmutableSortedMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            graphBuilder,
            Iterables.transform(
                roots.values(),
                g -> g.getNativeLinkable(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)),
            true);
    assertThat(
        sharedLibs, Matchers.equalTo(ImmutableSortedMap.<String, SourcePath>of("libc.so", path)));
  }

  @Test
  public void transitiveSharedLibrariesDynamicallyLinksStaticRoots() {
    FakeNativeLinkableGroup b =
        new FakeNativeLinkableGroup(
            "//:b",
            ImmutableList.of(),
            ImmutableList.of(),
            Linkage.ANY,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("libb.so", FakeSourcePath.of("libb.so")));
    FakeNativeLinkableGroup a =
        new FakeNativeLinkableGroup(
            "//:a",
            ImmutableList.of(b),
            ImmutableList.of(),
            Linkage.STATIC,
            NativeLinkableInput.builder().build(),
            ImmutableMap.of("liba.so", FakeSourcePath.of("liba.so")));
    ImmutableMap<BuildTarget, NativeLinkableGroup> roots =
        NativeLinkableGroups.getNativeLinkableRoots(ImmutableList.of(a), r -> Optional.empty());
    TestActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ImmutableSortedMap<String, SourcePath> sharedLibs =
        NativeLinkables.getTransitiveSharedLibraries(
            graphBuilder,
            Iterables.transform(
                roots.values(),
                g -> g.getNativeLinkable(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)),
            true);
    assertThat(
        sharedLibs,
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "liba.so", FakeSourcePath.of("liba.so"),
                "libb.so", FakeSourcePath.of("libb.so"))));
  }
}
