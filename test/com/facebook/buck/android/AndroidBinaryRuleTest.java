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

import static com.facebook.buck.util.BuckConstant.BIN_DIR;
import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cpp.PrebuiltNativeLibraryBuildRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.ZipSplitter;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AndroidBinaryRuleTest {
  private static final ArtifactCache artifactCache = new NoopArtifactCache();

  @Test
  public void testAndroidBinaryNoDx() {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    // Two android_library deps, neither with an assets directory.
    JavaLibraryRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        buildRuleIndex,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    JavaLibraryRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        buildRuleIndex,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    AndroidBinaryRule androidBinary = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(binaryBuildTarget)
        .addDep(libraryOne.getFullyQualifiedName())
        .addDep(libraryTwo.getFullyQualifiedName())
        .addBuildRuleToExcludeFromDex("//java/src/com/facebook/base:libraryTwo")
        .setManifest("java/src/com/facebook/base/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("java/src/com/facebook/base/keystore.properties")
        .setPackageType("debug")
        .setArtifactCache(artifactCache)
        .build(buildRuleIndex);
    buildRuleIndex.put(androidBinary.getFullyQualifiedName(), androidBinary);

    DependencyGraph graph = RuleMap.createGraphFromBuildRules(buildRuleIndex);
    AndroidTransitiveDependencies transitiveDependencies =
        androidBinary.findTransitiveDependencies(graph, Optional.<BuildContext>absent());
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    androidBinary.addProguardCommands(transitiveDependencies,
        commands,
        ImmutableSet.<String>of());

    MakeCleanDirectoryStep expectedClean =
        new MakeCleanDirectoryStep("buck-out/gen/java/src/com/facebook/base/.proguard/apk");

    GenProGuardConfigStep expectedGenProguard =
        new GenProGuardConfigStep(
            "buck-out/bin/java/src/com/facebook/base/__manifest_apk__/AndroidManifest.xml",
            ImmutableSet.<String>of(),
            "buck-out/gen/java/src/com/facebook/base/.proguard/apk/proguard.txt");

    ProGuardObfuscateStep expectedObfuscation =
        new ProGuardObfuscateStep(
          "buck-out/gen/java/src/com/facebook/base/.proguard/apk/proguard.txt",
          ImmutableSet.<String>of(),
          false,
          ImmutableMap.of(
              "buck-out/gen/java/src/com/facebook/base/lib__libraryOne__output/libraryOne.jar",
              "buck-out/gen/java/src/com/facebook/base/.proguard/apk/buck-out/gen/java/src/com/" +
                  "facebook/base/lib__libraryOne__output/libraryOne-obfuscated.jar"),
          ImmutableSet.of("buck-out/gen/java/src/com/facebook/base/lib__libraryTwo__output/libraryTwo.jar"),
          "buck-out/gen/java/src/com/facebook/base/.proguard/apk");

    assertEquals(
        ImmutableList.of(expectedClean, expectedGenProguard, expectedObfuscation),
        commands.build());
  }

  /**
   * Tests an android_binary with zero dependent android_library rules that contains an assets
   * directory.
   */
  @Test
  public void testCreateAllAssetsDirectoryWithZeroAssetsDirectories() {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    // Two android_library deps, neither with an assets directory.
    JavaLibraryRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        buildRuleIndex,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    JavaLibraryRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        buildRuleIndex,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    AndroidBinaryRule androidBinary = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(binaryBuildTarget)
        .addDep(libraryOne.getFullyQualifiedName())
        .addDep(libraryTwo.getFullyQualifiedName())
        .setManifest("java/src/com/facebook/base/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("java/src/com/facebook/base/keystore.properties")
        .setPackageType("debug")
        .setArtifactCache(artifactCache)
        .build(buildRuleIndex);
    buildRuleIndex.put(androidBinary.getFullyQualifiedName(), androidBinary);

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    Set<String> assetsDirectories = ImmutableSet.of();
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) {
        throw new RuntimeException("Unexpected: no assets directories to traverse!");
      }
    };

    // Invoke createAllAssetsDirectory(), the method under test.
    Optional<String> allAssetsDirectory = androidBinary.createAllAssetsDirectory(
        assetsDirectories, ImmutableMap.<String, File>of(), commands, traverser);

    // Verify that no assets/ directory is used.
    assertFalse("There should not be an assets/ directory to pass to aapt.",
        allAssetsDirectory.isPresent());
    assertTrue("There should not be any commands to build up an assets/ directory.",
        commands.build().isEmpty());
  }

  /**
   * Tests an android_binary with one dependent android_library rule that contains an assets
   * directory.
   */
  @Test
  public void testCreateAllAssetsDirectoryWithOneAssetsDirectory() {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    // Two android_library deps, one of which has an assets directory.
    JavaLibraryRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        buildRuleIndex,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    JavaLibraryRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        buildRuleIndex,
        null, /* resDirectory */
        "java/src/com/facebook/base/assets2",
        null /* nativeLibsDirectory */);

    AndroidResourceRule resourceOne = (AndroidResourceRule) buildRuleIndex.get(
        "//java/src/com/facebook/base:libraryTwo_resources");

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    AndroidBinaryRule androidBinary = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(binaryBuildTarget)
        .addDep(libraryOne.getFullyQualifiedName())
        .addDep(libraryTwo.getFullyQualifiedName())
        .setManifest("java/src/com/facebook/base/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("java/src/com/facebook/base/keystore.properties")
        .setPackageType("debug")
        .setArtifactCache(artifactCache)
        .build(buildRuleIndex);
    buildRuleIndex.put(androidBinary.getFullyQualifiedName(), androidBinary);

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    Set<String> assetsDirectories = ImmutableSet.of(resourceOne.getAssets());
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) {
        String rootPath = traversal.getRoot().getPath();
        if ("java/src/com/facebook/base/assets2".equals(rootPath)) {
          traversal.visit(
              new File("java/src/com/facebook/base/assets2",
                  "fonts/Theinhardt-Medium.otf"),
              "fonts/Theinhardt-Medium.otf");
          traversal.visit(
              new File("java/src/com/facebook/base/assets2",
                  "fonts/Theinhardt-Regular.otf"),
              "fonts/Theinhardt-Regular.otf");
        } else {
          throw new RuntimeException("Unexpected path: " + rootPath);
        }
      }
    };

    // Invoke createAllAssetsDirectory(), the method under test.
    Optional<String> allAssetsDirectory = androidBinary.createAllAssetsDirectory(
        assetsDirectories, ImmutableMap.<String, File>of(), commands, traverser);

    // Verify that the existing assets/ directory will be passed to aapt.
    assertTrue(allAssetsDirectory.isPresent());
    assertEquals(
        "Even though there is only one assets directory, the one in " + BIN_DIR + " should be used.",
        androidBinary.getPathToAllAssetsDirectory(),
        allAssetsDirectory.get());
  }

  /**
   * Tests an android_binary with multiple dependent android_library rules, each with its own assets
   * directory.
   */
  @Test
  public void testCreateAllAssetsDirectoryWithMultipleAssetsDirectories() {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    // Two android_library deps, each with an assets directory.
    JavaLibraryRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        buildRuleIndex,
        null, /* resDirectory */
        "java/src/com/facebook/base/assets1",
        null /* nativeLibsDirectory */);
    JavaLibraryRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        buildRuleIndex,
        null, /* resDirectory */
        "java/src/com/facebook/base/assets2",
        null /* nativeLibsDirectory */);


    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    AndroidBinaryRule androidBinary = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(binaryBuildTarget)
        .addDep(libraryOne.getFullyQualifiedName())
        .addDep(libraryTwo.getFullyQualifiedName())
        .setManifest("java/src/com/facebook/base/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("java/src/com/facebook/base/keystore.properties")
        .setPackageType("debug")
        .setArtifactCache(artifactCache)
        .build(buildRuleIndex);
    buildRuleIndex.put(androidBinary.getFullyQualifiedName(), androidBinary);

    AndroidResourceRule resourceOne = (AndroidResourceRule) buildRuleIndex.get(
        "//java/src/com/facebook/base:libraryOne_resources");
    AndroidResourceRule resourceTwo = (AndroidResourceRule) buildRuleIndex.get(
        "//java/src/com/facebook/base:libraryTwo_resources");

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    Set<String> assetsDirectories = ImmutableSet.of(
        resourceOne.getAssets(),
        resourceTwo.getAssets());
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) {
        String rootPath = traversal.getRoot().getPath();
        if ("java/src/com/facebook/base/assets1".equals(rootPath)) {
          traversal.visit(
              new File("java/src/com/facebook/base/assets1",
                  "guava-10.0.1-fork.dex.1.jar"),
              "guava-10.0.1-fork.dex.1.jar");
        } else if ("java/src/com/facebook/base/assets2".equals(rootPath)) {
          traversal.visit(
              new File("java/src/com/facebook/base/assets2",
                  "fonts/Theinhardt-Medium.otf"),
              "fonts/Theinhardt-Medium.otf");
          traversal.visit(
              new File("java/src/com/facebook/base/assets2",
                  "fonts/Theinhardt-Regular.otf"),
              "fonts/Theinhardt-Regular.otf");
        } else {
          throw new RuntimeException("Unexpected path: " + rootPath);
        }
      }
    };

    // Invoke createAllAssetsDirectory(), the method under test.
    Optional<String> allAssetsDirectory = androidBinary.createAllAssetsDirectory(
        assetsDirectories, ImmutableMap.<String, File>of(), commands, traverser);

    // Verify that an assets/ directory will be created and passed to aapt.
    assertTrue(allAssetsDirectory.isPresent());
    assertEquals(BIN_DIR + "/java/src/com/facebook/base/__assets_apk__", allAssetsDirectory.get());
    List<? extends Step> expectedCommands = ImmutableList.of(
        new MakeCleanDirectoryStep(BIN_DIR + "/java/src/com/facebook/base/__assets_apk__"),
        new MkdirAndSymlinkFileStep(
            "java/src/com/facebook/base/assets1/guava-10.0.1-fork.dex.1.jar",
            BIN_DIR + "/java/src/com/facebook/base/__assets_apk__/guava-10.0.1-fork.dex.1.jar"),
        new MkdirAndSymlinkFileStep(
            "java/src/com/facebook/base/assets2/fonts/Theinhardt-Medium.otf",
            BIN_DIR + "/java/src/com/facebook/base/__assets_apk__/fonts/Theinhardt-Medium.otf"),
        new MkdirAndSymlinkFileStep(
            "java/src/com/facebook/base/assets2/fonts/Theinhardt-Regular.otf",
            BIN_DIR + "/java/src/com/facebook/base/__assets_apk__/fonts/Theinhardt-Regular.otf"));
    MoreAsserts.assertListEquals(expectedCommands, commands.build());
  }

  private JavaLibraryRule createAndroidLibraryRule(String buildTarget,
      Map<String, BuildRule> buildRuleIndex,
      String resDirectory,
      String assetDirectory,
      String nativeLibsDirectory) {
    BuildTarget libraryOnebuildTarget = BuildTargetFactory.newInstance(buildTarget);
    AndroidLibraryRule.Builder androidLibraryRuleBuilder = AndroidLibraryRule
        .newAndroidLibraryRuleBuilder()
        .addSrc(buildTarget.split(":")[1] + ".java")
        .setBuildTarget(libraryOnebuildTarget)
        .setArtifactCache(artifactCache);

    if (!Strings.isNullOrEmpty(resDirectory) || !Strings.isNullOrEmpty(assetDirectory)) {
      BuildTarget resourceOnebuildTarget = BuildTargetFactory.newInstance(buildTarget);
      AndroidResourceRule androidResourceRule = AndroidResourceRule.newAndroidResourceRuleBuilder()
          .setAssetsDirectory(assetDirectory)
          .setRes(resDirectory)
          .setBuildTarget(resourceOnebuildTarget)
          .setArtifactCache(artifactCache)
          .build(buildRuleIndex);
      buildRuleIndex.put(androidResourceRule.getFullyQualifiedName(), androidResourceRule);

      androidLibraryRuleBuilder.addDep(androidResourceRule.getFullyQualifiedName());
    }

    if (!Strings.isNullOrEmpty(resDirectory) || !Strings.isNullOrEmpty(assetDirectory)) {
      BuildTarget resourceOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_resources");
      AndroidResourceRule androidResourceRule = AndroidResourceRule.newAndroidResourceRuleBuilder()
          .setAssetsDirectory(assetDirectory)
          .setRes(resDirectory)
          .setBuildTarget(resourceOnebuildTarget)
          .setArtifactCache(artifactCache)
          .build(buildRuleIndex);
      buildRuleIndex.put(androidResourceRule.getFullyQualifiedName(), androidResourceRule);

      androidLibraryRuleBuilder.addDep(androidResourceRule.getFullyQualifiedName());
    }

    if (!Strings.isNullOrEmpty(nativeLibsDirectory)) {
      BuildTarget nativeLibOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_native_libs");
      PrebuiltNativeLibraryBuildRule nativeLibsRule =
          PrebuiltNativeLibraryBuildRule.newPrebuiltNativeLibrary()
          .setBuildTarget(nativeLibOnebuildTarget)
          .setNativeLibsDirectory(nativeLibsDirectory)
          .setArtifactCache(artifactCache)
          .build(buildRuleIndex);
      buildRuleIndex.put(nativeLibsRule.getFullyQualifiedName(), nativeLibsRule);

      androidLibraryRuleBuilder.addDep(nativeLibsRule.getFullyQualifiedName());
    }

    JavaLibraryRule androidLibraryRule = androidLibraryRuleBuilder.build(buildRuleIndex);
    buildRuleIndex.put(androidLibraryRule.getFullyQualifiedName(), androidLibraryRule);

    return androidLibraryRule;
  }

  @Test
  public void testGetInputsToCompareToOutput() {
    Map<String, BuildRule> buildRuleIndex = ImmutableMap.of();
    AndroidBinaryRule.Builder androidBinaryRuleBuilder = AndroidBinaryRule
        .newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .setManifest("java/src/com/facebook/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setArtifactCache(artifactCache)
        .setKeystorePropertiesPath("java/src/com/facebook/base/keystore.properties");

    BuildContext context = createMock(BuildContext.class);
    replay(context);

    MoreAsserts.assertListEquals(
        "getInputsToCompareToOutput() should include manifest and keystore file.",
        ImmutableList.of(
            "java/src/com/facebook/AndroidManifest.xml",
            "java/src/com/facebook/base/keystore.properties"),
            androidBinaryRuleBuilder.build(buildRuleIndex).getInputsToCompareToOutput(context));

    androidBinaryRuleBuilder.setProguardConfig(Optional.of("java/src/com/facebook/proguard.cfg"));
    MoreAsserts.assertListEquals(
        "getInputsToCompareToOutput() should include Proguard config, if present.",
        ImmutableList.of(
            "java/src/com/facebook/AndroidManifest.xml",
            "java/src/com/facebook/base/keystore.properties",
            "java/src/com/facebook/proguard.cfg"),
            androidBinaryRuleBuilder.build(buildRuleIndex).getInputsToCompareToOutput(context));

    verify(context);
  }

  @Test
  public void testGetUnsignedApkPath() {
    AndroidBinaryRule ruleInRootDirectory = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest("AndroidManifest.xml")
        .setKeystorePropertiesPath("keystore.properties")
        .setTarget("Google Inc.:Google APIs:16")
        .setArtifactCache(artifactCache)
        .build(ImmutableMap.<String, BuildRule>of());
    assertEquals(GEN_DIR + "/fb4a.apk", ruleInRootDirectory.getApkPath());

    AndroidBinaryRule ruleInNonRootDirectory = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example:fb4a"))
        .setManifest("AndroidManifest.xml")
        .setKeystorePropertiesPath("keystore.properties")
        .setTarget("Google Inc.:Google APIs:16")
        .setArtifactCache(artifactCache)
        .build(ImmutableMap.<String, BuildRule>of());
    assertEquals(GEN_DIR + "/java/com/example/fb4a.apk", ruleInNonRootDirectory.getApkPath());
  }

  @Test
  public void testGetProguardOutputFromInputClasspath() {
    AndroidBinaryRule rule = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
        .setManifest("AndroidManifest.xml")
        .setKeystorePropertiesPath("keystore.properties")
        .setTarget("Google Inc.:Google APIs:16")
        .setArtifactCache(artifactCache)
        .build(ImmutableMap.<String, BuildRule>of());

    String proguardDir = rule.getProguardOutputFromInputClasspath(
        BIN_DIR + "/first-party/orca/lib-base/lib__lib-base__classes");
    assertEquals(GEN_DIR + "/.proguard/fbandroid_with_dash_debug_fbsign/" +
        BIN_DIR + "/first-party/orca/lib-base/lib__lib-base__classes-obfuscated.jar",
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
    AndroidBinaryRule splitDexRule = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
        .setManifest("AndroidManifest.xml")
        .setKeystorePropertiesPath("keystore.properties")
        .setTarget("Google Inc.:Google APIs:16")
        .setDexSplitMode(new AndroidBinaryRule.DexSplitMode(true,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE))
        .setArtifactCache(artifactCache)
        .build(ImmutableMap.<String, BuildRule>of());

    Set<String> classpath = Sets.newHashSet();
    ImmutableSet.Builder<String> secondaryDexDirectories = ImmutableSet.builder();
    ImmutableList.Builder<Step> commandsBuilder = ImmutableList.builder();
    String primaryDexPath = BIN_DIR + "/.dex/classes.dex";
    splitDexRule.addDexingCommands(classpath, secondaryDexDirectories, commandsBuilder, primaryDexPath);

    assertEquals("Expected 2 new assets paths (one for metadata.txt and the other for the " +
        "secondary zips)", 2, secondaryDexDirectories.build().size());

    List<Step> steps = commandsBuilder.build();
    assertCommandsInOrder(steps,
        ImmutableList.<Class<?>>of(SplitZipStep.class, SmartDexingStep.class));
  }
}
