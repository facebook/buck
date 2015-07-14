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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleSdkDiscoveryTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private AppleToolchain getDefaultToolchain(Path path) {
    return AppleToolchain.builder()
        .setIdentifier("com.apple.dt.toolchain.XcodeDefault")
        .setPath(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();
  }

  @Test
  public void shouldReturnAnEmptyMapIfNoPlatformsFound() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-discovery-empty",
        temp);
    workspace.setUp();
    Path path = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        getDefaultToolchain(path)
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        Optional.of(path),
        ImmutableList.<Path>of(),
        toolchains);

    assertEquals(0, sdks.size());
  }

  @Test
  public void shouldFindPlatformsInExtraPlatformDirectories() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-discovery-minimal",
        temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    ProjectWorkspace emptyWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-discovery-empty",
        temp);
    emptyWorkspace.setUp();
    Path path = emptyWorkspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        getDefaultToolchain(path));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.builder().setName(ApplePlatform.Name.MACOSX).build())
            .addArchitectures("i386", "x86_64")
            .addAllToolchains(toolchains.values())
            .build();
    AppleSdkPaths macosx109Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(path)
            .addToolchainPaths(path.resolve("Toolchains/XcodeDefault.xctoolchain"))
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
            Optional.of(path),
            ImmutableList.of(root),
            toolchains),
        equalTo(expected));
  }

  @Test
  public void ignoresInvalidExtraPlatformDirectories() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-discovery-minimal",
        temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    Path path = Paths.get("invalid");

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        getDefaultToolchain(root));

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.builder().setName(ApplePlatform.Name.MACOSX).build())
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
            toolchains),
        equalTo(expected));
  }

  @Test
  public void shouldNotIgnoreSdkWithUnrecognizedPlatform() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-unknown-platform-discovery",
        temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        getDefaultToolchain(root)
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        Optional.of(root),
        ImmutableList.<Path>of(),
        toolchains);

    assertEquals(2, sdks.size());
  }

  @Test
  public void shouldIgnoreSdkWithBadSymlink() throws Exception {
    Path root = temp.newFolder().toPath();

    // Create a dangling symlink
    File toDelete = File.createTempFile("foo", "bar");
    Path symlink = root.resolve("Platforms/Foo.platform/Developer/NonExistent1.0.sdk");
    Files.createDirectories(symlink.getParent());
    Files.createSymbolicLink(symlink, toDelete.toPath());
    assertTrue(toDelete.delete());

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        getDefaultToolchain(root)
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        Optional.of(root),
        ImmutableList.<Path>of(),
        toolchains);

    assertEquals(0, sdks.size());
  }

  @Test
  public void appleSdkPathsBuiltFromDirectory() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-discovery",
        temp);
    workspace.setUp();
    Path root = workspace.getPath("");
    createSymLinkIosSdks(root, "8.0");
    createSymLinkWatchosSdks(root, "2.0");

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.builder().setName(ApplePlatform.Name.MACOSX).build())
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
            .setApplePlatform(ApplePlatform.builder().setName(ApplePlatform.Name.IPHONEOS).build())
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
            .setApplePlatform(
                ApplePlatform.builder().setName(ApplePlatform.Name.IPHONESIMULATOR).build())
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
            .setApplePlatform(ApplePlatform.builder().setName(ApplePlatform.Name.WATCHOS).build())
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
            .setApplePlatform(
                ApplePlatform.builder().setName(ApplePlatform.Name.WATCHSIMULATOR).build())
            .addArchitectures("i386")
            .addToolchains(getDefaultToolchain(root))
            .build();
    AppleSdkPaths watchsimulator20Paths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/WatchSimulator.platform"))
            .setSdkPath(
                root.resolve(
                    "Platforms/WatchSimulator.platform/Developer/SDKs/WatchSimulator.sdk"))
            .build();

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        getDefaultToolchain(root));

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
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.<Path>of(),
            toolchains),
        equalTo(expected));
  }

  @Test
  public void noAppleSdksFoundIfDefaultPlatformMissing() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-discovery",
        temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.<Path>of(),
            toolchains).entrySet(),
        empty());
  }

  @Test
  public void multipleAppleSdkPathsPerPlatformBuiltFromDirectory() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-multi-version-discovery",
        temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    createSymLinkIosSdks(root, "8.1");

    AppleSdk macosx109Sdk =
        AppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.builder().setName(ApplePlatform.Name.MACOSX).build())
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
            .setApplePlatform(ApplePlatform.builder().setName(ApplePlatform.Name.IPHONEOS).build())
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
            .setApplePlatform(
                ApplePlatform.builder().setName(ApplePlatform.Name.IPHONESIMULATOR).build())
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
            .setApplePlatform(ApplePlatform.builder().setName(ApplePlatform.Name.IPHONEOS).build())
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
            .setApplePlatform(
                ApplePlatform.builder().setName(ApplePlatform.Name.IPHONESIMULATOR).build())
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

    ImmutableMap<String, AppleToolchain> toolchains = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        getDefaultToolchain(root));

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(
            Optional.of(root),
            ImmutableList.<Path>of(),
            toolchains),
        equalTo(expected));
  }

  private void createSymLinkIosSdks(Path root, String version) throws IOException {
    createSymLinkSdks(ImmutableSet.of("iPhoneOS", "iPhoneSimulator"), root, version);
  }

  private void createSymLinkWatchosSdks(Path root, String version) throws IOException {
    createSymLinkSdks(ImmutableSet.of("WatchOS", "WatchSimulator"), root, version);
  }

  private void createSymLinkSdks(
      Iterable<String> sdks,
      Path root,
      String version) throws IOException {
    for (String sdk : sdks) {
      Path sdkDir = root.resolve(String.format("Platforms/%s.platform/Developer/SDKs", sdk));

      if (!Files.exists(sdkDir)) {
        continue;
      }

      Path actual = sdkDir.resolve(String.format("%s.sdk", sdk));
      Path link = sdkDir.resolve(String.format("%s%s.sdk", sdk, version));
      Files.createSymbolicLink(link, actual);
    }
  }
}
