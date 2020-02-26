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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA8_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_CONFIG;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_OPTIONS;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.dalvik.ZipSplitter;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaClassHashes;
import com.facebook.buck.jvm.java.FakeJavac;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavacFactoryHelper;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import org.immutables.value.Value;
import org.junit.Test;

public class AndroidBinaryGraphEnhancerTest {

  /**
   * This test verifies that AndroidBinaryGraphEnhancer correctly populates D8 desugar dependencies
   * for java libraries.
   */
  @Test
  public void testD8PreDexingWithInterfaceMethods() {

    JavaBuckConfig javaBuckConfig =
        getJavaBuckConfigWithInterfaceMethodsDexing(/* desugarInterfaceMethods */ true);

    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1, dep1: depends :dep2.
    BuildTarget javaDep1BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep1");

    BuildTarget javaDep2BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep2");
    TargetNode<?> javaDep2Node =
        JavaLibraryBuilder.createBuilder(javaDep2BuildTarget, javaBuckConfig)
            .addSrc(Paths.get("java/com/example/Dep2.java"))
            .build();

    TargetNode<?> javaDep1Node =
        JavaLibraryBuilder.createBuilder(javaDep1BuildTarget, javaBuckConfig)
            .addSrc(Paths.get("java/com/example/Dep1.java"))
            .addDep(javaDep2Node.getBuildTarget())
            .build();

    BuildTarget javaLibBuildTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    TargetNode<?> javaLibNode =
        JavaLibraryBuilder.createBuilder(javaLibBuildTarget, javaBuckConfig)
            .addSrc(Paths.get("java/com/example/Lib.java"))
            .addDep(javaDep1Node.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(javaDep1Node, javaDep2Node, javaLibNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(targetGraph, createToolchainProviderForAndroidWithJava8());

    BuildRule javaDep1 = graphBuilder.requireRule(javaDep1BuildTarget);
    BuildRule javaDep2 = graphBuilder.requireRule(javaDep2BuildTarget);
    BuildRule javaLib = graphBuilder.requireRule(javaLibBuildTarget);

    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams =
        new BuildRuleParams(
            Suppliers.ofInstance(originalDeps), ImmutableSortedSet::of, ImmutableSortedSet.of());
    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(apkTarget)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .setToolchainProvider(createToolchainProviderForAndroidWithJava8())
                .setResourceCompressionMode(ResourcesFilter.ResourceCompressionMode.DISABLED)
                .setShouldPredex(true)
                .setExopackageMode(EnumSet.noneOf(ExopackageMode.class))
                .setDexTool(DxStep.D8)
                .build());

    BuildTarget aaptPackageResourcesTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#aapt_package");
    AaptPackageResources aaptPackageResources =
        new AaptPackageResources(
            aaptPackageResourcesTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            graphBuilder,
            /* manifest */ FakeSourcePath.of("java/src/com/facebook/base/AndroidManifest.xml"),
            ImmutableList.of(),
            new IdentityResourcesProvider(ImmutableList.of()),
            ImmutableList.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* manifestEntries */ ManifestEntries.empty(),
            ImmutableList.of());
    graphBuilder.addToIndex(aaptPackageResources);

    AndroidPackageableCollection collection =
        new AndroidPackageableCollector(
                /* collectionRoot */ apkTarget,
                /* buildTargetsToExcludeFromDex */ ImmutableSet.of(),
                new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty()))
            .addClasspathEntry(((HasJavaClassHashes) javaDep1), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaDep2), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaLib), FakeSourcePath.of("ignored"))
            .build();

    graphEnhancer.createPreDexMergeSingleDexRule(
        graphEnhancer.createPreDexRulesForLibraries(
            /* additionalJavaLibrariesToDex */
            ImmutableList.of(), collection));

    BuildRule javaDep1Abi =
        graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:dep1#class-abi"));

    BuildRule javaDep2Abi =
        graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:dep2#class-abi"));

    // dep2 should have no desugar dependencies
    DexProducedFromJavaLibrary javaDep2DexRule =
        (DexProducedFromJavaLibrary)
            graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:dep2#d8"));
    assertNotNull(javaDep2DexRule);
    assertThat(javaDep2DexRule.getDesugarDeps(), empty());
    assertThat(
        javaDep2DexRule.getBuildDeps(),
        allOf(not(hasItem(javaDep2Abi)), not(hasItem(javaDep1Abi))));

    // dep1 should have only dep1 abi dependency
    DexProducedFromJavaLibrary javaDep1DexRule =
        (DexProducedFromJavaLibrary)
            graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:dep1#d8"));
    assertNotNull(javaDep1DexRule);
    assertThat(javaDep1DexRule.getDesugarDeps(), hasSize(1));
    assertThat(javaDep1DexRule.getDesugarDeps(), hasItem(javaDep2Abi.getSourcePathToOutput()));
    assertThat(
        javaDep1DexRule.getBuildDeps(), allOf(hasItem(javaDep2Abi), not(hasItem(javaDep1Abi))));

    // lib should have both dep1 and dep2 abi dependencies
    DexProducedFromJavaLibrary javaLibDexRule =
        (DexProducedFromJavaLibrary)
            graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:lib#d8"));
    assertNotNull(javaLibDexRule);
    assertThat(javaLibDexRule.getDesugarDeps(), hasSize(2));
    assertThat(
        javaLibDexRule.getDesugarDeps(),
        hasItems(javaDep1Abi.getSourcePathToOutput(), javaDep2Abi.getSourcePathToOutput()));
    assertThat(javaLibDexRule.getBuildDeps(), hasItems(javaDep1Abi, javaDep2Abi));
  }

  /**
   * This test verifies that AndroidBinaryGraphEnhancer skips D8 desugar dependencies for java
   * libraries when Interface Methods desugar is disabled.
   */
  @Test
  public void testD8PreDexingWithoutInterfaceMethods() {

    JavaBuckConfig javaBuckConfig =
        getJavaBuckConfigWithInterfaceMethodsDexing(/* desugarInterfaceMethods */ false);

    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1, dep1: depends :dep2.
    BuildTarget javaDep1BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep1");

    BuildTarget javaDep2BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep2");
    TargetNode<?> javaDep2Node =
        JavaLibraryBuilder.createBuilder(javaDep2BuildTarget, javaBuckConfig)
            .addSrc(Paths.get("java/com/example/Dep2.java"))
            .build();

    TargetNode<?> javaDep1Node =
        JavaLibraryBuilder.createBuilder(javaDep1BuildTarget, javaBuckConfig)
            .addSrc(Paths.get("java/com/example/Dep1.java"))
            .addDep(javaDep2Node.getBuildTarget())
            .build();

    BuildTarget javaLibBuildTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    TargetNode<?> javaLibNode =
        JavaLibraryBuilder.createBuilder(javaLibBuildTarget, javaBuckConfig)
            .addSrc(Paths.get("java/com/example/Lib.java"))
            .addDep(javaDep1Node.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(javaDep1Node, javaDep2Node, javaLibNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(targetGraph, createToolchainProviderForAndroidWithJava8());

    BuildRule javaDep1 = graphBuilder.requireRule(javaDep1BuildTarget);
    BuildRule javaDep2 = graphBuilder.requireRule(javaDep2BuildTarget);
    BuildRule javaLib = graphBuilder.requireRule(javaLibBuildTarget);

    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams =
        new BuildRuleParams(
            Suppliers.ofInstance(originalDeps), ImmutableSortedSet::of, ImmutableSortedSet.of());
    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(apkTarget)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .setToolchainProvider(createToolchainProviderForAndroidWithJava8())
                .setResourceCompressionMode(ResourcesFilter.ResourceCompressionMode.DISABLED)
                .setShouldPredex(true)
                .setExopackageMode(EnumSet.noneOf(ExopackageMode.class))
                .setDexTool(DxStep.D8)
                .build());

    BuildTarget aaptPackageResourcesTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#aapt_package");
    AaptPackageResources aaptPackageResources =
        new AaptPackageResources(
            aaptPackageResourcesTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            graphBuilder,
            /* manifest */ FakeSourcePath.of("java/src/com/facebook/base/AndroidManifest.xml"),
            ImmutableList.of(),
            new IdentityResourcesProvider(ImmutableList.of()),
            ImmutableList.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* manifestEntries */ ManifestEntries.empty(),
            ImmutableList.of());
    graphBuilder.addToIndex(aaptPackageResources);

    AndroidPackageableCollection collection =
        new AndroidPackageableCollector(
                /* collectionRoot */ apkTarget,
                /* buildTargetsToExcludeFromDex */ ImmutableSet.of(),
                new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty()))
            .addClasspathEntry(((HasJavaClassHashes) javaDep1), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaDep2), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaLib), FakeSourcePath.of("ignored"))
            .build();

    graphEnhancer.createPreDexMergeSingleDexRule(
        graphEnhancer.createPreDexRulesForLibraries(
            /* additionalJavaLibrariesToDex */
            ImmutableList.of(), collection));

    BuildRule javaDep1Abi =
        graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:dep1#class-abi"));

    BuildRule javaDep2Abi =
        graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:dep2#class-abi"));

    // dep2 should have no desugar dependencies
    DexProducedFromJavaLibrary javaDep2DexRule =
        (DexProducedFromJavaLibrary)
            graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:dep2#d8"));
    assertNotNull(javaDep2DexRule);
    assertThat(javaDep2DexRule.getDesugarDeps(), empty());
    assertThat(
        javaDep2DexRule.getBuildDeps(),
        allOf(not(hasItem(javaDep2Abi)), not(hasItem(javaDep1Abi))));

    // dep1 should have only dep1 abi dependency
    DexProducedFromJavaLibrary javaDep1DexRule =
        (DexProducedFromJavaLibrary)
            graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:dep1#d8"));
    assertNotNull(javaDep1DexRule);
    assertThat(javaDep1DexRule.getDesugarDeps(), empty());
    assertThat(
        javaDep1DexRule.getBuildDeps(),
        allOf(not(hasItem(javaDep2Abi)), not(hasItem(javaDep1Abi))));

    // lib should have both dep1 and dep2 abi dependencies
    DexProducedFromJavaLibrary javaLibDexRule =
        (DexProducedFromJavaLibrary)
            graphBuilder.getRule(BuildTargetFactory.newInstance("//java/com/example:lib#d8"));
    assertNotNull(javaLibDexRule);
    assertThat(javaLibDexRule.getDesugarDeps(), empty());
    assertThat(
        javaLibDexRule.getBuildDeps(), allOf(not(hasItem(javaDep2Abi)), not(hasItem(javaDep1Abi))));
  }

  public static ToolchainProvider createToolchainProviderForAndroidWithJava8() {
    return new ToolchainProviderBuilder()
        .withToolchain(
            NdkCxxPlatformsProvider.DEFAULT_NAME, NdkCxxPlatformsProvider.of(ImmutableMap.of()))
        .withToolchain(
            JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.of(DEFAULT_JAVA8_JAVAC_OPTIONS))
        .withToolchain(
            AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
        .withToolchain(
            JavaOptionsProvider.DEFAULT_NAME,
            JavaOptionsProvider.of(DEFAULT_JAVA_OPTIONS, DEFAULT_JAVA_OPTIONS))
        .build();
  }

  protected JavaBuckConfig getJavaBuckConfigWithInterfaceMethodsDexing(
      boolean desugarInterfaceMethods) {
    return JavaBuckConfig.of(
        FakeBuckConfig.builder()
            .setSections(
                "[" + JavaBuckConfig.SECTION + "]",
                "desugar_interface_methods = "
                    + Boolean.toString(desugarInterfaceMethods).toLowerCase())
            .build());
  }

  @Test
  public void testCreateDepsForPreDexing() {
    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1 and :dep2.
    BuildTarget javaDep1BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep1");
    TargetNode<?> javaDep1Node =
        JavaLibraryBuilder.createBuilder(javaDep1BuildTarget)
            .addSrc(Paths.get("java/com/example/Dep1.java"))
            .build();

    BuildTarget javaDep2BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep2");
    TargetNode<?> javaDep2Node =
        JavaLibraryBuilder.createBuilder(javaDep2BuildTarget)
            .addSrc(Paths.get("java/com/example/Dep2.java"))
            .build();

    BuildTarget javaLibBuildTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    TargetNode<?> javaLibNode =
        JavaLibraryBuilder.createBuilder(javaLibBuildTarget)
            .addSrc(Paths.get("java/com/example/Lib.java"))
            .addDep(javaDep1Node.getBuildTarget())
            .addDep(javaDep2Node.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(javaDep1Node, javaDep2Node, javaLibNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule javaDep1 = graphBuilder.requireRule(javaDep1BuildTarget);
    BuildRule javaDep2 = graphBuilder.requireRule(javaDep2BuildTarget);
    BuildRule javaLib = graphBuilder.requireRule(javaLibBuildTarget);

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    ImmutableSet<BuildTarget> buildRulesToExcludeFromDex = ImmutableSet.of(javaDep2BuildTarget);
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams =
        new BuildRuleParams(
            Suppliers.ofInstance(originalDeps), ImmutableSortedSet::of, ImmutableSortedSet.of());
    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(apkTarget)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .setResourceCompressionMode(ResourcesFilter.ResourceCompressionMode.DISABLED)
                .setShouldPredex(true)
                .setBuildRulesToExcludeFromDex(buildRulesToExcludeFromDex)
                .setExopackageMode(EnumSet.noneOf(ExopackageMode.class))
                .build());

    BuildTarget aaptPackageResourcesTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#aapt_package");
    AaptPackageResources aaptPackageResources =
        new AaptPackageResources(
            aaptPackageResourcesTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            graphBuilder,
            /* manifest */ FakeSourcePath.of("java/src/com/facebook/base/AndroidManifest.xml"),
            ImmutableList.of(),
            new IdentityResourcesProvider(ImmutableList.of()),
            ImmutableList.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* manifestEntries */ ManifestEntries.empty(),
            ImmutableList.of());
    graphBuilder.addToIndex(aaptPackageResources);

    AndroidPackageableCollection collection =
        new AndroidPackageableCollector(
                /* collectionRoot */ apkTarget,
                ImmutableSet.of(javaDep2BuildTarget),
                new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty()))
            .addClasspathEntry(((HasJavaClassHashes) javaDep1), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaDep2), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaLib), FakeSourcePath.of("ignored"))
            .build();

    ImmutableList<DexProducedFromJavaLibrary> preDexedLibraries =
        graphEnhancer.createPreDexRulesForLibraries(
            /* additionalJavaLibrariesToDex */
            ImmutableList.of(), collection);

    HasDexFiles preDexMergeRule = graphEnhancer.createPreDexMergeSingleDexRule(preDexedLibraries);
    BuildTarget dexMergeTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#d8,single_dex_merge");
    BuildRule dexMergeRule = graphBuilder.getRule(dexMergeTarget);

    assertEquals(dexMergeRule, preDexMergeRule);

    BuildTarget javaDep1DexBuildTarget =
        javaDep1BuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR);
    BuildTarget javaDep2DexBuildTarget =
        javaDep2BuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR);
    BuildTarget javaLibDexBuildTarget =
        javaLibBuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR);
    assertThat(
        "There should be a #d8 rule for dep1 and lib, but not dep2 because it is in the no_dx "
            + "list.  And we should depend on uber_r_dot_java",
        Iterables.transform(dexMergeRule.getBuildDeps(), BuildRule::getBuildTarget),
        allOf(
            not(hasItem(javaDep1BuildTarget)),
            hasItem(javaDep1DexBuildTarget),
            not(hasItem(javaDep2BuildTarget)),
            not(hasItem(javaDep2DexBuildTarget)),
            hasItem(javaLibDexBuildTarget)));
  }

  @Test
  public void testCreateDepsForPreDexingSplitDexSingleGroup() {
    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1 and :dep2.
    BuildTarget javaDep1BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep1");
    TargetNode<?> javaDep1Node =
        JavaLibraryBuilder.createBuilder(javaDep1BuildTarget)
            .addSrc(Paths.get("java/com/example/Dep1.java"))
            .build();

    BuildTarget javaDep2BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep2");
    TargetNode<?> javaDep2Node =
        JavaLibraryBuilder.createBuilder(javaDep2BuildTarget)
            .addSrc(Paths.get("java/com/example/Dep2.java"))
            .build();

    BuildTarget javaLibBuildTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    TargetNode<?> javaLibNode =
        JavaLibraryBuilder.createBuilder(javaLibBuildTarget)
            .addSrc(Paths.get("java/com/example/Lib.java"))
            .addDep(javaDep1Node.getBuildTarget())
            .addDep(javaDep2Node.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(javaDep1Node, javaDep2Node, javaLibNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule javaDep1 = graphBuilder.requireRule(javaDep1BuildTarget);
    BuildRule javaDep2 = graphBuilder.requireRule(javaDep2BuildTarget);
    BuildRule javaLib = graphBuilder.requireRule(javaLibBuildTarget);

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    ImmutableSet<BuildTarget> buildRulesToExcludeFromDex = ImmutableSet.of(javaDep2BuildTarget);
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams =
        new BuildRuleParams(
            Suppliers.ofInstance(originalDeps), ImmutableSortedSet::of, ImmutableSortedSet.of());

    DexSplitMode splitDexMode =
        new DexSplitMode(
            true,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
            DexStore.JAR,
            DexSplitMode.DEFAULT_LINEAR_ALLOC_HARD_LIMIT,
            ImmutableList.of(),
            Optional.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            false);

    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(apkTarget)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .setResourceCompressionMode(ResourcesFilter.ResourceCompressionMode.DISABLED)
                .setShouldPredex(true)
                .setDexSplitMode(splitDexMode)
                .setBuildRulesToExcludeFromDex(buildRulesToExcludeFromDex)
                .setExopackageMode(EnumSet.noneOf(ExopackageMode.class))
                .build());

    BuildTarget aaptPackageResourcesTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#aapt_package");
    AaptPackageResources aaptPackageResources =
        new AaptPackageResources(
            aaptPackageResourcesTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            graphBuilder,
            /* manifest */ FakeSourcePath.of("java/src/com/facebook/base/AndroidManifest.xml"),
            ImmutableList.of(),
            new IdentityResourcesProvider(ImmutableList.of()),
            ImmutableList.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* manifestEntries */ ManifestEntries.empty(),
            ImmutableList.of());
    graphBuilder.addToIndex(aaptPackageResources);

    AndroidPackageableCollection collection =
        new AndroidPackageableCollector(
                /* collectionRoot */ apkTarget,
                ImmutableSet.of(javaDep2BuildTarget),
                new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty()))
            .addClasspathEntry(((HasJavaClassHashes) javaDep1), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaDep2), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaLib), FakeSourcePath.of("ignored"))
            .build();

    ImmutableList<DexProducedFromJavaLibrary> preDexedLibraries =
        graphEnhancer.createPreDexRulesForLibraries(
            /* additionalJavaLibrariesToDex */
            ImmutableList.of(), collection);

    BuildTarget javaDep1DexBuildTarget =
        javaDep1BuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR);
    BuildTarget javaDep2DexBuildTarget =
        javaDep2BuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR);
    BuildTarget javaLibDexBuildTarget =
        javaLibBuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR);

    AndroidBinaryResourcesGraphEnhancer.AndroidBinaryResourcesGraphEnhancementResult
        resourcesEnhancementResult = graphEnhancer.getResourcesGraphEnhancer().enhance(collection);

    HasDexFiles splitDexMergeEnhancerRule =
        graphEnhancer.createPreDexMergeSplitDexRule(preDexedLibraries, resourcesEnhancementResult);

    BuildTarget splitDexMergeTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#d8,split_dex_merge");
    BuildRule splitDexMergeRule = graphBuilder.getRule(splitDexMergeTarget);

    assertEquals(splitDexMergeRule, splitDexMergeEnhancerRule);

    BuildTarget dexGroupBuildTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#secondary_dexes");
    BuildRule dexGroupRule = graphBuilder.getRule(dexGroupBuildTarget);
    assertThat(splitDexMergeRule.getBuildDeps(), hasItem(dexGroupRule));
    assertThat(
        "There should be a #dex rule for dep1 and lib, but not dep2 because it is in the no_dx "
            + "list.  And we should depend on uber_r_dot_java",
        Iterables.transform(dexGroupRule.getBuildDeps(), BuildRule::getBuildTarget),
        allOf(
            not(hasItem(javaDep1BuildTarget)),
            hasItem(javaDep1DexBuildTarget),
            not(hasItem(javaDep2BuildTarget)),
            not(hasItem(javaDep2DexBuildTarget)),
            hasItem(javaLibDexBuildTarget)));
  }

  @Test
  public void testCreateDepsForPreDexingSplitDexMultiGroup() {
    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1 and :dep2.
    BuildTarget javaDep1BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep1");
    TargetNode<?> javaDep1Node =
        JavaLibraryBuilder.createBuilder(javaDep1BuildTarget)
            .addSrc(Paths.get("java/com/example/Dep1.java"))
            .build();

    BuildTarget javaDep2BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep2");
    TargetNode<?> javaDep2Node =
        JavaLibraryBuilder.createBuilder(javaDep2BuildTarget)
            .addSrc(Paths.get("java/com/example/Dep2.java"))
            .build();

    BuildTarget javaLibBuildTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    TargetNode<?> javaLibNode =
        JavaLibraryBuilder.createBuilder(javaLibBuildTarget)
            .addSrc(Paths.get("java/com/example/Lib.java"))
            .addDep(javaDep1Node.getBuildTarget())
            .addDep(javaDep2Node.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(javaDep1Node, javaDep2Node, javaLibNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule javaDep1 = graphBuilder.requireRule(javaDep1BuildTarget);
    BuildRule javaDep2 = graphBuilder.requireRule(javaDep2BuildTarget);
    BuildRule javaLib = graphBuilder.requireRule(javaLibBuildTarget);

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    ImmutableSet<BuildTarget> buildRulesToExcludeFromDex = ImmutableSet.of(javaDep2BuildTarget);
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams =
        new BuildRuleParams(
            Suppliers.ofInstance(originalDeps), ImmutableSortedSet::of, ImmutableSortedSet.of());

    DexSplitMode splitDexMode =
        new DexSplitMode(
            true,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
            DexStore.JAR,
            DexSplitMode.DEFAULT_LINEAR_ALLOC_HARD_LIMIT,
            1,
            ImmutableList.of(),
            Optional.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            false);

    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(apkTarget)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .setResourceCompressionMode(ResourcesFilter.ResourceCompressionMode.DISABLED)
                .setShouldPredex(true)
                .setDexSplitMode(splitDexMode)
                .setBuildRulesToExcludeFromDex(buildRulesToExcludeFromDex)
                .setExopackageMode(EnumSet.noneOf(ExopackageMode.class))
                .setTrimResourceIds(true)
                .build());

    BuildTarget aaptPackageResourcesTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#aapt_package");
    AaptPackageResources aaptPackageResources =
        new AaptPackageResources(
            aaptPackageResourcesTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            graphBuilder,
            /* manifest */ FakeSourcePath.of("java/src/com/facebook/base/AndroidManifest.xml"),
            ImmutableList.of(),
            new IdentityResourcesProvider(ImmutableList.of()),
            ImmutableList.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* manifestEntries */ ManifestEntries.empty(),
            ImmutableList.of());
    graphBuilder.addToIndex(aaptPackageResources);

    AndroidPackageableCollection collection =
        new AndroidPackageableCollector(
                /* collectionRoot */ apkTarget,
                ImmutableSet.of(javaDep2BuildTarget),
                new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty()))
            .addClasspathEntry(((HasJavaClassHashes) javaDep1), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaDep2), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaLib), FakeSourcePath.of("ignored"))
            .build();

    ImmutableList<DexProducedFromJavaLibrary> preDexedLibraries =
        graphEnhancer.createPreDexRulesForLibraries(
            /* additionalJavaLibrariesToDex */
            ImmutableList.of(), collection);

    BuildTarget javaDep1DexBuildTarget =
        javaDep1BuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR);
    BuildTarget javaLibDexBuildTarget =
        javaLibBuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR);

    AndroidBinaryResourcesGraphEnhancer.AndroidBinaryResourcesGraphEnhancementResult
        resourcesEnhancementResult = graphEnhancer.getResourcesGraphEnhancer().enhance(collection);

    HasDexFiles splitDexMergeEnhancerRule =
        graphEnhancer.createPreDexMergeSplitDexRule(preDexedLibraries, resourcesEnhancementResult);

    BuildTarget splitDexMergeTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#d8,split_dex_merge");
    BuildRule splitDexMergeRule = graphBuilder.getRule(splitDexMergeTarget);

    assertEquals(splitDexMergeRule, splitDexMergeEnhancerRule);

    BuildTarget dexGroup1BuildTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#secondary_dexes_1");
    BuildTarget dexGroup2BuildTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#secondary_dexes_2");
    BuildRule dexGroup1Rule = graphBuilder.getRule(dexGroup1BuildTarget);
    BuildRule dexGroup2Rule = graphBuilder.getRule(dexGroup2BuildTarget);
    assertThat(splitDexMergeRule.getBuildDeps(), hasItem(dexGroup1Rule));

    assertThat(
        Iterables.transform(dexGroup1Rule.getBuildDeps(), BuildRule::getBuildTarget),
        contains(javaDep1DexBuildTarget));
    assertThat(
        Iterables.transform(dexGroup2Rule.getBuildDeps(), BuildRule::getBuildTarget),
        contains(javaLibDexBuildTarget));

    BuildTarget trimUberRDotJavaTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#trim_uber_r_dot_java");
    BuildRule trimUberRDotJavaRule = graphBuilder.getRule(trimUberRDotJavaTarget);
    BuildTarget rDotJavaDexTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#r_dot_java_dex");

    PreDexSplitDexGroup rDotJavaDexRule =
        (PreDexSplitDexGroup) graphBuilder.getRule(rDotJavaDexTarget);
    assertEquals(rDotJavaDexRule.getGroupIndex(), Optional.of(3));

    assertThat(
        Iterables.transform(trimUberRDotJavaRule.getBuildDeps(), BuildRule::getBuildTarget),
        contains(dexGroup1BuildTarget, dexGroup2BuildTarget));
    assertThat(
        Iterables.transform(splitDexMergeRule.getBuildDeps(), BuildRule::getBuildTarget),
        contains(rDotJavaDexTarget, dexGroup1BuildTarget, dexGroup2BuildTarget));
  }

  @Test
  public void testAllBuildablesExceptPreDexRule() {
    // Create an android_build_config() as a dependency of the android_binary().
    BuildTarget buildConfigBuildTarget = BuildTargetFactory.newInstance("//java/com/example:cfg");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams buildConfigParams = TestBuildRuleParams.create();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    AndroidBuildConfigJavaLibrary buildConfigJavaLibrary =
        AndroidBuildConfigDescription.createBuildRule(
            buildConfigBuildTarget,
            projectFilesystem,
            buildConfigParams,
            "com.example.buck",
            /* values */ BuildConfigFields.of(),
            /* valuesFile */ Optional.empty(),
            /* useConstantExpressions */ false,
            DEFAULT_JAVAC,
            ANDROID_JAVAC_OPTIONS,
            graphBuilder);

    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create()
            .withDeclaredDeps(ImmutableSortedSet.of(buildConfigJavaLibrary));

    // set it up.
    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(apkTarget)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .build());
    AndroidGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    // Verify that android_build_config() was processed correctly.
    Flavor flavor = InternalFlavor.of("buildconfig_com_example_buck");
    BuildTarget enhancedBuildConfigTarget = apkTarget.withAppendedFlavors(flavor);
    assertEquals(
        "The only classpath entry to dex should be the one from the AndroidBuildConfigJavaLibrary"
            + " created via graph enhancement.",
        ImmutableSet.of(
            BuildTargetPaths.getGenPath(
                    projectFilesystem, enhancedBuildConfigTarget, "lib__%s__output")
                .resolve(enhancedBuildConfigTarget.getShortNameAndFlavorPostfix() + ".jar")),
        result.getClasspathEntriesToDex().stream()
            .map(graphBuilder.getSourcePathResolver()::getRelativePath)
            .collect(ImmutableSet.toImmutableSet()));
    BuildRule enhancedBuildConfigRule = graphBuilder.getRule(enhancedBuildConfigTarget);
    assertTrue(enhancedBuildConfigRule instanceof AndroidBuildConfigJavaLibrary);
    AndroidBuildConfigJavaLibrary enhancedBuildConfigJavaLibrary =
        (AndroidBuildConfigJavaLibrary) enhancedBuildConfigRule;
    AndroidBuildConfig androidBuildConfig = enhancedBuildConfigJavaLibrary.getAndroidBuildConfig();
    assertEquals("com.example.buck", androidBuildConfig.getJavaPackage());
    assertTrue(androidBuildConfig.isUseConstantExpressions());
    assertEquals(
        "IS_EXOPACKAGE defaults to false, but should now be true. DEBUG should still be true.",
        BuildConfigFields.fromFields(
            ImmutableList.of(
                BuildConfigFields.Field.of("boolean", "DEBUG", "true"),
                BuildConfigFields.Field.of("boolean", "IS_EXOPACKAGE", "true"),
                BuildConfigFields.Field.of("int", "EXOPACKAGE_FLAGS", "1"))),
        androidBuildConfig.getBuildConfigFields());

    BuildRule resourcesFilterRule = findRuleOfType(graphBuilder, ResourcesFilter.class);

    BuildRule aaptPackageResourcesRule = findRuleOfType(graphBuilder, AaptPackageResources.class);
    MoreAsserts.assertDepends(
        "AaptPackageResources must depend on ResourcesFilter",
        aaptPackageResourcesRule,
        resourcesFilterRule);

    BuildRule packageStringAssetsRule = findRuleOfType(graphBuilder, PackageStringAssets.class);
    MoreAsserts.assertDepends(
        "PackageStringAssets must depend on ResourcesFilter",
        packageStringAssetsRule,
        aaptPackageResourcesRule);

    assertFalse(result.getPreDexMergeSplitDex().isPresent());
    assertTrue(result.getPackageStringAssets().isPresent());
  }

  @Test
  public void testResourceRulesBecomeDepsOfAaptPackageResources() {
    TargetNode<?> resourceNode =
        AndroidResourceBuilder.createBuilder(BuildTargetFactory.newInstance("//:resource"))
            .setRDotJavaPackage("package")
            .setRes(Paths.get("res"))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(resourceNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    AndroidResource resource =
        (AndroidResource) graphBuilder.requireRule(resourceNode.getBuildTarget());

    // set it up.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(resource));
    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(target)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .build());
    graphEnhancer.createAdditionalBuildables();

    BuildRule aaptPackageResourcesRule = findRuleOfType(graphBuilder, AaptPackageResources.class);
    MoreAsserts.assertDepends(
        "AaptPackageResources must depend on resource rules", aaptPackageResourcesRule, resource);
  }

  @Test
  public void testPackageStringsDependsOnResourcesFilter() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    // set it up.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams = TestBuildRuleParams.create();
    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(target)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .build());
    graphEnhancer.createAdditionalBuildables();

    ResourcesFilter resourcesFilter = findRuleOfType(graphBuilder, ResourcesFilter.class);
    PackageStringAssets packageStringAssetsRule =
        findRuleOfType(graphBuilder, PackageStringAssets.class);
    MoreAsserts.assertDepends(
        "PackageStringAssets must depend on AaptPackageResources",
        packageStringAssetsRule,
        resourcesFilter);
  }

  @Test
  public void testResourceRulesDependOnRulesBehindResourceSourcePaths() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    FakeBuildRule resourcesDep =
        graphBuilder.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//:resource_dep")));
    resourcesDep.setOutputFile("foo");

    BuildTarget resourceTarget = BuildTargetFactory.newInstance("//:resources");
    AndroidResource resource =
        graphBuilder.addToIndex(
            new AndroidResource(
                resourceTarget,
                new FakeProjectFilesystem(),
                TestBuildRuleParams.create()
                    .copyAppendingExtraDeps(ImmutableSortedSet.of(resourcesDep)),
                graphBuilder,
                ImmutableSortedSet.of(),
                resourcesDep.getSourcePathToOutput(),
                ImmutableSortedMap.of(),
                null,
                null,
                ImmutableSortedMap.of(),
                FakeSourcePath.of("manifest"),
                false));

    // set it up.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(resource));
    AndroidBinaryGraphEnhancer graphEnhancer =
        createGraphEnhancer(
            ImmutableTestGraphEnhancerArgs.builder()
                .setTarget(target)
                .setBuildRuleParams(originalParams)
                .setGraphBuilder(graphBuilder)
                .build());
    graphEnhancer.createAdditionalBuildables();

    ResourcesFilter resourcesFilter = findRuleOfType(graphBuilder, ResourcesFilter.class);
    MoreAsserts.assertDepends(
        "ResourcesFilter must depend on rules behind resources source paths",
        resourcesFilter,
        resourcesDep);
  }

  @BuckStyleValueWithBuilder
  abstract static class TestGraphEnhancerArgs {
    abstract BuildTarget getTarget();

    abstract BuildRuleParams getBuildRuleParams();

    abstract ActionGraphBuilder getGraphBuilder();

    @Value.Default
    ToolchainProvider getToolchainProvider() {
      return new ToolchainProviderBuilder()
          .withToolchain(
              NdkCxxPlatformsProvider.DEFAULT_NAME, NdkCxxPlatformsProvider.of(ImmutableMap.of()))
          .build();
    }

    @Value.Default
    ResourcesFilter.ResourceCompressionMode getResourceCompressionMode() {
      return ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS;
    }

    @Value.Default
    boolean getShouldPredex() {
      return false;
    }

    @Value.Default
    boolean getTrimResourceIds() {
      return false;
    }

    @Value.Default
    DexSplitMode getDexSplitMode() {
      return DexSplitMode.NO_SPLIT;
    }

    @Value.Default
    ImmutableSet<BuildTarget> getBuildRulesToExcludeFromDex() {
      return ImmutableSet.of();
    }

    @Value.Default
    EnumSet<ExopackageMode> getExopackageMode() {
      return EnumSet.of(ExopackageMode.SECONDARY_DEX);
    }

    @Value.Default
    String getDexTool() {
      return DxStep.D8;
    }
  }

  private AndroidBinaryGraphEnhancer createGraphEnhancer(TestGraphEnhancerArgs args) {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    return new AndroidBinaryGraphEnhancer(
        args.getToolchainProvider(),
        TestCellPathResolver.get(projectFilesystem),
        args.getTarget(),
        projectFilesystem,
        TestAndroidPlatformTargetFactory.create(),
        args.getBuildRuleParams(),
        args.getGraphBuilder(),
        AaptMode.AAPT1,
        ImmutableList.of(),
        args.getResourceCompressionMode(),
        FilterResourcesSteps.ResourceFilter.EMPTY_FILTER,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        Optional.empty(),
        Optional.empty(),
        /* locales */ ImmutableSet.of(),
        /* localizedStringFileName */ null,
        Optional.of(FakeSourcePath.of("AndroidManifest.xml")),
        Optional.empty(),
        Optional.empty(),
        PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ args.getShouldPredex(),
        args.getDexSplitMode(),
        /* buildRulesToExcludeFromDex */ args.getBuildRulesToExcludeFromDex(),
        /* resourcesToExclude */ ImmutableSet.of(),
        /* nativeLibsToExclude */ ImmutableSet.of(),
        /* nativeLinkablesToExclude */ ImmutableSet.of(),
        /* nativeLibAssetsToExclude */ ImmutableSet.of(),
        /* nativeLinkableAssetsToExclude */ ImmutableSet.of(),
        /* skipCrunchPngs */ false,
        /* includesVectorDrawables */ false,
        /* noAutoVersionResources */ false,
        /* noVersionTransitionsResources */ false,
        /* noAutoAddOverlayResources */ false,
        /* noResourceRemoval */ false,
        DEFAULT_JAVA_CONFIG,
        JavacFactoryHelper.createJavacFactory(DEFAULT_JAVA_CONFIG),
        ANDROID_JAVAC_OPTIONS,
        args.getExopackageMode(),
        /* buildConfigValues */ BuildConfigFields.of(),
        /* buildConfigValuesFiles */ Optional.empty(),
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        args.getTrimResourceIds(),
        /* keepResourcePattern */ Optional.empty(),
        false,
        /* nativeLibraryMergeMap */ Optional.empty(),
        /* nativeLibraryMergeGlue */ Optional.empty(),
        /* nativeLibraryMergeCodeGenerator */ Optional.empty(),
        /* nativeLibraryProguardConfigGenerator */ Optional.empty(),
        Optional.empty(),
        RelinkerMode.DISABLED,
        ImmutableList.of(),
        MoreExecutors.newDirectExecutorService(),
        /* manifestEntries */ ManifestEntries.empty(),
        CxxPlatformUtils.DEFAULT_CONFIG,
        new APKModuleGraph(TargetGraph.EMPTY, args.getTarget(), Optional.empty()),
        new DxConfig(FakeBuckConfig.builder().build()),
        args.getDexTool(),
        Optional.empty(),
        defaultNonPredexedArgs(),
        ImmutableSortedSet::of,
        /* useProtoFormat */ false,
        new NoopAndroidNativeTargetConfigurationMatcher(),
        /* failOnLegacyAapt2Errors */ false,
        false,
        ImmutableSet.of());
  }

  private NonPreDexedDexBuildable.NonPredexedDexBuildableArgs defaultNonPredexedArgs() {
    return ImmutableNonPredexedDexBuildableArgs.builder()
        .setSdkProguardConfig(ProGuardObfuscateStep.SdkProguardType.NONE)
        .setDexReorderDataDumpFile(FakeSourcePath.of(""))
        .setDexReorderToolFile(FakeSourcePath.of(""))
        .setDxExecutorService(MoreExecutors.newDirectExecutorService())
        .setDxMaxHeapSize("")
        .setJavaRuntimeLauncher(new FakeJavac())
        .setOptimizationPasses(0)
        .setProguardMaxHeapSize("")
        .setReorderClassesIntraDex(false)
        .setSkipProguard(true)
        .setShouldProguard(false)
        .build();
  }

  private <T extends BuildRule> T findRuleOfType(
      ActionGraphBuilder graphBuilder, Class<T> ruleClass) {
    for (BuildRule rule : graphBuilder.getBuildRules()) {
      if (ruleClass.isAssignableFrom(rule.getClass())) {
        return ruleClass.cast(rule);
      }
    }
    fail("Could not find build rule of type " + ruleClass.getCanonicalName());
    return null;
  }
}
