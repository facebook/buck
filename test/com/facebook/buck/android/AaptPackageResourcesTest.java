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

import static com.facebook.buck.util.BuckConstant.BIN_DIR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidBinaryRule.PackageType;
import com.facebook.buck.android.AndroidBinaryRule.TargetCpuType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.MorePaths;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

public class AaptPackageResourcesTest {

  /**
   * Tests an android_binary with zero dependent android_library rules that contains an assets
   * directory.
   */
  @Test
  public void testCreateAllAssetsDirectoryWithZeroAssetsDirectories() throws IOException {
    UberRDotJavaBuildable uberRDotJava = EasyMock.createMock(UberRDotJavaBuildable.class);
    EasyMock.replay(uberRDotJava);

    // One android_binary rule that depends on the two android_library rules.
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        new BuildTarget("//java/src/com/facebook/base", "apk", "aapt_package"),
        /* manifest */ new FileSourcePath("java/src/com/facebook/base/AndroidManifest.xml"),
        uberRDotJava,
        PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of());

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) {
        throw new RuntimeException("Unexpected: no assets directories to traverse!");
      }
    };

    // Invoke createAllAssetsDirectory(), the method under test.
    Optional<String> allAssetsDirectory = aaptPackageResources.createAllAssetsDirectory(
        /* assetsDirectories */ ImmutableSet.<String>of(),
        commands,
        traverser);
    EasyMock.verify(uberRDotJava);

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
    AndroidBinaryRuleTest.createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        null, /* assetDirectory */
        null /* nativeLibsDirectory */);
    AndroidBinaryRuleTest.createAndroidLibraryRule(
        "//java/src/com/facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        "java/src/com/facebook/base/assets2",
        null /* nativeLibsDirectory */);
    UberRDotJavaBuildable uberRDotJava = EasyMock.createMock(UberRDotJavaBuildable.class);
    EasyMock.replay(uberRDotJava);

    AndroidResourceRule resourceOne = (AndroidResourceRule) ruleResolver
        .get(BuildTargetFactory.newInstance("//java/src/com/facebook/base:libraryTwo_resources"));

    // One android_binary rule that depends on the two android_library rules.
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        new BuildTarget("//java/src/com/facebook/base", "apk", "aapt_package"),
        /* manifest */ new FileSourcePath("java/src/com/facebook/base/AndroidManifest.xml"),
        uberRDotJava,
        PackageType.DEBUG,
        ImmutableSet.<TargetCpuType>of());

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    Set<String> assetsDirectories = ImmutableSet.of(resourceOne.getAssets());
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) throws IOException {
        String rootPath = MorePaths.newPathInstance(traversal.getRoot()).toString();
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
    Optional<String> allAssetsDirectory = aaptPackageResources.createAllAssetsDirectory(
        assetsDirectories, commands, traverser);
    EasyMock.verify(uberRDotJava);

    // Verify that the existing assets/ directory will be passed to aapt.
    assertTrue(allAssetsDirectory.isPresent());
    assertEquals(
        "Even though there is only one assets directory, the one in " + BIN_DIR + " should be used.",
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
    AndroidBinaryRuleTest.createAndroidLibraryRule(
        "//facebook/base:libraryOne",
        ruleResolver,
        null, /* resDirectory */
        "facebook/base/assets1",
        null /* nativeLibsDirectory */);
    AndroidBinaryRuleTest.createAndroidLibraryRule(
        "//facebook/base:libraryTwo",
        ruleResolver,
        null, /* resDirectory */
        "facebook/base/assets2",
        null /* nativeLibsDirectory */);
    UberRDotJavaBuildable uberRDotJava = EasyMock.createMock(UberRDotJavaBuildable.class);
    EasyMock.replay(uberRDotJava);

    // One android_binary rule that depends on the two android_library rules.
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        new BuildTarget("//facebook/base", "apk", "aapt_package"),
        /* manifest */ new FileSourcePath("facebook/base/AndroidManifest.xml"),
        uberRDotJava,
        PackageType.DEBUG,
        ImmutableSet.<TargetCpuType>of());

    AndroidResourceRule resourceOne = (AndroidResourceRule) ruleResolver.get(
        BuildTargetFactory.newInstance("//facebook/base:libraryOne_resources"));
    AndroidResourceRule resourceTwo = (AndroidResourceRule) ruleResolver.get(
        BuildTargetFactory.newInstance("//facebook/base:libraryTwo_resources"));

    // Build up the parameters needed to invoke createAllAssetsDirectory().
    Set<String> assetsDirectories = ImmutableSet.of(
        resourceOne.getAssets(),
        resourceTwo.getAssets());
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) throws IOException {
        String rootPath = MorePaths.newPathInstance(traversal.getRoot()).toString();
        if ("facebook/base/assets1".equals(rootPath)) {
          traversal.visit(
              new File("facebook/base/assets1",
                  "guava-10.0.1-fork.dex.1.jar"),
              "guava-10.0.1-fork.dex.1.jar");
        } else if ("facebook/base/assets2".equals(rootPath)) {
          traversal.visit(
              new File("facebook/base/assets2",
                  "fonts/Theinhardt-Medium.otf"),
              "fonts/Theinhardt-Medium.otf");
          traversal.visit(
              new File("facebook/base/assets2",
                  "fonts/Theinhardt-Regular.otf"),
              "fonts/Theinhardt-Regular.otf");
        } else {
          throw new RuntimeException("Unexpected path: " + rootPath);
        }
      }
    };

    // Invoke createAllAssetsDirectory(), the method under test.
    Optional<String> allAssetsDirectory = aaptPackageResources.createAllAssetsDirectory(
        assetsDirectories, commands, traverser);
    EasyMock.verify(uberRDotJava);

    // Verify that an assets/ directory will be created and passed to aapt.
    assertTrue(allAssetsDirectory.isPresent());
    assertEquals(BIN_DIR + "/facebook/base/__assets_apk#aapt_package__",
        allAssetsDirectory.get());
    List<? extends Step> expectedCommands = ImmutableList.of(
        new MakeCleanDirectoryStep(BIN_DIR + "/facebook/base/__assets_apk#aapt_package__"),
        new MkdirAndSymlinkFileStep(
            "facebook/base/assets1/guava-10.0.1-fork.dex.1.jar",
            BIN_DIR + "/facebook/base/__assets_apk#aapt_package__/guava-10.0.1-fork.dex.1.jar"),
        new MkdirAndSymlinkFileStep(
            "facebook/base/assets2/fonts/Theinhardt-Medium.otf",
            BIN_DIR + "/facebook/base/__assets_apk#aapt_package__/fonts/Theinhardt-Medium.otf"),
        new MkdirAndSymlinkFileStep(
            "facebook/base/assets2/fonts/Theinhardt-Regular.otf",
            BIN_DIR + "/facebook/base/__assets_apk#aapt_package__/fonts/Theinhardt-Regular.otf"));
    MoreAsserts.assertListEquals(expectedCommands, commands.build());
  }

}
