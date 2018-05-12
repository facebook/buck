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

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AndroidNativeLibsPackageableGraphEnhancerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testNdkLibrary() throws Exception {
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    SourcePathResolver sourcePathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));

    NdkLibrary ndkLibrary =
        new NdkLibraryBuilder(BuildTargetFactory.newInstance("//:ndklib")).build(ruleResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(ndkLibrary));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(ImmutableMap.of()))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            ruleResolver,
            target,
            projectFilesystem,
            originalParams,
            ImmutableSet.of(),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            ImmutableList.of(),
            apkModuleGraph);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            target, ImmutableSet.of(), ImmutableSet.of(), apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(ImmutableSet.of(ndkLibrary)), ruleResolver);

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
            .collect(ImmutableList.toImmutableList()),
        Matchers.contains(ndkLibrary.getLibraryPath()));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCxxLibrary() {

    NdkCxxPlatform ndkCxxPlatform =
        NdkCxxPlatform.builder()
            .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
            .setCxxRuntime(NdkCxxRuntime.GNUSTL)
            .setCxxSharedRuntimePath(Paths.get("runtime"))
            .setObjdump(new CommandTool.Builder().addArg("objdump").build())
            .build();

    ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms =
        ImmutableMap.<TargetCpuType, NdkCxxPlatform>builder()
            .put(TargetCpuType.ARMV7, ndkCxxPlatform)
            .put(TargetCpuType.X86, ndkCxxPlatform)
            .build();

    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxxlib"))
            .setSoname("somelib.so")
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test/bar.cpp"))));
    TargetNode<CxxLibraryDescriptionArg, ?> cxxLibraryDescription = cxxLibraryBuilder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(cxxLibraryDescription);
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    CxxLibrary cxxLibrary =
        (CxxLibrary)
            cxxLibraryBuilder.build(ruleResolver, new FakeProjectFilesystem(), targetGraph);
    ruleResolver.addToIndex(cxxLibrary);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(cxxLibrary));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(nativePlatforms))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            ruleResolver,
            target,
            projectFilesystem,
            originalParams,
            ImmutableSet.of(TargetCpuType.ARMV7),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            ImmutableList.of(),
            apkModuleGraph);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            target, ImmutableSet.of(), ImmutableSet.of(), apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(ImmutableSet.of(cxxLibrary)), ruleResolver);

    AndroidPackageableCollection packageableCollection = collector.build();
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibrariesOptional =
        enhancer.enhance(packageableCollection).getCopyNativeLibraries();
    CopyNativeLibraries copyNativeLibraries =
        copyNativeLibrariesOptional.get().get(apkModuleGraph.getRootAPKModule());

    assertThat(
        copyNativeLibraries.getStrippedObjectDescriptions(),
        Matchers.containsInAnyOrder(
            Matchers.allOf(
                Matchers.hasProperty("targetCpuType", Matchers.equalTo(TargetCpuType.ARMV7)),
                Matchers.hasProperty("strippedObjectName", Matchers.equalTo("somelib.so"))),
            Matchers.allOf(
                Matchers.hasProperty("targetCpuType", Matchers.equalTo(TargetCpuType.ARMV7)),
                Matchers.hasProperty(
                    "strippedObjectName", Matchers.equalTo("libgnustl_shared.so")))));
    assertThat(copyNativeLibraries.getNativeLibDirectories(), Matchers.empty());
    ImmutableCollection<BuildRule> stripRules =
        ruleFinder.filterBuildRuleInputs(
            copyNativeLibraries
                .getStrippedObjectDescriptions()
                .stream()
                .map(StrippedObjectDescription::getSourcePath)
                .collect(ImmutableSet.toImmutableSet()));
    assertThat(
        stripRules,
        Matchers.contains(
            Matchers.instanceOf(StripLinkable.class), Matchers.instanceOf(StripLinkable.class)));
  }

  @Test(expected = HumanReadableException.class)
  public void testEmptyNativePlatformsWithNativeLinkables() throws Exception {
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();

    CxxLibrary cxxLibrary =
        (CxxLibrary)
            new CxxLibraryBuilder(
                    BuildTargetFactory.newInstance("//:cxxlib"), CxxPlatformUtils.DEFAULT_CONFIG)
                .build(ruleResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(cxxLibrary));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(ImmutableMap.of()))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            ruleResolver,
            target,
            projectFilesystem,
            originalParams,
            ImmutableSet.of(),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            ImmutableList.of(),
            apkModuleGraph);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            target, ImmutableSet.of(), ImmutableSet.of(), apkModuleGraph);

    collector.addNativeLinkable(cxxLibrary);

    enhancer.enhance(collector.build());
  }

  @Test
  public void testEmptyNativePlatformsWithNativeLinkableAssets() throws Exception {
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();

    CxxLibrary cxxLibrary =
        (CxxLibrary)
            new CxxLibraryBuilder(
                    BuildTargetFactory.newInstance("//:cxxlib"), CxxPlatformUtils.DEFAULT_CONFIG)
                .build(ruleResolver);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(cxxLibrary));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(ImmutableMap.of()))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            ruleResolver,
            target,
            projectFilesystem,
            originalParams,
            ImmutableSet.of(),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            ImmutableList.of(),
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

  @Test
  public void testDuplicateCxxLibrary() {

    NdkCxxPlatform ndkCxxPlatform =
        NdkCxxPlatform.builder()
            .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
            .setCxxRuntime(NdkCxxRuntime.GNUSTL)
            .setCxxSharedRuntimePath(Paths.get("runtime"))
            .setObjdump(new CommandTool.Builder().addArg("objdump").build())
            .build();

    ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms =
        ImmutableMap.<TargetCpuType, NdkCxxPlatform>builder()
            .put(TargetCpuType.ARMV7, ndkCxxPlatform)
            .put(TargetCpuType.X86, ndkCxxPlatform)
            .build();

    CxxLibraryBuilder cxxLibraryBuilder1 =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxxlib1"))
            .setSoname("somelib.so")
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test/bar.cpp"))));
    TargetNode<CxxLibraryDescriptionArg, ?> cxxLibraryDescription1 = cxxLibraryBuilder1.build();
    CxxLibraryBuilder cxxLibraryBuilder2 =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxxlib2"))
            .setSoname("somelib.so")
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("test/bar2.cpp"))));
    TargetNode<CxxLibraryDescriptionArg, ?> cxxLibraryDescription2 = cxxLibraryBuilder2.build();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            ImmutableList.of(cxxLibraryDescription1, cxxLibraryDescription2));
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    CxxLibrary cxxLibrary1 =
        (CxxLibrary)
            cxxLibraryBuilder1.build(ruleResolver, new FakeProjectFilesystem(), targetGraph);
    ruleResolver.addToIndex(cxxLibrary1);
    CxxLibrary cxxLibrary2 =
        (CxxLibrary)
            cxxLibraryBuilder2.build(ruleResolver, new FakeProjectFilesystem(), targetGraph);
    ruleResolver.addToIndex(cxxLibrary2);

    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create()
            .withDeclaredDeps(ImmutableSortedSet.of(cxxLibrary1, cxxLibrary2));

    APKModuleGraph apkModuleGraph = new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty());

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    AndroidNativeLibsPackageableGraphEnhancer enhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(nativePlatforms))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            ruleResolver,
            target,
            projectFilesystem,
            originalParams,
            ImmutableSet.of(TargetCpuType.ARMV7),
            CxxPlatformUtils.DEFAULT_CONFIG,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            ImmutableList.of(),
            apkModuleGraph);

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            target, ImmutableSet.of(), ImmutableSet.of(), apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(ImmutableSet.of(cxxLibrary1, cxxLibrary2)),
        ruleResolver);

    AndroidPackageableCollection packageableCollection = collector.build();
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        Matchers.containsString(
            "Two libraries in the dependencies have the same output filename: somelib.so:\n"
                + "Those libraries are  //:cxxlib2 and //:cxxlib1"));
    enhancer.enhance(packageableCollection).getCopyNativeLibraries();
  }
}
