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

import static com.facebook.buck.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.AndroidBinary.ExopackageMode;
import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.java.HasJavaClassHashes;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Iterator;

public class AndroidBinaryGraphEnhancerTest {

  @Test
  public void testCreateDepsForPreDexing() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    RuleKeyBuilderFactory ruleKeyBuilderFactory = new FakeRuleKeyBuilderFactory();

    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1 and :dep2.
    BuildTarget javaDep1BuildTarget = BuildTarget.builder("//java/com/example", "dep1").build();
    BuildRule javaDep1 = JavaLibraryBuilder
        .createBuilder(javaDep1BuildTarget)
        .addSrc(Paths.get("java/com/example/Dep1.java"))
        .build(ruleResolver);

    BuildTarget javaDep2BuildTarget = BuildTarget.builder("//java/com/example", "dep2").build();
    BuildRule javaDep2 = JavaLibraryBuilder
        .createBuilder(javaDep2BuildTarget)
        .addSrc(Paths.get("java/com/example/Dep2.java"))
        .build(ruleResolver);

    BuildTarget javaLibBuildTarget = BuildTarget.builder("//java/com/example", "lib").build();
    BuildRule javaLib = JavaLibraryBuilder
        .createBuilder(javaLibBuildTarget)
        .addSrc(Paths.get("java/com/example/Lib.java"))
        .addDep(javaDep1.getBuildTarget())
        .addDep(javaDep2.getBuildTarget())
        .build(ruleResolver);

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    ImmutableSet<BuildTarget> buildRulesToExcludeFromDex = ImmutableSet.of(javaDep2BuildTarget);
    BuildTarget apkTarget = BuildTarget.builder("//java/com/example", "apk").build();
    BuildRuleParams originalParams = new BuildRuleParams(
        apkTarget,
        originalDeps,
        originalDeps,
        new FakeProjectFilesystem(),
        ruleKeyBuilderFactory,
        AndroidBinaryDescription.TYPE,
        TargetGraph.EMPTY);
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.DISABLED,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        createStrictMock(PathSourcePath.class),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.< TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ true,
        BuildTargets.getBinPath(apkTarget, "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        buildRulesToExcludeFromDex,
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        ANDROID_JAVAC_OPTIONS,
        EnumSet.noneOf(ExopackageMode.class),
        createStrictMock(Keystore.class),
        /* buildConfigValues */ BuildConfigFields.empty(),
        /* buildConfigValuesFile */ Optional.<SourcePath>absent(),
        /* nativePlatforms */ ImmutableMap.<TargetCpuType, NdkCxxPlatform>of());

    BuildTarget aaptPackageResourcesTarget =
        BuildTarget.builder("//java/com/example", "apk").setFlavor("aapt_package").build();
    BuildRuleParams aaptPackageResourcesParams =
        new FakeBuildRuleParamsBuilder(aaptPackageResourcesTarget).build();
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        aaptPackageResourcesParams,
        new SourcePathResolver(ruleResolver),
        /* manifest */ new TestSourcePath("java/src/com/facebook/base/AndroidManifest.xml"),
        createMock(FilteredResourcesProvider.class),
        ImmutableList.<HasAndroidResourceDeps>of(),
        ImmutableSet.<Path>of(),
        AndroidBinary.PackageType.DEBUG,
        ImmutableSet.<TargetCpuType>of(),
        ANDROID_JAVAC_OPTIONS,
        false,
        false);
    ruleResolver.addToIndex(aaptPackageResources);

    ImmutableAndroidPackageableCollection collection = new AndroidPackageableCollector(
            /* collectionRoot */ apkTarget,
        ImmutableSet.of(javaDep2BuildTarget),
            /* resourcesToExclude */ ImmutableSet.<BuildTarget>of())
        .addClasspathEntry(
            ((HasJavaClassHashes) javaDep1), Paths.get("ignored"))
        .addClasspathEntry(
            ((HasJavaClassHashes) javaDep2), Paths.get("ignored"))
        .addClasspathEntry(
            ((HasJavaClassHashes) javaLib), Paths.get("ignored"))
        .build();


    BuildRule preDexMergeRule = graphEnhancer.createPreDexMergeRule(
        aaptPackageResources,
        /* preDexRulesNotInThePackageableCollection */ ImmutableList
            .<DexProducedFromJavaLibrary>of(),
        collection);
    BuildTarget dexMergeTarget =
        BuildTarget.builder("//java/com/example", "apk").setFlavor("dex_merge").build();
    BuildRule dexMergeRule = ruleResolver.getRule(dexMergeTarget);

    assertEquals(dexMergeRule, preDexMergeRule);

    assertEquals(
        "There should be a #dex rule for dep1 and lib, but not dep2 because it is in the no_dx " +
            "list.  And we should depend on uber_r_dot_java.",
        3,
        dexMergeRule.getDeps().size());

    Iterator<BuildRule> depsForPreDexingIter = dexMergeRule.getDeps().iterator();

    BuildRule shouldBeAaptPackageResourcesRule = depsForPreDexingIter.next();
    assertEquals(aaptPackageResources, shouldBeAaptPackageResourcesRule);

    BuildRule preDexRule1 = depsForPreDexingIter.next();
    assertEquals("//java/com/example:dep1#dex", preDexRule1.getBuildTarget().toString());
    assertNotNull(ruleResolver.getRule(preDexRule1.getBuildTarget()));

    BuildRule preDexRule2 = depsForPreDexingIter.next();
    assertEquals("//java/com/example:lib#dex", preDexRule2.getBuildTarget().toString());
    assertNotNull(ruleResolver.getRule(preDexRule2.getBuildTarget()));
  }

  @Test
  public void testAllBuildablesExceptPreDexRule() {
    // Create an android_build_config() as a dependency of the android_binary().
    BuildTarget buildConfigBuildTarget = BuildTarget.builder("//java/com/example", "cfg").build();
    BuildRuleParams buildConfigParams = new FakeBuildRuleParamsBuilder(buildConfigBuildTarget)
        .build();
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidBuildConfigJavaLibrary buildConfigJavaLibrary = AndroidBuildConfigDescription
        .createBuildRule(
          buildConfigParams,
          "com.example.buck",
          /* values */ BuildConfigFields.empty(),
          /* valuesFile */ Optional.<SourcePath>absent(),
          /* useConstantExpressions */ false,
          ANDROID_JAVAC_OPTIONS,
          ruleResolver);

    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    BuildRuleParams originalParams = new FakeBuildRuleParamsBuilder(apkTarget)
        .setDeps(ImmutableSortedSet.<BuildRule>of(buildConfigJavaLibrary))
        .build();

    // set it up.
    Keystore keystore = createStrictMock(Keystore.class);
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        new TestSourcePath("AndroidManifest.xml"),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        BuildTargets.getBinPath(apkTarget, "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        /* buildRulesToExcludeFromDex */ ImmutableSet.<BuildTarget>of(),
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        ANDROID_JAVAC_OPTIONS,
        EnumSet.of(ExopackageMode.SECONDARY_DEX),
        keystore,
        /* buildConfigValues */ BuildConfigFields.empty(),
        /* buildConfigValuesFiles */ Optional.<SourcePath>absent(),
        /* nativePlatforms */ ImmutableMap.<TargetCpuType, NdkCxxPlatform>of());
    replay(keystore);
    AndroidGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    // Verify that android_build_config() was processed correctly.
    String flavor = "buildconfig_com_example_buck";
    assertEquals(
        "The only classpath entry to dex should be the one from the AndroidBuildConfigJavaLibrary" +
            " created via graph enhancement.",
        ImmutableSet.of(Paths.get(
            "buck-out/gen/java/com/example/lib__apk#" + flavor + "__output/apk#" + flavor + ".jar")
        ),
        result.classpathEntriesToDex());
    BuildTarget enhancedBuildConfigTarget = BuildTarget.builder(apkTarget).setFlavor(flavor)
        .build();
    BuildRule enhancedBuildConfigRule = ruleResolver.getRule(enhancedBuildConfigTarget);
    assertTrue(enhancedBuildConfigRule instanceof AndroidBuildConfigJavaLibrary);
    AndroidBuildConfigJavaLibrary enhancedBuildConfigJavaLibrary =
        (AndroidBuildConfigJavaLibrary) enhancedBuildConfigRule;
    AndroidBuildConfig androidBuildConfig = enhancedBuildConfigJavaLibrary.getAndroidBuildConfig();
    assertEquals("com.example.buck", androidBuildConfig.getJavaPackage());
    assertTrue(androidBuildConfig.isUseConstantExpressions());
    assertEquals(
        "IS_EXOPACKAGE defaults to false, but should now be true. DEBUG should still be true.",
        BuildConfigFields.fromFields(ImmutableList.of(
            new BuildConfigFields.Field("boolean", "DEBUG", "true"),
            new BuildConfigFields.Field("boolean", "IS_EXOPACKAGE", "true"),
            new BuildConfigFields.Field("int", "EXOPACKAGE_FLAGS", "1"))),
        androidBuildConfig.getBuildConfigFields());

    ImmutableSortedSet<BuildRule> finalDeps = result.finalDeps();
    // Verify that the only dep is computeExopackageDepsAbi
    assertEquals(1, finalDeps.size());
    BuildRule computeExopackageDepsAbiRule =
        findRuleOfType(ruleResolver, ComputeExopackageDepsAbi.class);
    assertEquals(computeExopackageDepsAbiRule, finalDeps.first());

    FilteredResourcesProvider resourcesProvider = result.aaptPackageResources()
        .getFilteredResourcesProvider();
    assertTrue(resourcesProvider instanceof ResourcesFilter);
    BuildRule resourcesFilterRule = findRuleOfType(ruleResolver, ResourcesFilter.class);

    BuildRule aaptPackageResourcesRule =
        findRuleOfType(ruleResolver, AaptPackageResources.class);
    MoreAsserts.assertDepends(
        "AaptPackageResources must depend on ResourcesFilter",
        aaptPackageResourcesRule,
        resourcesFilterRule);

    BuildRule packageStringAssetsRule =
        findRuleOfType(ruleResolver, PackageStringAssets.class);
    MoreAsserts.assertDepends(
        "PackageStringAssets must depend on ResourcesFilter",
        packageStringAssetsRule,
        aaptPackageResourcesRule);


    assertFalse(result.preDexMerge().isPresent());

    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on ResourcesFilter",
        computeExopackageDepsAbiRule,
        resourcesFilterRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on PackageStringAssets",
        computeExopackageDepsAbiRule,
        packageStringAssetsRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on AaptPackageResources",
        computeExopackageDepsAbiRule,
        aaptPackageResourcesRule);

    assertTrue(result.packageStringAssets().isPresent());
    assertTrue(result.computeExopackageDepsAbi().isPresent());

    verify(keystore);
  }

  private BuildRule findRuleOfType(BuildRuleResolver ruleResolver, Class<?> ruleClass) {
    for (BuildRule rule : ruleResolver.getBuildRules()) {
      if (ruleClass.isAssignableFrom(rule.getClass())) {
        return rule;
      }
    }
    fail("Could not find build rule of type " + ruleClass.getCanonicalName());
    return null;
  }
}
