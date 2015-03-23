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

import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static com.facebook.buck.util.BuckConstant.SCRATCH_DIR;
import static com.facebook.buck.util.BuckConstant.SCRATCH_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class AaptPackageResourcesTest {

  /**
   * Tests an android_binary with zero dependent android_library rules that contains an assets
   * directory.
   */
  @Test
  public void testCreateAllAssetsDirectoryWithZeroAssetsDirectories() throws IOException {
    ResourcesFilter resourcesFilter = EasyMock.createMock(ResourcesFilter.class);
    EasyMock.replay(resourcesFilter);

    BuildRuleParams params = new FakeBuildRuleParamsBuilder(
        BuildTarget.builder("//java/src/com/facebook/base", "apk")
            .addFlavors(ImmutableFlavor.of("aapt_package"))
            .build())
        .build();

    // One android_binary rule that depends on the two android_library rules.
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        params,
        new SourcePathResolver(new BuildRuleResolver()),
        /* manifest */ new TestSourcePath("java/src/com/facebook/base/AndroidManifest.xml"),
        resourcesFilter,
        ImmutableList.<HasAndroidResourceDeps>of(),
        ImmutableSet.<Path>of(),
        PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of(),
        DEFAULT_JAVAC_OPTIONS,
        /* rDotJavaNeedsDexing */ false,
        /* shouldBuildStringSourceMap */ false,
        /* shouldWarnIfMissingResources */ false,
        /* skipCrunchPngs */ false);

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Invoke createAllAssetsDirectory(), the method under test.
    Optional<Path> allAssetsDirectory = aaptPackageResources.createAllAssetsDirectory(
        /* assetsDirectories */ ImmutableSet.<Path>of(),
        commands,
        new FakeProjectFilesystem());
    EasyMock.verify(resourcesFilter);

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
  public void testCreateAllAssetsDirectoryWithOneAssetsDirectory() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Two android_library deps, one of which has an assets directory.
    AndroidBinaryTest.createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    AndroidBinaryTest.createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        "java/src/com/facebook/base/assets2",
        null /* nativeLibsDirectory */);
    ResourcesFilter resourcesFilter = EasyMock.createMock(ResourcesFilter.class);
    EasyMock.replay(resourcesFilter);

    AndroidResource resourceOne = (AndroidResource) ruleResolver.getRule(
        BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryTwo_resources"));

    BuildRuleParams params = new FakeBuildRuleParamsBuilder(
        BuildTarget.builder("//java/src/com/facebook/base", "apk")
            .addFlavors(ImmutableFlavor.of("aapt_package"))
            .build())
        .build();
    // One android_binary rule that depends on the two android_library rules.
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        params,
        new SourcePathResolver(ruleResolver),
        /* manifest */ new TestSourcePath("java/src/com/facebook/base/AndroidManifest.xml"),
        resourcesFilter,
        ImmutableList.<HasAndroidResourceDeps>of(),
        ImmutableSet.<Path>of(),
        PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of(),
        DEFAULT_JAVAC_OPTIONS,
        /* rDotJavaNeedsDexing */ false,
        /* shouldBuildStringSourceMap */ false,
        /* shouldWarnIfMissingResources */ false,
        /* skipCrunchPngs */ false);

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    Set<Path> assetsDirectories = ImmutableSet.of(resourceOne.getAssets());
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("java/src/com/facebook/base/assets2/fonts/Theinhardt-Medium.otf"));
    filesystem.touch(Paths.get("java/src/com/facebook/base/assets2/fonts/Theinhardt-Regular.otf"));

    // Some special files should be ignored.
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/.svn"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/.git"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/.DS_Store"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/Something.scc"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/CVS"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/thumbs.db"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/picasa.ini"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/something~"));

    // Invoke createAllAssetsDirectory(), the method under test.
    Optional<Path> allAssetsDirectory = aaptPackageResources.createAllAssetsDirectory(
        assetsDirectories, commands, filesystem);
    EasyMock.verify(resourcesFilter);

    // Verify that the existing assets/ directory will be passed to aapt.
    assertTrue(allAssetsDirectory.isPresent());
    assertEquals(
        "Even though there is only one assets directory, the one in " +
            SCRATCH_DIR +
            " should be used.",
        aaptPackageResources.getPathToAllAssetsDirectory(),
        allAssetsDirectory.get());
  }

  /**
   * Tests an android_binary with multiple dependent android_library rules, each with its own assets
   * directory.
   */
  @Test
  public void testCreateAllAssetsDirectoryWithMultipleAssetsDirectories() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Two android_library deps, each with an assets directory.
    AndroidBinaryTest.createAndroidLibraryRule(
        "//facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        "facebook/base/assets1",
        null /* nativeLibsDirectory */);
    AndroidBinaryTest.createAndroidLibraryRule(
        "//facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        "facebook/base/assets2",
        null /* nativeLibsDirectory */);
    ResourcesFilter resourcesFilter = EasyMock.createMock(ResourcesFilter.class);
    EasyMock.replay(resourcesFilter);

    BuildRuleParams params = new FakeBuildRuleParamsBuilder(
        BuildTarget
            .builder("//facebook/base", "apk")
            .addFlavors(ImmutableFlavor.of("aapt_package"))
            .build())
        .build();
    // One android_binary rule that depends on the two android_library rules.
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        params,
        new SourcePathResolver(ruleResolver),
        /* manifest */ new TestSourcePath("facebook/base/AndroidManifest.xml"),
        resourcesFilter,
        ImmutableList.<HasAndroidResourceDeps>of(),
        ImmutableSet.<Path>of(),
        PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of(),
        DEFAULT_JAVAC_OPTIONS,
        /* rDotJavaNeedsDexing */ false,
        /* shouldBuildStringSourceMap */ false,
        /* shouldWarnIfMissingResources */ false,
        /* skipCrunchPngs */ false);

    AndroidResource resourceOne = (AndroidResource) ruleResolver.getRule(
        BuildTargetFactory.newInstance("//facebook/base:libraryOne_resources"));
    AndroidResource resourceTwo = (AndroidResource) ruleResolver.getRule(
        BuildTargetFactory.newInstance("//facebook/base:libraryTwo_resources"));

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    Set<Path> assetsDirectories = ImmutableSet.of(
        resourceOne.getAssets(),
        resourceTwo.getAssets());
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("facebook/base/assets1/guava-10.0.1-fork.dex.1.jar"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/Theinhardt-Medium.otf"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/Theinhardt-Regular.otf"));

    // Some special files should be ignored and shouldn't cause conflicts.
    filesystem.touch(Paths.get("facebook/base/assets1/fonts/.svn"));
    filesystem.touch(Paths.get("facebook/base/assets1/fonts/.git"));
    filesystem.touch(Paths.get("facebook/base/assets1/fonts/.DS_Store"));
    filesystem.touch(Paths.get("facebook/base/assets1/fonts/Something.scc"));
    filesystem.touch(Paths.get("facebook/base/assets1/fonts/CVS"));
    filesystem.touch(Paths.get("facebook/base/assets1/fonts/thumbs.db"));
    filesystem.touch(Paths.get("facebook/base/assets1/fonts/picasa.ini"));
    filesystem.touch(Paths.get("facebook/base/assets1/fonts/something~"));

    filesystem.touch(Paths.get("facebook/base/assets2/fonts/.svn"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/.git"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/.DS_Store"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/Something.scc"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/CVS"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/thumbs.db"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/picasa.ini"));
    filesystem.touch(Paths.get("facebook/base/assets2/fonts/something~"));

    // Invoke createAllAssetsDirectory(), the method under test.
    Optional<Path> allAssetsDirectory = aaptPackageResources.createAllAssetsDirectory(
        assetsDirectories, commands, filesystem);
    EasyMock.verify(resourcesFilter);

    // Verify that an assets/ directory will be created and passed to aapt.
    assertTrue(allAssetsDirectory.isPresent());
    assertEquals(
        SCRATCH_PATH.resolve("facebook/base/__assets_apk#aapt_package__"),
        allAssetsDirectory.get());

    List<? extends Step> observedCommands = commands.build();
    assertEquals(4, observedCommands.size());
    assertEquals(
        new MakeCleanDirectoryStep(
            SCRATCH_PATH.resolve("facebook/base/__assets_apk#aapt_package__")),
        observedCommands.get(0));

    ImmutableSet<Step> remainingCommands = ImmutableSet.copyOf(observedCommands.subList(1, 4));
    MoreAsserts.assertSetEquals(
        ImmutableSet.<Step>of(
            new MkdirAndSymlinkFileStep(
                Paths.get("facebook/base/assets1/guava-10.0.1-fork.dex.1.jar"),
                SCRATCH_PATH.resolve(
                    "facebook/base/__assets_apk#aapt_package__/guava-10.0.1-fork.dex.1.jar")),
            new MkdirAndSymlinkFileStep(
                Paths.get("facebook/base/assets2/fonts/Theinhardt-Medium.otf"),
                SCRATCH_PATH.resolve(
                    "facebook/base/__assets_apk#aapt_package__/fonts/Theinhardt-Medium.otf")),
            new MkdirAndSymlinkFileStep(
                Paths.get("facebook/base/assets2/fonts/Theinhardt-Regular.otf"),
                SCRATCH_PATH.resolve(
                    "facebook/base/__assets_apk#aapt_package__/fonts/Theinhardt-Regular.otf"))),
        remainingCommands);
  }
}
