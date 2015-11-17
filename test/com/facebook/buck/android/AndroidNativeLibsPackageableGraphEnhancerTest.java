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

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.AbstractCxxSourceBuilder;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;

public class AndroidNativeLibsPackageableGraphEnhancerTest {

  @Test
  public void testNdkLibrary() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleResolver);

    NdkLibrary ndkLibrary = (NdkLibrary) new NdkLibraryBuilder(
        BuildTargetFactory.newInstance("//:ndklib"))
        .build(ruleResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(ndkLibrary))
            .build();

    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            originalParams,
            ImmutableMap.<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform>of(),
            ImmutableSet.<NdkCxxPlatforms.TargetCpuType>of()
        );

    AndroidPackageableCollector collector = new AndroidPackageableCollector(
        target,
        ImmutableSet.<BuildTarget>of(),
        ImmutableSet.<BuildTarget>of());
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(
            ImmutableSet.<BuildRule>of(ndkLibrary)));

    Optional<CopyNativeLibraries> copyNativeLibrariesOptional =
        enhancer.getCopyNativeLibraries(TargetGraph.EMPTY, collector.build());
    CopyNativeLibraries copyNativeLibraries = copyNativeLibrariesOptional.get();

    assertThat(copyNativeLibraries.getStrippedObjectDescriptions(), Matchers.empty());
    assertThat(
        FluentIterable.from(copyNativeLibraries.getNativeLibDirectories())
            .transform(sourcePathResolver.deprecatedPathFunction())
            .toList(),
        Matchers.contains(
            ndkLibrary.getLibraryPath()
        )
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCxxLibrary() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleResolver);

    NdkCxxPlatform ndkCxxPlatform =
        NdkCxxPlatform.builder()
            .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
            .setCxxRuntime(NdkCxxPlatforms.CxxRuntime.GNUSTL)
            .setCxxSharedRuntimePath(Paths.get("runtime"))
            .build();

    ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms =
        ImmutableMap.<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform>builder()
        .put(NdkCxxPlatforms.TargetCpuType.ARMV7, ndkCxxPlatform)
        .put(NdkCxxPlatforms.TargetCpuType.X86, ndkCxxPlatform)
        .build();

    AbstractCxxSourceBuilder<CxxLibraryDescription.Arg> cxxLibraryBuilder = new CxxLibraryBuilder(
        BuildTargetFactory.newInstance("//:cxxlib"))
        .setSoname("somelib.so")
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test/bar.cpp"))));
    TargetNode<CxxLibraryDescription.Arg> cxxLibraryDescription = cxxLibraryBuilder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(cxxLibraryDescription);
    CxxLibrary cxxLibrary = (CxxLibrary) cxxLibraryBuilder.build(
        ruleResolver,
        new FakeProjectFilesystem(),
        targetGraph);
    ruleResolver.addToIndex(cxxLibrary);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(cxxLibrary))
            .build();

    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            originalParams,
            nativePlatforms,
            ImmutableSet.of(NdkCxxPlatforms.TargetCpuType.ARMV7)
        );

    AndroidPackageableCollector collector = new AndroidPackageableCollector(
        target,
        ImmutableSet.<BuildTarget>of(),
        ImmutableSet.<BuildTarget>of());
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(
            ImmutableSet.<BuildRule>of(cxxLibrary)));

    AndroidPackageableCollection packageableCollection = collector.build();
    Optional<CopyNativeLibraries> copyNativeLibrariesOptional =
        enhancer.getCopyNativeLibraries(targetGraph, packageableCollection);
    CopyNativeLibraries copyNativeLibraries = copyNativeLibrariesOptional.get();

    assertThat(
        copyNativeLibraries.getStrippedObjectDescriptions(),
        Matchers.containsInAnyOrder(
            Matchers.allOf(
                Matchers.hasProperty(
                    "targetCpuType",
                    Matchers.equalTo(NdkCxxPlatforms.TargetCpuType.ARMV7)),
                Matchers.hasProperty(
                    "strippedObjectName",
                    Matchers.equalTo("somelib.so"))
            ),
            Matchers.allOf(
                Matchers.hasProperty(
                    "targetCpuType",
                    Matchers.equalTo(NdkCxxPlatforms.TargetCpuType.ARMV7)),
                Matchers.hasProperty(
                    "strippedObjectName",
                    Matchers.equalTo("libgnustl_shared.so"))
            )
        )
    );
    assertThat(copyNativeLibraries.getNativeLibDirectories(), Matchers.empty());
    ImmutableCollection<BuildRule> stripRules = sourcePathResolver.filterBuildRuleInputs(
        FluentIterable.from(copyNativeLibraries.getStrippedObjectDescriptions())
            .transform(
                new Function<StrippedObjectDescription, SourcePath>() {
                  @Override
                  public SourcePath apply(StrippedObjectDescription input) {
                    return input.getSourcePath();
                  }
                })
            .toSet());
    assertThat(
        stripRules,
        Matchers.contains(
            Matchers.instanceOf(StripLinkable.class),
            Matchers.instanceOf(StripLinkable.class)));
  }
}
