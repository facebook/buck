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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidNativeLibsPackageableGraphEnhancerTest {

  @Test
  public void testNdkLibrary() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver sourcePathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));

    NdkLibrary ndkLibrary =
        new NdkLibraryBuilder(BuildTargetFactory.newInstance("//:ndklib")).build(ruleResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(ndkLibrary));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            target,
            new FakeProjectFilesystem(),
            originalParams,
            ImmutableMap.of(),
            ImmutableSet.of(),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            apkModuleGraph);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            target, ImmutableSet.of(), ImmutableSet.of(), apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(ImmutableSet.of(ndkLibrary)));

    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibrariesOptional =
        enhancer.enhance(collector.build()).getCopyNativeLibraries();
    CopyNativeLibraries copyNativeLibraries =
        copyNativeLibrariesOptional.get().get(apkModuleGraph.getRootAPKModule());

    assertThat(copyNativeLibraries.getStrippedObjectDescriptions(), Matchers.empty());
    assertThat(
        copyNativeLibraries
            .getNativeLibDirectories()
            .stream()
            .map(sourcePathResolver::getRelativePath)
            .collect(MoreCollectors.toImmutableList()),
        Matchers.contains(ndkLibrary.getLibraryPath()));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCxxLibrary() throws Exception {

    NdkCxxPlatform ndkCxxPlatform =
        NdkCxxPlatform.builder()
            .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
            .setCxxRuntime(NdkCxxRuntime.GNUSTL)
            .setCxxSharedRuntimePath(Paths.get("runtime"))
            .setObjdump(new CommandTool.Builder().addArg("objdump").build())
            .build();

    ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms =
        ImmutableMap.<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform>builder()
            .put(NdkCxxPlatforms.TargetCpuType.ARMV7, ndkCxxPlatform)
            .put(NdkCxxPlatforms.TargetCpuType.X86, ndkCxxPlatform)
            .build();

    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxxlib"))
            .setSoname("somelib.so")
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test/bar.cpp"))));
    TargetNode<CxxLibraryDescriptionArg, ?> cxxLibraryDescription = cxxLibraryBuilder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(cxxLibraryDescription);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    CxxLibrary cxxLibrary =
        (CxxLibrary)
            cxxLibraryBuilder.build(ruleResolver, new FakeProjectFilesystem(), targetGraph);
    ruleResolver.addToIndex(cxxLibrary);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(cxxLibrary));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            target,
            new FakeProjectFilesystem(),
            originalParams,
            nativePlatforms,
            ImmutableSet.of(NdkCxxPlatforms.TargetCpuType.ARMV7),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            apkModuleGraph);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            target, ImmutableSet.of(), ImmutableSet.of(), apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(ImmutableSet.of(cxxLibrary)));

    AndroidPackageableCollection packageableCollection = collector.build();
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibrariesOptional =
        enhancer.enhance(packageableCollection).getCopyNativeLibraries();
    CopyNativeLibraries copyNativeLibraries =
        copyNativeLibrariesOptional.get().get(apkModuleGraph.getRootAPKModule());

    assertThat(
        copyNativeLibraries.getStrippedObjectDescriptions(),
        Matchers.containsInAnyOrder(
            Matchers.allOf(
                Matchers.hasProperty(
                    "targetCpuType", Matchers.equalTo(NdkCxxPlatforms.TargetCpuType.ARMV7)),
                Matchers.hasProperty("strippedObjectName", Matchers.equalTo("somelib.so"))),
            Matchers.allOf(
                Matchers.hasProperty(
                    "targetCpuType", Matchers.equalTo(NdkCxxPlatforms.TargetCpuType.ARMV7)),
                Matchers.hasProperty(
                    "strippedObjectName", Matchers.equalTo("libgnustl_shared.so")))));
    assertThat(copyNativeLibraries.getNativeLibDirectories(), Matchers.empty());
    ImmutableCollection<BuildRule> stripRules =
        ruleFinder.filterBuildRuleInputs(
            copyNativeLibraries
                .getStrippedObjectDescriptions()
                .stream()
                .map(StrippedObjectDescription::getSourcePath)
                .collect(MoreCollectors.toImmutableSet()));
    assertThat(
        stripRules,
        Matchers.contains(
            Matchers.instanceOf(StripLinkable.class), Matchers.instanceOf(StripLinkable.class)));
  }

  @Test(expected = HumanReadableException.class)
  public void testEmptyNativePlatformsWithNativeLinkables() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    CxxLibrary cxxLibrary =
        (CxxLibrary)
            new CxxLibraryBuilder(
                    BuildTargetFactory.newInstance("//:cxxlib"), CxxPlatformUtils.DEFAULT_CONFIG)
                .build(ruleResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(cxxLibrary));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            target,
            new FakeProjectFilesystem(),
            originalParams,
            ImmutableMap.of(),
            ImmutableSet.of(),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            apkModuleGraph);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            target, ImmutableSet.of(), ImmutableSet.of(), apkModuleGraph);

    collector.addNativeLinkable(cxxLibrary);

    enhancer.enhance(collector.build());
  }

  @Test
  public void testEmptyNativePlatformsWithNativeLinkableAssets() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    CxxLibrary cxxLibrary =
        (CxxLibrary)
            new CxxLibraryBuilder(
                    BuildTargetFactory.newInstance("//:cxxlib"), CxxPlatformUtils.DEFAULT_CONFIG)
                .build(ruleResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(cxxLibrary));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            ruleResolver,
            target,
            new FakeProjectFilesystem(),
            originalParams,
            ImmutableMap.of(),
            ImmutableSet.of(),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            apkModuleGraph);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            target, ImmutableSet.of(), ImmutableSet.of(), apkModuleGraph);

    collector.addNativeLinkableAsset(cxxLibrary);

    try {
      enhancer.enhance(collector.build());
      fail();
    } catch (HumanReadableException e) {
      assertEquals(
          "No native platforms detected. Probably Android NDK is not configured properly.",
          e.getHumanReadableErrorMessage());
    }
  }
}
