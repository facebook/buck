/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.util.BuckConstant.SCRATCH_PATH;
import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static com.facebook.buck.util.BuckConstant.GEN_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AndroidBinaryTest {

  @Test
  public void testAndroidBinaryNoDx() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Two android_library deps, neither with an assets directory.
    BuildRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    BuildRule libraryOneRule = ruleResolver.getRule(libraryOne.getBuildTarget());
    BuildRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    BuildRule libraryTwoRule = ruleResolver.getRule(libraryTwo.getBuildTarget());

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    ImmutableSortedSet<BuildTarget> originalDepsTargets =
        ImmutableSortedSet.of(libraryOneRule.getBuildTarget(), libraryTwoRule.getBuildTarget());
    BuildRule keystoreRule = addKeystoreRule(ruleResolver);
    AndroidBinary androidBinary = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        binaryBuildTarget)
        .setOriginalDeps(originalDepsTargets)
        .setBuildTargetsToExcludeFromDex(
            ImmutableSet.of(
                BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryTwo")))
        .setManifest(new TestSourcePath("java/src/com/facebook/base/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(keystoreRule.getBuildTarget())
        .build(ruleResolver);

    AndroidPackageableCollection packageableCollection =
        androidBinary.getAndroidPackageableCollection();
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    FakeBuildableContext buildableContext = new FakeBuildableContext();

    androidBinary.addProguardCommands(
        packageableCollection.getClasspathEntriesToDex(),
        packageableCollection.getProguardConfigs(),
        commands,
        buildableContext);

    ImmutableSet<Path> expectedRecordedArtifacts = ImmutableSet.of(
        GEN_PATH.resolve(
            "java/src/com/facebook/base/__apk#aapt_package__proguard__/" +
                ".proguard/configuration.txt"),
        GEN_PATH.resolve("java/src/com/facebook/base/__apk#aapt_package__proguard__/" +
                ".proguard/mapping.txt"));

    assertEquals(expectedRecordedArtifacts, buildableContext.getRecordedArtifacts());

    buildableContext = new FakeBuildableContext();

    ImmutableList.Builder<Step> expectedSteps = ImmutableList.builder();

    ProGuardObfuscateStep.create(
        Optional.<Path>absent(),
        "1024M",
        GEN_PATH.resolve("java/src/com/facebook/base/__apk#aapt_package__proguard__/" +
                "/.proguard/proguard.txt"),
        ImmutableSet.<Path>of(),
        ProGuardObfuscateStep.SdkProguardType.DEFAULT,
        Optional.<Integer>absent(),
        ImmutableMap.of(
            GEN_PATH.resolve("java/src/com/facebook/base/lib__libraryOne__output/libraryOne.jar"),
            GEN_PATH.resolve(
                "java/src/com/facebook/base/__apk#aapt_package__proguard__/.proguard/buck-out/" +
                    "gen/java/src/com/facebook/base/lib__libraryOne__output/" +
                    "libraryOne-obfuscated.jar")),
        ImmutableSet.of(
            GEN_PATH.resolve("java/src/com/facebook/base/lib__libraryTwo__output/libraryTwo.jar")),
        GEN_PATH.resolve("java/src/com/facebook/base/__apk#aapt_package__proguard__/.proguard"),
        buildableContext,
        expectedSteps);

    assertEquals(expectedSteps.build(), commands.build());

    assertEquals(expectedRecordedArtifacts, buildableContext.getRecordedArtifacts());
  }

  static BuildRule createAndroidLibraryRule(String buildTarget,
      BuildRuleResolver ruleResolver,
      String resDirectory,
      String assetDirectory,
      String nativeLibsDirectory) {
    BuildTarget libraryOnebuildTarget = BuildTargetFactory.newInstance(buildTarget);
    AndroidLibraryBuilder androidLibraryRuleBuilder = AndroidLibraryBuilder
        .createBuilder(libraryOnebuildTarget)
        .addSrc(Paths.get(buildTarget.split(":")[1] + ".java"));

    if (!Strings.isNullOrEmpty(resDirectory) || !Strings.isNullOrEmpty(assetDirectory)) {
      BuildTarget resourceOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_resources");
      BuildRule androidResourceRule = ruleResolver.addToIndex(
          AndroidResourceRuleBuilder.newBuilder()
              .setResolver(new SourcePathResolver(ruleResolver))
              .setAssets(Paths.get(assetDirectory))
              .setRes(resDirectory == null ? null : Paths.get(resDirectory))
              .setBuildTarget(resourceOnebuildTarget)
              .build());

      androidLibraryRuleBuilder.addDep(androidResourceRule.getBuildTarget());
    }

    if (!Strings.isNullOrEmpty(nativeLibsDirectory)) {
      BuildTarget nativeLibOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_native_libs");
      BuildRule nativeLibsRule = PrebuiltNativeLibraryBuilder.newBuilder(nativeLibOnebuildTarget)
          .setNativeLibs(Paths.get(nativeLibsDirectory))
          .build(ruleResolver);
      ruleResolver.addToIndex(nativeLibsRule);
      androidLibraryRuleBuilder.addDep(nativeLibsRule.getBuildTarget());
    }

    return androidLibraryRuleBuilder.build(ruleResolver);
  }

  @Test
  public void testGetInputsToCompareToOutputIncludesManifest() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidBinaryBuilder androidBinaryRuleBuilder = AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .setManifest(new TestSourcePath("java/src/com/facebook/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(addKeystoreRule(ruleResolver).getBuildTarget());

    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should include manifest.",
        ImmutableList.of(Paths.get("java/src/com/facebook/AndroidManifest.xml")),
        androidBinaryRuleBuilder.build(ruleResolver).getInputs());
  }

  @Test
  public void testGetInputsToCompareToOutputIncludesProguardConfig() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidBinaryBuilder androidBinaryRuleBuilder = AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .setManifest(new TestSourcePath("java/src/com/facebook/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(addKeystoreRule(ruleResolver).getBuildTarget());

    SourcePath proguardConfig = new TestSourcePath("java/src/com/facebook/proguard.cfg");
    androidBinaryRuleBuilder.setProguardConfig(Optional.of(proguardConfig));
    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should include Proguard config, if present.",
        ImmutableList.of(
            Paths.get("java/src/com/facebook/AndroidManifest.xml"),
            Paths.get("java/src/com/facebook/proguard.cfg")),
        androidBinaryRuleBuilder.build(ruleResolver).getInputs());
  }

  @Test
  public void testGetUnsignedApkPath() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    Keystore keystore = addKeystoreRule(ruleResolver);

    AndroidBinary ruleInRootDirectory = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setKeystore(keystore.getBuildTarget())
        .setTarget("Google Inc.:Google APIs:16")
        .build(ruleResolver);
    assertEquals(Paths.get(GEN_DIR + "/fb4a.apk"), ruleInRootDirectory.getApkPath());

    AndroidBinary ruleInNonRootDirectory = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//java/com/example:fb4a"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setKeystore(keystore.getBuildTarget())
        .setTarget("Google Inc.:Google APIs:16")
        .build(ruleResolver);
    assertEquals(
        Paths.get(GEN_DIR + "/java/com/example/fb4a.apk"), ruleInNonRootDirectory.getApkPath());
  }

  @Test
  public void testGetProguardOutputFromInputClasspath() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    AndroidBinary rule = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setKeystore(addKeystoreRule(ruleResolver).getBuildTarget())
        .setTarget("Google Inc.:Google APIs:16")
        .build(ruleResolver);

    Path proguardDir = rule.getProguardOutputFromInputClasspath(
        SCRATCH_PATH.resolve("first-party/orca/lib-base/lib__lib-base__classes"));
    assertEquals(GEN_PATH.resolve(
            "__fbandroid_with_dash_debug_fbsign#aapt_package__proguard__/.proguard")
            .resolve(
                SCRATCH_PATH.resolve(
                    "first-party/orca/lib-base/lib__lib-base__classes-obfuscated.jar")),
        proguardDir);
  }

  private void assertCommandsInOrder(List<Step> steps, List<Class<?>> expectedCommands) {
    Iterable<Class<?>> filteredObservedCommands = FluentIterable
        .from(steps)
        .transform(new Function<Step, Class<?>>() {
          @Override
          public Class<?> apply(Step command) {
            return command.getClass();
          }
        })
        .filter(Predicates.in(Sets.newHashSet(expectedCommands)));
    MoreAsserts.assertIterablesEquals(expectedCommands, filteredObservedCommands);
  }

  @Test
  public void testDexingCommand() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidBinary splitDexRule = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setKeystore(addKeystoreRule(ruleResolver).getBuildTarget())
        .setTarget("Google Inc.:Google APIs:16")
        .setShouldSplitDex(true)
        .setLinearAllocHardLimit(0)
        .setPrimaryDexScenarioOverflowAllowed(true)
        .setDexCompression(DexStore.JAR)
        .build(ruleResolver);

    Set<Path> classpath = Sets.newHashSet();
    ImmutableSet.Builder<Path> secondaryDexDirectories = ImmutableSet.builder();
    ImmutableList.Builder<Step> commandsBuilder = ImmutableList.builder();
    Path primaryDexPath = SCRATCH_PATH.resolve(".dex/classes.dex");
    splitDexRule.addDexingSteps(
        classpath,
        Suppliers.<Map<String, HashCode>>ofInstance(ImmutableMap.<String, HashCode>of()),
        secondaryDexDirectories,
        commandsBuilder,
        primaryDexPath,
        Optional.<SourcePath>absent(),
        Optional.<SourcePath>absent());

    assertEquals("Expected 2 new assets paths (one for metadata.txt and the other for the " +
        "secondary zips)", 2, secondaryDexDirectories.build().size());

    List<Step> steps = commandsBuilder.build();
    assertCommandsInOrder(steps,
        ImmutableList.<Class<?>>of(SplitZipStep.class, SmartDexingStep.class));
  }

  @Test
  public void testCreateFilterResourcesStep() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRule keystoreRule = addKeystoreRule(resolver);
    AndroidBinaryBuilder builder = AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//:target"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setKeystore(keystoreRule.getBuildTarget())
        .setTarget("Google Inc:Google APIs:16")
        .setResourceFilter(new ResourceFilter(ImmutableList.of("mdpi")))
        .setResourceCompressionMode(ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS);

    AndroidBinary buildRule = (AndroidBinary) builder.build(resolver);
    ImmutableList<Path> resourceDirectories = ImmutableList.of(Paths.get("one"), Paths.get("two"));

    FilteredResourcesProvider resourcesProvider = buildRule.getEnhancementResult()
        .getAaptPackageResources()
        .getFilteredResourcesProvider();
    assertTrue(resourcesProvider instanceof ResourcesFilter);
    ImmutableList.Builder<Path> filteredDirs = ImmutableList.builder();
    ((ResourcesFilter) resourcesProvider)
        .createFilterResourcesStep(
            resourceDirectories,
            /* whitelistedStringsDir */ ImmutableSet.<Path>of(),
            /* locales */ ImmutableSet.<String>of(),
            filteredDirs);

    assertEquals(
        ImmutableList.of(
            Paths.get("buck-out/bin/__filtered__target#resources_filter__/0"),
            Paths.get("buck-out/bin/__filtered__target#resources_filter__/1")),
        filteredDirs.build());
  }

  private Keystore addKeystoreRule(BuildRuleResolver ruleResolver) {
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    return (Keystore) KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .build(ruleResolver);
  }
}
