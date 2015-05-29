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

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Unit tests for {@link AppleCxxPlatforms}.
 */
public class AppleCxxPlatformsTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void appleSdkPathsBuiltFromDirectory() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.IPHONEOS).build())
        .setName("iphoneos8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/xctest"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            new FakeBuckConfig(),
            new FakeExecutableFinder(paths));

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());

    assertEquals(
        ImmutableList.of("usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Tools/otest"),
        appleCxxPlatform.getOtest().getCommandPrefix(resolver));

    assertEquals(
        ImmutableFlavor.of("iphoneos8.0-armv7"),
        cxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        cxxPlatform.getCc().getCommandPrefix(resolver).get(0));
    assertThat(
        cxxPlatform.getCc().getCommandPrefix(resolver),
        hasConsecutiveItems(
            "-isysroot",
            Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk").toString()));
    assertThat(
        cxxPlatform.getCc().getCommandPrefix(resolver),
        hasConsecutiveItems("-arch", "armv7"));
    assertThat(
        cxxPlatform.getCc().getCommandPrefix(resolver),
        hasConsecutiveItems("-mios-version-min=7.0"));

    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar")
            .toString(),
        cxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }

  @Test
  public void invalidFlavorCharactersInSdkAreEscaped() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/xctest"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.IPHONEOS).build())
        .setName("_(in)+va|id_")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "cha+rs",
            appleSdkPaths,
            new FakeBuckConfig(),
            new FakeExecutableFinder(paths));

    assertEquals(
        ImmutableFlavor.of("__in__va_id_-cha_rs"),
        appleCxxPlatform.getCxxPlatform().getFlavor());
  }

  @Test
  public void cxxToolParamsReadFromBuckConfig() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/xctest"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.IPHONEOS).build())
        .setName("iphoneos8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            new FakeBuckConfig(
                ImmutableMap.of(
                    "cxx", ImmutableMap.of(
                        "cflags", "-std=gnu11",
                        "cppflags", "-DCTHING",
                        "cxxflags", "-std=c++11",
                        "cxxppflags", "-DCXXTHING"))),
            new FakeExecutableFinder(paths));

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-std=gnu11"));
    assertThat(
        cxxPlatform.getCppflags(),
        hasItems("-std=gnu11", "-DCTHING"));
    assertThat(
        cxxPlatform.getCxxflags(),
        hasItem("-std=c++11"));
    assertThat(
        cxxPlatform.getCxxppflags(),
        hasItems("-std=c++11", "-DCXXTHING"));
  }

  @Test
  public void pathNotFoundThrows() throws Exception {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("Cannot find tool"));
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.IPHONEOS).build())
        .setName("iphoneos8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatforms.buildWithExecutableChecker(
        targetSdk,
        "7.0",
        "armv7",
        appleSdkPaths,
        new FakeBuckConfig(),
        new FakeExecutableFinder(ImmutableSet.<Path>of()));
  }

  @Test
  public void simulatorPlatformSetsLinkerFlags() throws Exception {
    AppleSdkPaths appleSdkPaths = AppleSdkPaths.builder()
        .setDeveloperPath(Paths.get("."))
        .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
        .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/xctest"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.IPHONESIMULATOR).build())
        .setName("iphonesimulator8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            new FakeBuckConfig(),
            new FakeExecutableFinder(paths));

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    assertThat(
        cxxPlatform.getCc().getCommandPrefix(resolver),
        hasItem("-mios-simulator-version-min=7.0"));
    assertThat(
        cxxPlatform.getCxxld().getCommandPrefix(resolver),
        hasItem("-mios-simulator-version-min=7.0"));
  }
}
