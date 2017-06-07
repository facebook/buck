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

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class OmnibusTest {

  @Test
  public void includedDeps() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkable b = new OmnibusNode("//:b");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a, b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM, ImmutableList.of(root), ImmutableList.of());
    assertThat(
        spec.getGraph().getNodes(),
        Matchers.containsInAnyOrder(a.getBuildTarget(), b.getBuildTarget()));
    assertThat(
        spec.getBody().keySet(),
        Matchers.containsInAnyOrder(a.getBuildTarget(), b.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.empty());
    assertThat(spec.getExcluded().keySet(), Matchers.empty());

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
                resolver,
                ruleFinder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget().toString(), "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get("libomnibus.so")),
        pathResolver,
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC),
        b.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC));
  }

  @Test
  public void excludedAndIncludedDeps() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkable b = new OmnibusSharedOnlyNode("//:b");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a, b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM, ImmutableList.of(root), ImmutableList.of());
    assertThat(spec.getGraph().getNodes(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getBody().keySet(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.containsInAnyOrder(b.getBuildTarget()));
    assertThat(spec.getExcluded().keySet(), Matchers.containsInAnyOrder(b.getBuildTarget()));

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
                resolver,
                ruleFinder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(), b.getBuildTarget().toString(), "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM),
        b.getNativeLinkableInput(CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get("libomnibus.so")),
        pathResolver,
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC));
  }

  @Test
  public void excludedDepExcludesTransitiveDep() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkable b = new OmnibusNode("//:b");
    NativeLinkable c = new OmnibusSharedOnlyNode("//:c", ImmutableList.of(b));
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a, c));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM, ImmutableList.of(root), ImmutableList.of());
    assertThat(spec.getGraph().getNodes(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getBody().keySet(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.containsInAnyOrder(c.getBuildTarget()));
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(b.getBuildTarget(), c.getBuildTarget()));

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
                resolver,
                ruleFinder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            b.getBuildTarget().toString(),
            c.getBuildTarget().toString(),
            "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM),
        c.getNativeLinkableInput(CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.SHARED));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertThat(
        libs.get(c.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get("libomnibus.so")),
        pathResolver,
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC));
  }

  @Test
  public void depOfExcludedRoot() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a));
    NativeLinkable b = new OmnibusNode("//:b");
    NativeLinkable excludedRoot = new OmnibusNode("//:excluded_root", ImmutableList.of(b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(excludedRoot));
    assertThat(spec.getGraph().getNodes(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getBody().keySet(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.empty());
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(excludedRoot.getBuildTarget(), b.getBuildTarget()));

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
                resolver,
                ruleFinder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of(excludedRoot)));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            excludedRoot.getBuildTarget().toString(),
            b.getBuildTarget().toString(),
            "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM));
    assertThat(
        libs.get(excludedRoot.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get("libomnibus.so")),
        pathResolver,
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC));
  }

  @Test
  public void commondDepOfIncludedAndExcludedRoots() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a));
    NativeLinkable excludedRoot = new OmnibusNode("//:excluded_root", ImmutableList.of(a));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(excludedRoot));
    assertThat(spec.getGraph().getNodes(), Matchers.empty());
    assertThat(spec.getBody().keySet(), Matchers.empty());
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(excludedRoot.getBuildTarget(), a.getBuildTarget()));

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
                resolver,
                ruleFinder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of(excludedRoot)));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            excludedRoot.getBuildTarget().toString(),
            a.getBuildTarget().toString()));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM));
    assertThat(
        libs.get(excludedRoot.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertThat(
        libs.get(a.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
  }

  @Test
  public void unusedStaticDepsAreNotIncludedInBody() throws NoSuchBuildTargetException {
    NativeLinkable a =
        new OmnibusNode(
            "//:a", ImmutableList.of(), ImmutableList.of(), NativeLinkable.Linkage.STATIC);
    NativeLinkable b = new OmnibusNode("//:b");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a, b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM, ImmutableList.of(root), ImmutableList.of());
    assertThat(spec.getGraph().getNodes(), Matchers.containsInAnyOrder(b.getBuildTarget()));
    assertThat(spec.getBody().keySet(), Matchers.containsInAnyOrder(b.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.empty());
    assertThat(spec.getExcluded().keySet(), Matchers.empty());

    // Verify the libs.
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule")).build(),
                resolver,
                ruleFinder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget().toString(), "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(CxxPlatformUtils.DEFAULT_PLATFORM),
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(ruleFinder, libs.get("libomnibus.so")),
        pathResolver,
        b.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC_PIC));
  }

  private CxxLink getCxxLinkRule(SourcePathRuleFinder ruleFinder, SourcePath path) {
    return ((CxxLink) ruleFinder.getRuleOrThrow((ExplicitBuildTargetSourcePath) path));
  }

  private void assertCxxLinkContainsNativeLinkableInput(
      CxxLink link, SourcePathResolver pathResolver, NativeLinkableInput... inputs) {
    for (NativeLinkableInput input : inputs) {
      assertThat(
          Arg.stringify(link.getArgs(), pathResolver),
          Matchers.hasItems(Arg.stringify(input.getArgs(), pathResolver).toArray(new String[1])));
    }
  }

  private ImmutableMap<String, SourcePath> toSonameMap(OmnibusLibraries libraries) {
    ImmutableMap.Builder<String, SourcePath> map = ImmutableMap.builder();
    for (Map.Entry<BuildTarget, OmnibusRoot> root : libraries.getRoots().entrySet()) {
      map.put(root.getKey().toString(), root.getValue().getPath());
    }
    for (OmnibusLibrary library : libraries.getLibraries()) {
      map.put(library.getSoname(), library.getPath());
    }
    return map.build();
  }
}
