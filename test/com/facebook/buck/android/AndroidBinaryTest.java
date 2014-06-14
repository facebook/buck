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

import static com.facebook.buck.util.BuckConstant.BIN_PATH;
import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static com.facebook.buck.util.BuckConstant.GEN_PATH;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidBinary.TargetCpuType;
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
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.ProjectFilesystem;
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

import java.io.File;
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
    BuildRule libraryOneRule = ruleResolver.get(libraryOne.getBuildTarget());
    BuildRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    BuildRule libraryTwoRule = ruleResolver.get(libraryTwo.getBuildTarget());

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    ImmutableSortedSet<BuildRule> originalDeps =
        ImmutableSortedSet.of(libraryOneRule, libraryTwoRule);
    BuildRule keystoreRule = addKeystoreRule(ruleResolver);
    AndroidBinary androidBinary = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        binaryBuildTarget)
        .setOriginalDeps(originalDeps)
        .setBuildTargetsToExcludeFromDex(
            ImmutableSet.of(
                BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryTwo")))
        .setManifest(new TestSourcePath("java/src/com/facebook/base/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore((Keystore) keystoreRule)
        .build(ruleResolver);

    AndroidPackageableCollection packageableCollection =
        androidBinary.getAndroidPackageableCollection();
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    FakeBuildableContext buildableContext = new FakeBuildableContext();

    androidBinary.addProguardCommands(
        packageableCollection.classpathEntriesToDex,
        packageableCollection.proguardConfigs,
        commands,
        ImmutableList.<Path>of(),
        buildableContext);

    ImmutableSet<Path> expectedRecordedArtifacts = ImmutableSet.of(
        Paths.get("buck-out/gen/java/src/com/facebook/base/.proguard/apk/configuration.txt"),
        Paths.get("buck-out/gen/java/src/com/facebook/base/.proguard/apk/mapping.txt"));

    assertEquals(expectedRecordedArtifacts, buildableContext.getRecordedArtifacts());

    buildableContext = new FakeBuildableContext();

    ImmutableList.Builder<Step> expectedSteps = ImmutableList.builder();

    MakeCleanDirectoryStep expectedClean =
        new MakeCleanDirectoryStep(
            Paths.get("buck-out/gen/java/src/com/facebook/base/.proguard/apk"));
    expectedSteps.add(expectedClean);

    GenProGuardConfigStep expectedGenProguard =
        new GenProGuardConfigStep(
            Paths.get("buck-out/bin/java/src/com/facebook/base/" +
                "__manifest_apk#aapt_package__/AndroidManifest.xml"),
            ImmutableList.<Path>of(),
            Paths.get("buck-out/gen/java/src/com/facebook/base/.proguard/apk/proguard.txt"));
    expectedSteps.add(expectedGenProguard);

    ProGuardObfuscateStep.create(
        Optional.<Path>absent(),
        Paths.get("buck-out/gen/java/src/com/facebook/base/.proguard/apk/proguard.txt"),
        ImmutableSet.<Path>of(),
        ProGuardObfuscateStep.SdkProguardType.DEFAULT,
        Optional.<Integer>absent(),
        ImmutableMap.of(
            Paths.get(
                "buck-out/gen/java/src/com/facebook/base/lib__libraryOne__output/libraryOne.jar"),
            Paths.get(
                "buck-out/gen/java/src/com/facebook/base/.proguard/apk/buck-out/gen/" +
                "java/src/com/facebook/base/lib__libraryOne__output/libraryOne-obfuscated.jar")),
        ImmutableSet.of(
            Paths.get(
              "buck-out/gen/java/src/com/facebook/base/lib__libraryTwo__output/libraryTwo.jar")),
        Paths.get("buck-out/gen/java/src/com/facebook/base/.proguard/apk"),
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
              .setAssets(Paths.get(assetDirectory))
              .setRes(resDirectory == null ? null : Paths.get(resDirectory))
              .setBuildTarget(resourceOnebuildTarget)
              .build());

      androidLibraryRuleBuilder.addDep(androidResourceRule);
    }

    if (!Strings.isNullOrEmpty(nativeLibsDirectory)) {
      BuildTarget nativeLibOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_native_libs");
      BuildRule nativeLibsRule = PrebuiltNativeLibraryBuilder.newBuilder(nativeLibOnebuildTarget)
          .setNativeLibs(Paths.get(nativeLibsDirectory))
          .build();
      ruleResolver.addToIndex(nativeLibOnebuildTarget, nativeLibsRule);
      androidLibraryRuleBuilder.addDep(nativeLibsRule);
    }

    return androidLibraryRuleBuilder.build(ruleResolver);
  }

  @Test
  public void testGetInputsToCompareToOutput() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidBinaryBuilder androidBinaryRuleBuilder = AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .setManifest(new TestSourcePath("java/src/com/facebook/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore((Keystore) addKeystoreRule(ruleResolver));

    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should include manifest.",
        ImmutableList.of(Paths.get("java/src/com/facebook/AndroidManifest.xml")),
        androidBinaryRuleBuilder.build().getInputs());

    SourcePath proguardConfig = new TestSourcePath("java/src/com/facebook/proguard.cfg");
    androidBinaryRuleBuilder.setProguardConfig(Optional.of(proguardConfig));
    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should include Proguard config, if present.",
        ImmutableList.of(
            Paths.get("java/src/com/facebook/AndroidManifest.xml"),
            Paths.get("java/src/com/facebook/proguard.cfg")),
        androidBinaryRuleBuilder.build().getInputs());
  }

  @Test
  public void testGetUnsignedApkPath() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    Keystore keystore = (Keystore) addKeystoreRule(ruleResolver);

    AndroidBinary ruleInRootDirectory = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setKeystore(keystore)
        .setTarget("Google Inc.:Google APIs:16")
        .build(ruleResolver);
    assertEquals(Paths.get(GEN_DIR + "/fb4a.apk"), ruleInRootDirectory.getApkPath());

    AndroidBinary ruleInNonRootDirectory = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//java/com/example:fb4a"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setKeystore(keystore)
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
        .setKeystore((Keystore) addKeystoreRule(ruleResolver))
        .setTarget("Google Inc.:Google APIs:16")
        .build(ruleResolver);

    Path proguardDir = rule.getProguardOutputFromInputClasspath(
        BIN_PATH.resolve("first-party/orca/lib-base/lib__lib-base__classes"));
    assertEquals(GEN_PATH.resolve(".proguard/fbandroid_with_dash_debug_fbsign").resolve(
        BIN_PATH.resolve("first-party/orca/lib-base/lib__lib-base__classes-obfuscated.jar")),
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
        .setKeystore((Keystore) addKeystoreRule(ruleResolver))
        .setTarget("Google Inc.:Google APIs:16")
        .setShouldSplitDex(true)
        .setLinearAllocHardLimit(0)
        .setPrimaryDexScenarioOverflowAllowed(true)
        .setDexCompression(DexStore.JAR)
        .build(ruleResolver);

    Set<Path> classpath = Sets.newHashSet();
    ImmutableSet.Builder<Path> secondaryDexDirectories = ImmutableSet.builder();
    ImmutableList.Builder<Step> commandsBuilder = ImmutableList.builder();
    Path primaryDexPath = BIN_PATH.resolve(".dex/classes.dex");
    splitDexRule.addDexingSteps(
        classpath,
        Suppliers.<Map<String, HashCode>>ofInstance(ImmutableMap.<String, HashCode>of()),
        secondaryDexDirectories,
        commandsBuilder,
        primaryDexPath);

    assertEquals("Expected 2 new assets paths (one for metadata.txt and the other for the " +
        "secondary zips)", 2, secondaryDexDirectories.build().size());

    List<Step> steps = commandsBuilder.build();
    assertCommandsInOrder(steps,
        ImmutableList.<Class<?>>of(SplitZipStep.class, SmartDexingStep.class));
  }

  @Test
  public void testCopyNativeLibraryCommandWithoutCpuFilter() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.<TargetCpuType>of() /* cpuFilters */,
        "/path/to/source",
        "/path/to/destination/",
        ImmutableList.of(
            "cp -R /path/to/source/* /path/to/destination",
            "rename_native_executables"));
  }

  @Test
  public void testCopyNativeLibraryCommand() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.of(TargetCpuType.ARMV7),
        "/path/to/source",
        "/path/to/destination/",
        ImmutableList.of(
            "[ -d /path/to/source/armeabi-v7a ] && mkdir -p /path/to/destination/armeabi-v7a " +
                "&& cp -R /path/to/source/armeabi-v7a/* /path/to/destination/armeabi-v7a",
            "rename_native_executables"));
  }

  @Test
  public void testCopyNativeLibraryCommandWithMultipleCpuFilters() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.of(TargetCpuType.ARM, TargetCpuType.X86),
        "/path/to/source",
        "/path/to/destination/",
        ImmutableList.of(
            "[ -d /path/to/source/armeabi ] && mkdir -p /path/to/destination/armeabi " +
                "&& cp -R /path/to/source/armeabi/* /path/to/destination/armeabi",
            "[ -d /path/to/source/x86 ] && mkdir -p /path/to/destination/x86 " +
                "&& cp -R /path/to/source/x86/* /path/to/destination/x86",
            "rename_native_executables"));
  }

  @Test
  public void testCreateFilterResourcesStep() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRule keystoreRule = addKeystoreRule(resolver);
    AndroidBinaryBuilder builder = AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//:target"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setKeystore((Keystore) keystoreRule)
        .setTarget("Google Inc:Google APIs:16")
        .setResourceFilter(new ResourceFilter(ImmutableList.<String>of("mdpi")))
        .setResourceCompressionMode(ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS);

    AndroidBinary buildRule = (AndroidBinary) builder.build(resolver);
    ImmutableList<Path> resourceDirectories = ImmutableList.of(Paths.get("one"), Paths.get("two"));

    assertTrue(buildRule.getFilteredResourcesProvider() instanceof ResourcesFilter);
    ImmutableList.Builder<Path> filteredDirs = ImmutableList.builder();
    ((ResourcesFilter) buildRule.getFilteredResourcesProvider())
        .createFilterResourcesStep(
            resourceDirectories,
                /* whitelistedStringsDir */ ImmutableSet.<Path>of(),
            filteredDirs);

    assertEquals(
        ImmutableList.of(
            Paths.get("buck-out/bin/__filtered__target#resources_filter__/0"),
            Paths.get("buck-out/bin/__filtered__target#resources_filter__/1")),
        filteredDirs.build());
  }

  private void createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
      ImmutableSet<TargetCpuType> cpuFilters,
      String sourceDir,
      String destinationDir,
      ImmutableList<String> expectedCommandDescriptions) {

    class FakeProjectFilesystem extends ProjectFilesystem {

      public FakeProjectFilesystem() {
        super(new File("."));
      }

      @Override
      public Path resolve(Path path) {
        return path;
      }
    }

    // Invoke copyNativeLibrary to populate the steps.
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    AndroidBinary.copyNativeLibrary(
        Paths.get(sourceDir), Paths.get(destinationDir), cpuFilters, stepsBuilder);
    ImmutableList<Step> steps = stepsBuilder.build();

    assertEquals(steps.size(), expectedCommandDescriptions.size());
    ExecutionContext context = createMock(ExecutionContext.class);
    expect(context.getProjectFilesystem()).andReturn(new FakeProjectFilesystem()).anyTimes();
    replay(context);

    for (int i = 0; i < steps.size(); ++i) {
      String description = steps.get(i).getDescription(context);
      assertEquals(expectedCommandDescriptions.get(i), description);
    }

    verify(context);
  }

  private BuildRule addKeystoreRule(BuildRuleResolver ruleResolver) {
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    return KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .build(ruleResolver);
  }
}
