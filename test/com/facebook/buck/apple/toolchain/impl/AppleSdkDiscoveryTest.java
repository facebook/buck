/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple.toolchain.impl;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AppleSdkDiscoveryTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private AppleToolchain getDefaultToolchain(Path path) {
    return AppleToolchain.builder()
        .setIdentifier("com.apple.dt.toolchain.XcodeDefault")
        .setPath(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();
  }

  @Test
  public void shouldReturnAnEmptyMapIfNoPlatformsFound() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-empty", temp);
    workspace.setUp();
    Path path = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(path));
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(path),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class));

    assertEquals(0, sdks.size());
  }

  @Test
  public void shouldResolveSdkVersionConflicts() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-discovery-conflict", temp.newFolder("conflict"));
    workspace.setUp();
    Path root = workspace.getPath("Platforms");

    ProjectWorkspace emptyWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-discovery-empty", temp.newFolder("empty"));
    emptyWorkspace.setUp();
    Path path = emptyWorkspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(path));

    AppleSdk macosxReleaseSdk =
        AppleSdk.builder()
            .setName("macosx")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addAllToolchains(toolchains.values())
            .build();
    AppleSdk macosxDebugSdk =
        AppleSdk.builder()
            .setName("macosx-Debug")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addAllToolchains(toolchains.values())
            .build();
    AppleSdkPaths macosxReleasePaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(path)
            .addToolchainPaths(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("MacOSX.platform"))
            .setSdkPath(root.resolve("MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();
    AppleSdkPaths macosxDebugPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(path)
            .addToolchainPaths(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("MacOSX.platform"))
            .setSdkPath(root.resolve("MacOSX.platform/Developer/SDKs/MacOSX-Debug10.9.sdk"))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosxReleaseSdk, macosxReleasePaths)
            .put(macosxDebugSdk, macosxDebugPaths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(path),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class)),
        equalTo(expected));
  }

  @Test
  public void shouldFindPlatformsInExtraPlatformDirectories() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-discovery-minimal", temp.newFolder("minimal"));
    workspace.setUp();
    Path root = workspace.getPath("Platforms");

    ProjectWorkspace emptyWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-discovery-empty", temp.newFolder("empty"));
    emptyWorkspace.setUp();
    Path path = emptyWorkspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(path));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addAllToolchains(toolchains.values())
            .build();
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(path)
            .addToolchainPaths(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("MacOSX.platform"))
            .setSdkPath(root.resolve("MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(path),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class)),
        equalTo(expected));
  }

  @Test
  public void ignoresInvalidExtraPlatformDirectories() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-minimal", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    Path path = Paths.get("invalid");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addAllToolchains(toolchains.values())
            .build();
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/MacOSX.platform"))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(path),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class)),
        equalTo(expected));
  }

  @Test
  public void shouldNotIgnoreSdkWithUnrecognizedPlatform() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "sdk-unknown-platform-discovery", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class));

    assertEquals(2, sdks.size());
  }

  @Test
  public void shouldIgnoreSdkWithBadSymlink() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-symlink", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    Path sdksDir = root.resolve("Platforms/MacOSX.platform/Developer/SDKs");
    Files.createDirectories(sdksDir);

    // Create a dangling symlink
    File toDelete = File.createTempFile("foo", "bar");
    Path symlink = sdksDir.resolve("NonExistent1.0.sdk");
    CreateSymlinksForTests.createSymLink(symlink, toDelete.toPath());
    assertTrue(toDelete.delete());

    // Also create a working symlink
    Path actualSdkPath = root.resolve("MacOSX10.9.sdk");
    CreateSymlinksForTests.createSymLink(sdksDir.resolve("MacOSX10.9.sdk"), actualSdkPath);

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addAllToolchains(toolchains.values())
            .build();
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/MacOSX.platform"))
            .setSdkPath(actualSdkPath)
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> discoveredSdks =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class));

    assertThat(discoveredSdks, equalTo(expected));
  }

  @Test
  public void appleSdkPathsBuiltFromDirectory() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery", temp);
    workspace.setUp();
    Path root = workspace.getPath("");
    createSymLinkIosSdks(root, "8.0");
    createSymLinkWatchosSdks(root, "2.0");
    createSymLinkAppletvosSdks(root, "9.1");

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/MacOSX.platform"))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    AppleSdk iphoneos80Sdk =
        AppleSdk.builder()
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths iphoneos80Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .build();

    AppleSdk iphonesimulator80Sdk =
        AppleSdk.builder()
            .setName("iphonesimulator8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths iphonesimulator80Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/iPhoneSimulator.platform"))
            .setSdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"))
            .build();

    AppleSdk watchos20Sdk =
        AppleSdk.builder()
            .setName("watchos2.0")
            .setVersion("2.0")
            .setApplePlatform(ApplePlatform.WATCHOS)
            .addArchitectures("armv7k")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths watchos20Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/WatchOS.platform"))
            .setSdkPath(root.resolve("Platforms/WatchOS.platform/Developer/SDKs/WatchOS.sdk"))
            .build();

    AppleSdk watchsimulator20Sdk =
        AppleSdk.builder()
            .setName("watchsimulator2.0")
            .setVersion("2.0")
            .setApplePlatform(ApplePlatform.WATCHSIMULATOR)
            .addArchitectures("i386")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths watchsimulator20Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/WatchSimulator.platform"))
            .setSdkPath(
                root.resolve("Platforms/WatchSimulator.platform/Developer/SDKs/WatchSimulator.sdk"))
            .build();

    AppleSdk appletvos91Sdk =
        AppleSdk.builder()
            .setName("appletvos9.1")
            .setVersion("9.1")
            .setApplePlatform(ApplePlatform.APPLETVOS)
            .addArchitectures("arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths appletvos91Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/AppleTVOS.platform"))
            .setSdkPath(root.resolve("Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS.sdk"))
            .build();

    AppleSdk appletvsimulator91Sdk =
        AppleSdk.builder()
            .setName("appletvsimulator9.1")
            .setVersion("9.1")
            .setApplePlatform(ApplePlatform.APPLETVSIMULATOR)
            .addArchitectures("x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths appletvsimulator91Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/AppleTVSimulator.platform"))
            .setSdkPath(
                root.resolve(
                    "Platforms/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator.sdk"))
            .build();

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .put(iphoneos80Sdk, iphoneos80Paths)
            .put(iphoneos80Sdk.withName("iphoneos"), iphoneos80Paths)
            .put(iphonesimulator80Sdk, iphonesimulator80Paths)
            .put(iphonesimulator80Sdk.withName("iphonesimulator"), iphonesimulator80Paths)
            .put(watchos20Sdk, watchos20Paths)
            .put(watchos20Sdk.withName("watchos"), watchos20Paths)
            .put(watchsimulator20Sdk, watchsimulator20Paths)
            .put(watchsimulator20Sdk.withName("watchsimulator"), watchsimulator20Paths)
            .put(appletvos91Sdk, appletvos91Paths)
            .put(appletvos91Sdk.withName("appletvos"), appletvos91Paths)
            .put(appletvsimulator91Sdk, appletvsimulator91Paths)
            .put(appletvsimulator91Sdk.withName("appletvsimulator"), appletvsimulator91Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class)),
        equalTo(expected));
  }

  @Test
  public void noAppleSdksFoundIfDefaultPlatformMissing() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
                Optional.of(root),
                ImmutableList.of(),
                toolchains,
                FakeBuckConfig.builder().build().getView(AppleConfig.class))
            .entrySet(),
        empty());
  }

  @Test
  public void multipleAppleSdkPathsPerPlatformBuiltFromDirectory() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-multi-version-discovery", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    createSymLinkIosSdks(root, "8.1");

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/MacOSX.platform"))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    AppleSdk iphoneos80Sdk =
        AppleSdk.builder()
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths iphoneos80Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleSdk iphonesimulator80Sdk =
        AppleSdk.builder()
            .setName("iphonesimulator8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths iphonesimulator80Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/iPhoneSimulator.platform"))
            .setSdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
            .build();

    AppleSdk iphoneos81Sdk =
        AppleSdk.builder()
            .setName("iphoneos8.1")
            .setVersion("8.1")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths iphoneos81Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .build();

    AppleSdk iphonesimulator81Sdk =
        AppleSdk.builder()
            .setName("iphonesimulator8.1")
            .setVersion("8.1")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths iphonesimulator81Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/iPhoneSimulator.platform"))
            .setSdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .put(iphoneos80Sdk, iphoneos80Paths)
            .put(iphonesimulator80Sdk, iphonesimulator80Paths)
            .put(iphoneos81Sdk, iphoneos81Paths)
            .put(iphoneos81Sdk.withName("iphoneos"), iphoneos81Paths)
            .put(iphonesimulator81Sdk, iphonesimulator81Paths)
            .put(iphonesimulator81Sdk.withName("iphonesimulator"), iphonesimulator81Paths)
            .build();

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class)),
        equalTo(expected));
  }

  @Test
  public void shouldDiscoverRealSdkThroughAbsoluteSymlink() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-symlink", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    Path actualSdkPath = root.resolve("MacOSX10.9.sdk");
    Path sdksDir = root.resolve("Platforms/MacOSX.platform/Developer/SDKs");

    Files.createDirectories(sdksDir);
    CreateSymlinksForTests.createSymLink(sdksDir.resolve("MacOSX10.9.sdk"), actualSdkPath);

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addAllToolchains(toolchains.values())
            .build();
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/MacOSX.platform"))
            .setSdkPath(actualSdkPath)
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class)),
        equalTo(expected));
  }

  @Test
  public void shouldScanRealDirectoryOnlyOnce() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-symlink", temp);
    workspace.setUp();
    Path root = workspace.getPath("");
    FileSystem fileSystem = root.getFileSystem();

    Path actualSdkPath = root.resolve("MacOSX10.9.sdk");
    Path sdksDir = root.resolve("Platforms/MacOSX.platform/Developer/SDKs");
    Files.createDirectories(sdksDir);

    // create relative symlink

    CreateSymlinksForTests.createSymLink(
        sdksDir.resolve("MacOSX10.9.sdk"), fileSystem.getPath("MacOSX.sdk"));

    // create absolute symlink
    CreateSymlinksForTests.createSymLink(sdksDir.resolve("MacOSX.sdk"), actualSdkPath);

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    ImmutableMap<AppleSdk, AppleSdkPaths> actual =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class));

    // if both symlinks were to be visited, exception would have been thrown during discovery
    assertThat(actual.size(), is(2));
  }

  @Test
  public void shouldNotCrashOnBrokenSymlink() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-symlink", temp);
    workspace.setUp();
    Path root = workspace.getPath("");
    FileSystem fileSystem = root.getFileSystem();

    Path sdksDir = root.resolve("Platforms/MacOSX.platform/Developer/SDKs");
    Files.createDirectories(sdksDir);
    CreateSymlinksForTests.createSymLink(
        sdksDir.resolve("MacOSX.sdk"), fileSystem.getPath("does_not_exist"));

    ImmutableMap<String, AppleToolchain> toolchains =
        ImmutableMap.of("com.apple.dt.toolchain.XcodeDefault", getDefaultToolchain(root));

    ImmutableMap<AppleSdk, AppleSdkPaths> actual =
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.of(root),
            toolchains,
            FakeBuckConfig.builder().build().getView(AppleConfig.class));

    assertThat(actual.size(), is(0));
  }

  @Test
  public void overrideToolchains() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sdk-discovery-minimal", temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    String toolchainName1 = "toolchainoverride.1";
    String toolchainPath1 = "Toolchains/" + toolchainName1;
    AppleToolchain overrideToolchain1 =
        AppleToolchain.builder()
            .setIdentifier(toolchainName1)
            .setPath(root.resolve(toolchainPath1))
            .setVersion("1")
            .build();

    String toolchainName2 = "toolchainoverride.2";
    String toolchainPath2 = "Toolchains/" + toolchainName2;
    AppleToolchain overrideToolchain2 =
        AppleToolchain.builder()
            .setIdentifier(toolchainName2)
            .setPath(root.resolve(toolchainPath2))
            .setVersion("1")
            .build();

    ImmutableMap<String, AppleToolchain> allToolchains =
        ImmutableMap.of(
            "com.apple.dt.toolchain.XcodeDefault",
            getDefaultToolchain(root),
            toolchainName1,
            overrideToolchain1,
            toolchainName2,
            overrideToolchain2);

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .addAllToolchains(ImmutableList.of(overrideToolchain1, overrideToolchain2))
            .build();
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve(toolchainPath1), root.resolve(toolchainPath2))
            .setPlatformPath(root.resolve("Platforms/MacOSX.platform"))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .build();

    AppleConfig fakeAppleConfig =
        FakeBuckConfig.builder()
            .setSections(
                "[apple]",
                "  macosx10.9_toolchains_override = " + toolchainName1 + "," + toolchainName2,
                "  macosx_toolchains_override = " + toolchainName1 + "," + toolchainName2)
            .build()
            .getView(AppleConfig.class);

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root), ImmutableList.of(root), allToolchains, fakeAppleConfig),
        equalTo(expected));
  }

  private void createSymLinkIosSdks(Path root, String version) throws IOException {
    createSymLinkSdks(ImmutableSet.of("iPhoneOS", "iPhoneSimulator"), root, version);
  }

  private void createSymLinkWatchosSdks(Path root, String version) throws IOException {
    createSymLinkSdks(ImmutableSet.of("WatchOS", "WatchSimulator"), root, version);
  }

  private void createSymLinkAppletvosSdks(Path root, String version) throws IOException {
    createSymLinkSdks(ImmutableSet.of("AppleTVOS", "AppleTVSimulator"), root, version);
  }

  private void createSymLinkSdks(Iterable<String> sdks, Path root, String version)
      throws IOException {
    for (String sdk : sdks) {
      Path sdkDir = root.resolve(String.format("Platforms/%s.platform/Developer/SDKs", sdk));

      if (!Files.exists(sdkDir)) {
        System.out.println(sdkDir);
        continue;
      }

      Path actual = sdkDir.resolve(String.format("%s.sdk", sdk));
      Path link = sdkDir.resolve(String.format("%s%s.sdk", sdk, version));
      CreateSymlinksForTests.createSymLink(link, actual);
    }
  }
}
