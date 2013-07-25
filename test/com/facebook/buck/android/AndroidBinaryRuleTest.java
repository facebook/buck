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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.KeystoreRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.ZipSplitter;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

public class AndroidBinaryRuleTest {

  /**
   * Directory where native libraries are expected to put their output.
   */
  final String nativeOutDir = "buck-out/bin/__native_zips__fbandroid_with_dash_debug_fbsign__/";

  @Test
  public void testAndroidBinaryNoDx() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Two android_library deps, neither with an assets directory.
    JavaLibraryRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    JavaLibraryRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    AndroidBinaryRule androidBinary = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(binaryBuildTarget)
        .addDep(libraryOne.getBuildTarget())
        .addDep(libraryTwo.getBuildTarget())
        .addBuildRuleToExcludeFromDex(
            BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryTwo"))
        .setManifest("java/src/com/facebook/base/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setPackageType("debug"));

    DependencyGraph graph = RuleMap.createGraphFromBuildRules(ruleResolver);
    AndroidTransitiveDependencies transitiveDependencies =
        androidBinary.findTransitiveDependencies(graph);
    AndroidDexTransitiveDependencies dexTransitiveDependencies =
        androidBinary.findDexTransitiveDependencies(graph);
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    BuildContext context = createMock(BuildContext.class);
    replay(context);
    androidBinary.addProguardCommands(
        context,
        dexTransitiveDependencies,
        transitiveDependencies.proguardConfigs,
        commands,
        ImmutableSet.<String>of());
    verify(context);

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
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Two android_library deps, neither with an assets directory.
    JavaLibraryRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    JavaLibraryRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    AndroidBinaryRule androidBinary = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(binaryBuildTarget)
        .addDep(libraryOne.getBuildTarget())
        .addDep(libraryTwo.getBuildTarget())
        .setManifest("java/src/com/facebook/base/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setPackageType("debug"));

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
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Two android_library deps, one of which has an assets directory.
    JavaLibraryRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    JavaLibraryRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        "java/src/com/facebook/base/assets2",
        null /* nativeLibsDirectory */);

    AndroidResourceRule resourceOne = (AndroidResourceRule) ruleResolver
        .get(BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryTwo_resources"));

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    AndroidBinaryRule androidBinary = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(binaryBuildTarget)
        .addDep(libraryOne.getBuildTarget())
        .addDep(libraryTwo.getBuildTarget())
        .setManifest("java/src/com/facebook/base/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setPackageType("debug"));

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
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Two android_library deps, each with an assets directory.
    JavaLibraryRule libraryOne = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        "java/src/com/facebook/base/assets1",
        null /* nativeLibsDirectory */);
    JavaLibraryRule libraryTwo = createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        "java/src/com/facebook/base/assets2",
        null /* nativeLibsDirectory */);


    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base:apk");
    AndroidBinaryRule androidBinary = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(binaryBuildTarget)
        .addDep(libraryOne.getBuildTarget())
        .addDep(libraryTwo.getBuildTarget())
        .setManifest("java/src/com/facebook/base/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setPackageType("debug"));

    AndroidResourceRule resourceOne = (AndroidResourceRule) ruleResolver.get(
        BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryOne_resources"));
    AndroidResourceRule resourceTwo = (AndroidResourceRule) ruleResolver.get(
        BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryTwo_resources"));

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
      BuildRuleResolver ruleResolver,
      String resDirectory,
      String assetDirectory,
      String nativeLibsDirectory) {
    BuildTarget libraryOnebuildTarget = BuildTargetFactory.newInstance(buildTarget);
    AndroidLibraryRule.Builder androidLibraryRuleBuilder = AndroidLibraryRule
        .newAndroidLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .addSrc(buildTarget.split(":")[1] + ".java")
        .setBuildTarget(libraryOnebuildTarget);

    if (!Strings.isNullOrEmpty(resDirectory) || !Strings.isNullOrEmpty(assetDirectory)) {
      BuildTarget resourceOnebuildTarget = BuildTargetFactory.newInstance(buildTarget);
      AndroidResourceRule androidResourceRule = ruleResolver.buildAndAddToIndex(
          AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
          .setAssetsDirectory(assetDirectory)
          .setRes(resDirectory)
          .setBuildTarget(resourceOnebuildTarget));

      androidLibraryRuleBuilder.addDep(androidResourceRule.getBuildTarget());
    }

    if (!Strings.isNullOrEmpty(resDirectory) || !Strings.isNullOrEmpty(assetDirectory)) {
      BuildTarget resourceOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_resources");
      AndroidResourceRule androidResourceRule = ruleResolver.buildAndAddToIndex(
          AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
          .setAssetsDirectory(assetDirectory)
          .setRes(resDirectory)
          .setBuildTarget(resourceOnebuildTarget));

      androidLibraryRuleBuilder.addDep(androidResourceRule.getBuildTarget());
    }

    if (!Strings.isNullOrEmpty(nativeLibsDirectory)) {
      BuildTarget nativeLibOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_native_libs");
      PrebuiltNativeLibraryBuildRule nativeLibsRule = ruleResolver.buildAndAddToIndex(
          PrebuiltNativeLibraryBuildRule.newPrebuiltNativeLibrary(
              new FakeAbstractBuildRuleBuilderParams())
          .setBuildTarget(nativeLibOnebuildTarget)
          .setNativeLibsDirectory(nativeLibsDirectory));

      androidLibraryRuleBuilder.addDep(nativeLibsRule.getBuildTarget());
    }

    JavaLibraryRule androidLibraryRule = ruleResolver.buildAndAddToIndex(
        androidLibraryRuleBuilder);

    return androidLibraryRule;
  }

  @Test
  public void testGetInputsToCompareToOutput() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidBinaryRule.Builder androidBinaryRuleBuilder = AndroidBinaryRule
        .newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .setManifest("java/src/com/facebook/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(addKeystoreRule(ruleResolver));

    BuildContext context = createMock(BuildContext.class);
    replay(context);

    MoreAsserts.assertListEquals(
        "getInputsToCompareToOutput() should include manifest.",
        ImmutableList.of("java/src/com/facebook/AndroidManifest.xml"),
            ruleResolver.buildAndAddToIndex(androidBinaryRuleBuilder)
                .getInputsToCompareToOutput());

    SourcePath proguardConfig = new FileSourcePath("java/src/com/facebook/proguard.cfg");
    androidBinaryRuleBuilder.setProguardConfig(Optional.of(proguardConfig));
    MoreAsserts.assertListEquals(
        "getInputsToCompareToOutput() should include Proguard config, if present.",
        ImmutableList.of(
            "java/src/com/facebook/AndroidManifest.xml",
            "java/src/com/facebook/proguard.cfg"),
            ruleResolver.buildAndAddToIndex(androidBinaryRuleBuilder)
                .getInputsToCompareToOutput());

    verify(context);
  }

  @Test
  public void testGetUnsignedApkPath() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    AndroidBinaryRule ruleInRootDirectory = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest("AndroidManifest.xml")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setTarget("Google Inc.:Google APIs:16"));
    assertEquals(GEN_DIR + "/fb4a.apk", ruleInRootDirectory.getApkPath());

    AndroidBinaryRule ruleInNonRootDirectory = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example:fb4a"))
        .setManifest("AndroidManifest.xml")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setTarget("Google Inc.:Google APIs:16"));
    assertEquals(GEN_DIR + "/java/com/example/fb4a.apk", ruleInNonRootDirectory.getApkPath());
  }

  @Test
  public void testGetProguardOutputFromInputClasspath() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    AndroidBinaryRule rule = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
        .setManifest("AndroidManifest.xml")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setTarget("Google Inc.:Google APIs:16"));

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
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidBinaryRule splitDexRule = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
        .setManifest("AndroidManifest.xml")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setTarget("Google Inc.:Google APIs:16")
        .setDexSplitMode(new DexSplitMode(
            /* shouldSplitDex */ true,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
            DexStore.JAR)));

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

  @Test
  public void testCopyNativeLibraryCommandWithoutCpuFilter() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.<String>of() /* cpuFilters */,
        "path/to/source/libs.zip",
        "path/to/destination/",
        ImmutableList.of(
            "unzip -q -d " + nativeOutDir + "path/to/source path/to/source/libs.zip",
            "bash -c cp -R " + nativeOutDir + "path/to/source/* path/to/destination/"));
  }

  @Test
  public void testCopyNativeLibraryCommand() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.of("armv7"),
        "path/to/source/libs.zip",
        "path/to/destination/",
        ImmutableList.of(
            "unzip -q -d " + nativeOutDir + "path/to/source path/to/source/libs.zip",
            "bash -c " +
                "[ -d " + nativeOutDir + "path/to/source/armeabi-v7a ] && " +
                "cp -R " + nativeOutDir + "path/to/source/armeabi-v7a path/to/destination/ || " +
                "exit 0"));
  }

  @Test
  public void testCopyNativeLibraryCommandWithMultipleCpuFilters() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.of("arm", "x86"),
        "path/to/source/libs.zip",
        "path/to/destination/",
        ImmutableList.of(
            "unzip -q -d " + nativeOutDir + "path/to/source path/to/source/libs.zip",
            "bash -c [ -d " + nativeOutDir + "path/to/source/armeabi ] && " +
                "cp -R " + nativeOutDir + "path/to/source/armeabi path/to/destination/ || exit 0",
            "bash -c [ -d " + nativeOutDir + "path/to/source/x86 ] && " +
                "cp -R " + nativeOutDir + "path/to/source/x86 path/to/destination/ || exit 0"));
  }

  private void createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
      ImmutableSet<String> cpuFilters,
      String sourceZip,
      String destinationDir,
      ImmutableList<String> expectedShellCommands) {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidBinaryRule.Builder builder = AndroidBinaryRule.newAndroidBinaryRuleBuilder(
        new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
        .setManifest("AndroidManifest.xml")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setTarget("Google Inc:Google APIs:16");

    for (String filter: cpuFilters) {
      builder.addCpuFilter(filter);
    }

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    AndroidBinaryRule buildRule = ruleResolver.buildAndAddToIndex(builder);
    buildRule.unzipNativeLibrary(sourceZip, destinationDir, commands);

    ImmutableList<Step> steps = commands.build();

    assertEquals(steps.size(), expectedShellCommands.size() + 1);
    assertEquals(MakeCleanDirectoryStep.class, steps.get(0).getClass());

    ImmutableList<ShellStep> shellSteps =
        ImmutableList.copyOf(Iterables.filter(steps, ShellStep.class));
    assertEquals(shellSteps.size(), expectedShellCommands.size());
    ExecutionContext context = ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setProjectFilesystem(new ProjectFilesystem(new File(".")))
        .setEventBus(new BuckEventBus())
        .build();

    for (int i = 0; i < shellSteps.size(); ++i) {
      assertEquals(expectedShellCommands.get(i),
          Joiner.on(" ").join((shellSteps.get(i)).getShellCommand(context)));
    }
  }

  private BuildTarget addKeystoreRule(BuildRuleResolver ruleResolver) {
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    ruleResolver.buildAndAddToIndex(
        KeystoreRule.newKeystoreBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(keystoreTarget)
        .setStore("keystore/debug.keystore")
        .setProperties("keystore/debug.keystore.properties"));
    return keystoreTarget;
  }
}
