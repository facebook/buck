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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class AppleSdkDiscoveryTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  @Test
  public void shouldReturnAnEmptyMapIfNoPlatformsFound() throws IOException {
    Path path = temp.newFolder().toPath().toAbsolutePath();

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        path.resolve("Toolchains/XcodeDefault")
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        path,
        toolchainPaths);

    assertEquals(0, sdks.size());
  }

  @Test
  public void shouldIgnoreSdkWithUnrecognizedPlatform() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "sdk-unknown-platform-discovery",
        temp);
    workspace.setUp();
    Path root = workspace.getPath("");

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        root.resolve("Toolchains/XcodeDefault")
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        root,
        toolchainPaths);

    assertEquals(0, sdks.size());
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

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        root.resolve("Toolchains/XcodeDefault")
    );
    ImmutableMap<AppleSdk, AppleSdkPaths> sdks = AppleSdkDiscovery.discoverAppleSdkPaths(
        root,
        toolchainPaths);

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

    ImmutableAppleSdk macosx109Sdk =
        ImmutableAppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .build();
    ImmutableAppleSdkPaths macosx109Paths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformDeveloperPath(root.resolve("Platforms/MacOSX.platform/Developer"))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    ImmutableAppleSdk iphoneos80Sdk =
        ImmutableAppleSdk.builder()
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains("com.apple.dt.toolchain.iOS8_0")
            .build();
    ImmutableAppleSdkPaths iphoneos80Paths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .build();

    ImmutableAppleSdk iphonesimulator80Sdk =
        ImmutableAppleSdk.builder()
            .setName("iphonesimulator8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains("com.apple.dt.toolchain.iOS8_0")
            .build();
    ImmutableAppleSdkPaths iphonesimulator80Paths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformDeveloperPath(root.resolve("Platforms/iPhoneSimulator.platform/Developer"))
            .setSdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"))
            .build();

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        root.resolve("Toolchains/XcodeDefault.xctoolchain"));

    ImmutableMap<AppleSdk, AppleSdkPaths> expected =
        ImmutableMap.<AppleSdk, AppleSdkPaths>builder()
            .put(macosx109Sdk, macosx109Paths)
            .put(macosx109Sdk.withName("macosx"), macosx109Paths)
            .put(iphoneos80Sdk, iphoneos80Paths)
            .put(iphoneos80Sdk.withName("iphoneos"), iphoneos80Paths)
            .put(iphonesimulator80Sdk, iphonesimulator80Paths)
            .put(iphonesimulator80Sdk.withName("iphonesimulator"), iphonesimulator80Paths)
            .build();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(root, toolchainPaths),
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

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of();

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(root, toolchainPaths).entrySet(),
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

    ImmutableAppleSdk macosx109Sdk =
        ImmutableAppleSdk.builder()
            .setName("macosx10.9")
            .setVersion("10.9")
            .setApplePlatform(ApplePlatform.MACOSX)
            .addArchitectures("i386", "x86_64")
            .build();
    ImmutableAppleSdkPaths macosx109Paths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformDeveloperPath(root.resolve("Platforms/MacOSX.platform/Developer"))
            .setSdkPath(root.resolve("Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk"))
            .build();

    ImmutableAppleSdk iphoneos80Sdk =
        ImmutableAppleSdk.builder()
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains("com.apple.dt.toolchain.iOS8_0")
            .build();
    ImmutableAppleSdkPaths iphoneos80Paths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableAppleSdk iphonesimulator80Sdk =
        ImmutableAppleSdk.builder()
            .setName("iphonesimulator8.0")
            .setVersion("8.0")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains("com.apple.dt.toolchain.iOS8_0")
            .build();
    ImmutableAppleSdkPaths iphonesimulator80Paths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformDeveloperPath(root.resolve("Platforms/iPhoneSimulator.platform/Developer"))
            .setSdkPath(
                root.resolve(
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
            .build();

    ImmutableAppleSdk iphoneos81Sdk =
        ImmutableAppleSdk.builder()
            .setName("iphoneos8.1")
            .setVersion("8.1")
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .addArchitectures("armv7", "arm64")
            .addToolchains("com.apple.dt.toolchain.iOS8_1")
            .build();
    ImmutableAppleSdkPaths iphoneos81Paths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformDeveloperPath(root.resolve("Platforms/iPhoneOS.platform/Developer"))
            .setSdkPath(root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .build();

    ImmutableAppleSdk iphonesimulator81Sdk =
        ImmutableAppleSdk.builder()
            .setName("iphonesimulator8.1")
            .setVersion("8.1")
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .addArchitectures("i386", "x86_64")
            .addToolchains("com.apple.dt.toolchain.iOS8_1")
            .build();
    ImmutableAppleSdkPaths iphonesimulator81Paths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformDeveloperPath(root.resolve("Platforms/iPhoneSimulator.platform/Developer"))
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

    ImmutableMap<String, Path> toolchainPaths = ImmutableMap.of(
        "com.apple.dt.toolchain.XcodeDefault",
        root.resolve("Toolchains/XcodeDefault.xctoolchain"));

    assertThat(
        AppleSdkDiscovery.discoverAppleSdkPaths(root, toolchainPaths),
        equalTo(expected));
  }

  private void createSymLinkIosSdks(Path root, String version) throws IOException {
    Set<String> sdks = ImmutableSet.of("iPhoneOS", "iPhoneSimulator");
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
