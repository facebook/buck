/*
 * Copyright 2013-present Facebook, Inc.
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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_CONFIG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.cell.TestCellPathResolver;
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
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaClassHashes;
import com.facebook.buck.jvm.java.FakeJavac;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavacFactoryHelper;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalInt;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidBinaryGraphEnhancerTest {

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
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

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
        new AndroidBinaryGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(ImmutableMap.of()))
                .build(),
            TestCellPathResolver.get(filesystem),
            apkTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            originalParams,
            graphBuilder,
            AaptMode.AAPT1,
            ResourcesFilter.ResourceCompressionMode.DISABLED,
            FilterResourcesSteps.ResourceFilter.EMPTY_FILTER,
            /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
            Optional.empty(),
            Optional.empty(),
            /* locales */ ImmutableSet.of(),
            /* localizedStringFileName */ null,
            Optional.of(PathSourcePath.of(filesystem, Paths.get("AndroidManifest.xml"))),
            Optional.empty(),
            Optional.empty(),
            PackageType.DEBUG,
            /* cpuFilters */ ImmutableSet.of(),
            /* shouldBuildStringSourceMap */ false,
            /* shouldPreDex */ true,
            DexSplitMode.NO_SPLIT,
            buildRulesToExcludeFromDex,
            /* resourcesToExclude */ ImmutableSet.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* noAutoVersionResources */ false,
            /* noVersionTransitionsResources */ false,
            /* noAutoAddOverlayResources */ false,
            DEFAULT_JAVA_CONFIG,
            JavacFactoryHelper.createJavacFactory(DEFAULT_JAVA_CONFIG),
            ANDROID_JAVAC_OPTIONS,
            EnumSet.noneOf(ExopackageMode.class),
            /* buildConfigValues */ BuildConfigFields.of(),
            /* buildConfigValuesFile */ Optional.empty(),
            /* xzCompressionLevel */ OptionalInt.empty(),
            /* trimResourceIds */ false,
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
            new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty()),
            new DxConfig(FakeBuckConfig.builder().build()),
            DxStep.DX,
            Optional.empty(),
            defaultNonPredexedArgs(),
            ImmutableSortedSet.of(),
            false);

    BuildTarget aaptPackageResourcesTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#aapt_package");
    AaptPackageResources aaptPackageResources =
        new AaptPackageResources(
            aaptPackageResourcesTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            ruleFinder,
            graphBuilder,
            /* manifest */ FakeSourcePath.of("java/src/com/facebook/base/AndroidManifest.xml"),
            ImmutableList.of(),
            new IdentityResourcesProvider(ImmutableList.of()),
            ImmutableList.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* manifestEntries */ ManifestEntries.empty());
    graphBuilder.addToIndex(aaptPackageResources);

    AndroidPackageableCollection collection =
        new AndroidPackageableCollector(
                /* collectionRoot */ apkTarget,
                ImmutableSet.of(javaDep2BuildTarget),
                /* resourcesToExclude */ ImmutableSet.of(),
                new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty()))
            .addClasspathEntry(((HasJavaClassHashes) javaDep1), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaDep2), FakeSourcePath.of("ignored"))
            .addClasspathEntry(((HasJavaClassHashes) javaLib), FakeSourcePath.of("ignored"))
            .build();

    ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexedLibraries =
        graphEnhancer.createPreDexRulesForLibraries(
            /* additionalJavaLibrariesToDex */
            ImmutableList.of(), collection);

    BuildRule preDexMergeRule = graphEnhancer.createPreDexMergeRule(preDexedLibraries);
    BuildTarget dexMergeTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#dex,dex_merge");
    BuildRule dexMergeRule = graphBuilder.getRule(dexMergeTarget);

    assertEquals(dexMergeRule, preDexMergeRule);

    BuildTarget javaDep1DexBuildTarget =
        javaDep1BuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR);
    BuildTarget javaDep2DexBuildTarget =
        javaDep2BuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR);
    BuildTarget javaLibDexBuildTarget =
        javaLibBuildTarget.withAppendedFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR);
    assertThat(
        "There should be a #dex rule for dep1 and lib, but not dep2 because it is in the no_dx "
            + "list.  And we should depend on uber_r_dot_java",
        Iterables.transform(dexMergeRule.getBuildDeps(), BuildRule::getBuildTarget),
        Matchers.allOf(
            Matchers.not(Matchers.hasItem(javaDep1BuildTarget)),
            Matchers.hasItem(javaDep1DexBuildTarget),
            Matchers.not(Matchers.hasItem(javaDep2BuildTarget)),
            Matchers.not(Matchers.hasItem(javaDep2DexBuildTarget)),
            Matchers.hasItem(javaLibDexBuildTarget)));
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
        new AndroidBinaryGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(ImmutableMap.of()))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            apkTarget,
            projectFilesystem,
            TestAndroidPlatformTargetFactory.create(),
            originalParams,
            graphBuilder,
            AaptMode.AAPT1,
            ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
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
            /* shouldPreDex */ false,
            DexSplitMode.NO_SPLIT,
            /* buildRulesToExcludeFromDex */ ImmutableSet.of(),
            /* resourcesToExclude */ ImmutableSet.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* noAutoVersionResources */ false,
            /* noVersionTransitionsResources */ false,
            /* noAutoAddOverlayResources */ false,
            DEFAULT_JAVA_CONFIG,
            JavacFactoryHelper.createJavacFactory(DEFAULT_JAVA_CONFIG),
            ANDROID_JAVAC_OPTIONS,
            EnumSet.of(ExopackageMode.SECONDARY_DEX),
            /* buildConfigValues */ BuildConfigFields.of(),
            /* buildConfigValuesFiles */ Optional.empty(),
            /* xzCompressionLevel */ OptionalInt.empty(),
            /* trimResourceIds */ false,
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
            new APKModuleGraph(TargetGraph.EMPTY, apkTarget, Optional.empty()),
            new DxConfig(FakeBuckConfig.builder().build()),
            DxStep.DX,
            Optional.empty(),
            defaultNonPredexedArgs(),
            ImmutableSortedSet.of(),
            false);
    AndroidGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    // Verify that android_build_config() was processed correctly.
    Flavor flavor = InternalFlavor.of("buildconfig_com_example_buck");
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuildTarget enhancedBuildConfigTarget = apkTarget.withAppendedFlavors(flavor);
    assertEquals(
        "The only classpath entry to dex should be the one from the AndroidBuildConfigJavaLibrary"
            + " created via graph enhancement.",
        ImmutableSet.of(
            BuildTargetPaths.getGenPath(
                    projectFilesystem, enhancedBuildConfigTarget, "lib__%s__output")
                .resolve(enhancedBuildConfigTarget.getShortNameAndFlavorPostfix() + ".jar")),
        result
            .getClasspathEntriesToDex()
            .stream()
            .map(pathResolver::getRelativePath)
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

    assertFalse(result.getPreDexMerge().isPresent());
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
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(resource));
    AndroidBinaryGraphEnhancer graphEnhancer =
        new AndroidBinaryGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(ImmutableMap.of()))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            target,
            projectFilesystem,
            TestAndroidPlatformTargetFactory.create(),
            originalParams,
            graphBuilder,
            AaptMode.AAPT1,
            ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
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
            /* shouldPreDex */ false,
            DexSplitMode.NO_SPLIT,
            /* buildRulesToExcludeFromDex */ ImmutableSet.of(),
            /* resourcesToExclude */ ImmutableSet.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* noAutoVersionResources */ false,
            /* noVersionTransitionsResources */ false,
            /* noAutoAddOverlayResources */ false,
            DEFAULT_JAVA_CONFIG,
            JavacFactoryHelper.createJavacFactory(DEFAULT_JAVA_CONFIG),
            ANDROID_JAVAC_OPTIONS,
            EnumSet.of(ExopackageMode.SECONDARY_DEX),
            /* buildConfigValues */ BuildConfigFields.of(),
            /* buildConfigValuesFiles */ Optional.empty(),
            /* xzCompressionLevel */ OptionalInt.empty(),
            /* trimResourceIds */ false,
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
            new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty()),
            new DxConfig(FakeBuckConfig.builder().build()),
            DxStep.DX,
            Optional.empty(),
            defaultNonPredexedArgs(),
            ImmutableSortedSet.of(),
            false);
    graphEnhancer.createAdditionalBuildables();

    BuildRule aaptPackageResourcesRule = findRuleOfType(graphBuilder, AaptPackageResources.class);
    MoreAsserts.assertDepends(
        "AaptPackageResources must depend on resource rules", aaptPackageResourcesRule, resource);
  }

  @Test
  public void testPackageStringsDependsOnResourcesFilter() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    // set it up.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams = TestBuildRuleParams.create();
    AndroidBinaryGraphEnhancer graphEnhancer =
        new AndroidBinaryGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(ImmutableMap.of()))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            target,
            projectFilesystem,
            TestAndroidPlatformTargetFactory.create(),
            originalParams,
            graphBuilder,
            AaptMode.AAPT1,
            ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
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
            /* shouldPreDex */ false,
            DexSplitMode.NO_SPLIT,
            /* buildRulesToExcludeFromDex */ ImmutableSet.of(),
            /* resourcesToExclude */ ImmutableSet.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* noAutoVersionResources */ false,
            /* noVersionTransitionsResources */ false,
            /* noAutoAddOverlayResources */ false,
            DEFAULT_JAVA_CONFIG,
            JavacFactoryHelper.createJavacFactory(DEFAULT_JAVA_CONFIG),
            ANDROID_JAVAC_OPTIONS,
            EnumSet.of(ExopackageMode.SECONDARY_DEX),
            /* buildConfigValues */ BuildConfigFields.of(),
            /* buildConfigValuesFiles */ Optional.empty(),
            /* xzCompressionLevel */ OptionalInt.empty(),
            /* trimResourceIds */ false,
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
            new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty()),
            new DxConfig(FakeBuckConfig.builder().build()),
            DxStep.DX,
            Optional.empty(),
            defaultNonPredexedArgs(),
            ImmutableSortedSet.of(),
            false);
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
  public void testResourceRulesDependOnRulesBehindResourceSourcePaths() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

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
                ruleFinder,
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
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(resource));
    AndroidBinaryGraphEnhancer graphEnhancer =
        new AndroidBinaryGraphEnhancer(
            new ToolchainProviderBuilder()
                .withToolchain(
                    NdkCxxPlatformsProvider.DEFAULT_NAME,
                    NdkCxxPlatformsProvider.of(ImmutableMap.of()))
                .build(),
            TestCellPathResolver.get(projectFilesystem),
            target,
            projectFilesystem,
            TestAndroidPlatformTargetFactory.create(),
            originalParams,
            graphBuilder,
            AaptMode.AAPT1,
            ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
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
            /* shouldPreDex */ false,
            DexSplitMode.NO_SPLIT,
            /* buildRulesToExcludeFromDex */ ImmutableSet.of(),
            /* resourcesToExclude */ ImmutableSet.of(),
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* noAutoVersionResources */ false,
            /* noVersionTransitionsResources */ false,
            /* noAutoAddOverlayResources */ false,
            DEFAULT_JAVA_CONFIG,
            JavacFactoryHelper.createJavacFactory(DEFAULT_JAVA_CONFIG),
            ANDROID_JAVAC_OPTIONS,
            EnumSet.of(ExopackageMode.SECONDARY_DEX),
            /* buildConfigValues */ BuildConfigFields.of(),
            /* buildConfigValuesFiles */ Optional.empty(),
            /* xzCompressionLevel */ OptionalInt.empty(),
            /* trimResourceIds */ false,
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
            new APKModuleGraph(TargetGraph.EMPTY, target, Optional.empty()),
            new DxConfig(FakeBuckConfig.builder().build()),
            DxStep.DX,
            Optional.empty(),
            defaultNonPredexedArgs(),
            ImmutableSortedSet.of(),
            false);
    graphEnhancer.createAdditionalBuildables();

    ResourcesFilter resourcesFilter = findRuleOfType(graphBuilder, ResourcesFilter.class);
    MoreAsserts.assertDepends(
        "ResourcesFilter must depend on rules behind resources source paths",
        resourcesFilter,
        resourcesDep);
  }

  private NonPredexedDexBuildableArgs defaultNonPredexedArgs() {
    return NonPredexedDexBuildableArgs.builder()
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
