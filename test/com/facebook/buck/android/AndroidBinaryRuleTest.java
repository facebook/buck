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
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidBinaryRule.TargetCpuType;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class AndroidBinaryRuleTest {

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
        .addClasspathDep(libraryOne.getBuildTarget())
        .addClasspathDep(libraryTwo.getBuildTarget())
        .addBuildRuleToExcludeFromDex(
            BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryTwo"))
        .setManifest(new FileSourcePath("java/src/com/facebook/base/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(addKeystoreRule(ruleResolver))
        .setPackageType("debug"));

    AndroidTransitiveDependencies transitiveDependencies =
        androidBinary.findTransitiveDependencies();
    AndroidDexTransitiveDependencies dexTransitiveDependencies =
        androidBinary.findDexTransitiveDependencies();
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    BuildContext context = createMock(BuildContext.class);
    replay(context);
    androidBinary.addProguardCommands(
        context,
        dexTransitiveDependencies.classpathEntriesToDex,
        transitiveDependencies.proguardConfigs,
        commands,
        ImmutableSet.<String>of());
    verify(context);

    MakeCleanDirectoryStep expectedClean =
        new MakeCleanDirectoryStep("buck-out/gen/java/src/com/facebook/base/.proguard/apk");

    GenProGuardConfigStep expectedGenProguard =
        new GenProGuardConfigStep(
            "buck-out/bin/java/src/com/facebook/base/__manifest_apk#aapt_package__/AndroidManifest.xml",
            ImmutableSet.<String>of(),
            "buck-out/gen/java/src/com/facebook/base/.proguard/apk/proguard.txt");

    Step expectedObfuscation =
        ProGuardObfuscateStep.create(
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

  static JavaLibraryRule createAndroidLibraryRule(String buildTarget,
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
      BuildRule nativeLibsRule = ruleResolver.buildAndAddToIndex(
          PrebuiltNativeLibrary.newPrebuiltNativeLibrary(
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
        .setManifest(new FileSourcePath("java/src/com/facebook/AndroidManifest.xml"))
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
    androidBinaryRuleBuilder.setBuildTarget(new BuildTarget("//java/src/com/facebook", "app2"));
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
    BuildTarget keystoreTarget = addKeystoreRule(ruleResolver);

    AndroidBinaryRule ruleInRootDirectory = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest(new FileSourcePath("AndroidManifest.xml"))
        .setKeystore(keystoreTarget)
        .setTarget("Google Inc.:Google APIs:16"));
    assertEquals(GEN_DIR + "/fb4a.apk", ruleInRootDirectory.getApkPath());

    AndroidBinaryRule ruleInNonRootDirectory = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example:fb4a"))
        .setManifest(new FileSourcePath("AndroidManifest.xml"))
        .setKeystore(keystoreTarget)
        .setTarget("Google Inc.:Google APIs:16"));
    assertEquals(GEN_DIR + "/java/com/example/fb4a.apk", ruleInNonRootDirectory.getApkPath());
  }

  @Test
  public void testGetProguardOutputFromInputClasspath() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    AndroidBinaryRule rule = ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
        .setManifest(new FileSourcePath("AndroidManifest.xml"))
        .setKeystore(addKeystoreRule(ruleResolver))
        .setTarget("Google Inc.:Google APIs:16"));

    Path proguardDir = rule.getProguardOutputFromInputClasspath(
        BIN_DIR + "/first-party/orca/lib-base/lib__lib-base__classes");
    assertEquals(GEN_DIR + "/.proguard/fbandroid_with_dash_debug_fbsign/" +
        BIN_DIR + "/first-party/orca/lib-base/lib__lib-base__classes-obfuscated.jar",
        proguardDir.toString());
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
        .setManifest(new FileSourcePath("AndroidManifest.xml"))
        .setKeystore(addKeystoreRule(ruleResolver))
        .setTarget("Google Inc.:Google APIs:16")
        .setDexSplitMode(new DexSplitMode(
            /* shouldSplitDex */ true,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
            DexStore.JAR,
            /* useLinearAllocSplitDex */ false)));

    Set<String> classpath = Sets.newHashSet();
    ImmutableSet.Builder<String> secondaryDexDirectories = ImmutableSet.builder();
    ImmutableList.Builder<Step> commandsBuilder = ImmutableList.builder();
    String primaryDexPath = BIN_DIR + "/.dex/classes.dex";
    splitDexRule.addDexingSteps(classpath,
        secondaryDexDirectories,
        commandsBuilder,
        primaryDexPath,
        /* sourcePathResolver */ new Function<SourcePath, Path>() {
          @Override
          public Path apply(SourcePath input) {
            throw new UnsupportedOperationException("This resolver should not be used.");
          }
        });

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
            "cp -R /path/to/source /path/to/destination"));
  }

  @Test
  public void testCopyNativeLibraryCommand() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.of(TargetCpuType.ARMV7),
        "/path/to/source",
        "/path/to/destination/",
        ImmutableList.of(
            "[ -d /path/to/source/armeabi-v7a ] && mkdir -p /path/to/destination/armeabi-v7a " +
                "&& cp -R /path/to/source/armeabi-v7a /path/to/destination/armeabi-v7a"));
  }

  @Test
  public void testCopyNativeLibraryCommandWithMultipleCpuFilters() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.of(TargetCpuType.ARM, TargetCpuType.X86),
        "/path/to/source",
        "/path/to/destination/",
        ImmutableList.of(
            "[ -d /path/to/source/armeabi ] && mkdir -p /path/to/destination/armeabi " +
                "&& cp -R /path/to/source/armeabi /path/to/destination/armeabi",
            "[ -d /path/to/source/x86 ] && mkdir -p /path/to/destination/x86 " +
                "&& cp -R /path/to/source/x86 /path/to/destination/x86"));
  }

  @Test
  public void testCreateFilterResourcesStep() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    AndroidBinaryRule.Builder builder = AndroidBinaryRule.newAndroidBinaryRuleBuilder(
        new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:target"))
        .setManifest(new FileSourcePath("AndroidManifest.xml"))
        .setKeystore(addKeystoreRule(resolver))
        .setTarget("Google Inc:Google APIs:16")
        .setResourceFilter(new ResourceFilter(ImmutableList.<String>of("mdpi")))
        .setResourceCompressionMode("enabled_with_strings_as_assets");

    AndroidBinaryRule buildRule = resolver.buildAndAddToIndex(builder);
    Set<String> resourceDirectories = ImmutableSet.of("one", "two");

    FilterResourcesStep filterResourcesStep = buildRule.getUberRDotJavaBuildable()
        .createFilterResourcesStep(resourceDirectories);

    assertEquals(
        ImmutableSet.of(
            "buck-out/bin/__filtered__target#uber_r_dot_java__/0",
            "buck-out/bin/__filtered__target#uber_r_dot_java__/1"),
        filterResourcesStep.getOutputResourceDirs());
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
      public Function<String, Path> getPathRelativizer() {
        return new Function<String, Path>() {

          @Override
          public Path apply(String input) {
            return Paths.get(input);
          }
        };
      }

      @Override
      public Path resolve(Path path) {
        return path;
      }
    }

    // Invoke copyNativeLibrary to populate the steps.
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    AndroidBinaryRule.copyNativeLibrary(sourceDir, destinationDir, cpuFilters, stepsBuilder);
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

  private BuildTarget addKeystoreRule(BuildRuleResolver ruleResolver) {
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    ruleResolver.buildAndAddToIndex(
        Keystore.newKeystoreBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(keystoreTarget)
        .setStore("keystore/debug.keystore")
        .setProperties("keystore/debug.keystore.properties")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));
    return keystoreTarget;
  }
}
