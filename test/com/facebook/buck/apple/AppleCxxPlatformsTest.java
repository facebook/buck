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
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

/**
 * Unit tests for {@link AppleCxxPlatforms}.
 */
public class AppleCxxPlatformsTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void iphoneOSSdkPathsBuiltFromDirectory() throws Exception {
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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
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
    assertThat(appleCxxPlatform.getOtest().isPresent(), is(true));
    assertEquals(
        ImmutableList.of("Tools/otest"),
        appleCxxPlatform.getOtest().get().getCommandPrefix(resolver));

    assertEquals(
        ImmutableFlavor.of("iphoneos8.0-armv7"),
        cxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        cxxPlatform.getCc().getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot",
            Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk").toString()));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-arch", "armv7"));
    assertThat(
        cxxPlatform.getAsflags(),
        hasConsecutiveItems("-arch", "armv7"));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-mios-version-min=7.0"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasConsecutiveItems(
            "-sdk_version",
            "8.0"));
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar")
            .toString(),
        cxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }

  @Test
  public void watchOSSdkPathsBuiltFromDirectory() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/WatchOS.platform"))
            .setSdkPath(Paths.get("Platforms/WatchOS.platform/Developer/SDKs/WatchOS2.0.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.WATCHOS).build())
        .setName("watchos2.0")
        .setVersion("2.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/xctest"))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "2.0",
            "armv7k",
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
        ImmutableFlavor.of("watchos2.0-armv7k"),
        cxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        cxxPlatform.getCc().getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot",
            Paths.get("Platforms/WatchOS.platform/Developer/SDKs/WatchOS2.0.sdk").toString()));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-arch", "armv7k"));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-mwatchos-version-min=2.0"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasConsecutiveItems(
            "-sdk_version",
            "2.0"));
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/ar")
            .toString(),
        cxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }

  @Test
  public void platformWithoutOtestIsValid() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS9.0.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.IPHONEOS).build())
        .setName("iphoneos9.0")
        .setVersion("9.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/xctest"))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            new FakeBuckConfig(),
            new FakeExecutableFinder(paths));

    assertThat(appleCxxPlatform.getOtest().isPresent(), is(false));
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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
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
  public void cxxToolParamsReadFromBuckConfigWithGenFlavor() throws Exception {
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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
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


    Flavor testFlavor = ImmutableFlavor.of("test-flavor");

    BuckConfig config = new FakeBuckConfig(
                ImmutableMap.of(
                    "cxx", ImmutableMap.of(
                        "cflags", "-std=gnu11",
                        "cppflags", "-DCTHING",
                        "cxxflags", "-std=c++11",
                        "cxxppflags", "-DCXXTHING"),
                    "cxx#" + testFlavor.toString(), ImmutableMap.of(
                        "cflags", "-Wnoerror",
                        "cppflags", "-DCTHING2",
                        "cxxflags", "-Woption",
                        "cxxppflags", "-DCXXTHING2",
                        "default_platform", "iphoneos8.0-armv7")));

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            config,
            new FakeExecutableFinder(paths));

    CxxPlatform defaultCxxPlatform = appleCxxPlatform.getCxxPlatform();

    CxxPlatform cxxPlatform = CxxPlatforms.copyPlatformWithFlavorAndConfig(
        defaultCxxPlatform,
        new CxxBuckConfig(config, testFlavor),
        testFlavor);

    assertThat(
        cxxPlatform.getCflags(),
        hasItems("-std=gnu11", "-Wnoerror"));
    assertThat(
        cxxPlatform.getCppflags(),
        hasItems("-std=gnu11",  "-Wnoerror", "-DCTHING", "-DCTHING2"));
    assertThat(
        cxxPlatform.getCxxflags(),
        hasItems("-std=c++11", "-Woption"));
    assertThat(
        cxxPlatform.getCxxppflags(),
        hasItems("-std=c++11", "-Woption", "-DCXXTHING", "-DCXXTHING2"));
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
  public void iphoneOSSimulatorPlatformSetsLinkerFlags() throws Exception {
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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
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

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-mios-simulator-version-min=7.0"));
    assertThat(
        cxxPlatform.getCxxldflags(),
        hasItem("-mios-simulator-version-min=7.0"));
  }

  @Test
  public void watchOSSimulatorPlatformSetsLinkerFlags() throws Exception {
    AppleSdkPaths appleSdkPaths = AppleSdkPaths.builder()
        .setDeveloperPath(Paths.get("."))
        .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setPlatformPath(Paths.get("Platforms/WatchSimulator.platform"))
        .setSdkPath(
            Paths.get("Platforms/WatchSimulator.platform/Developer/SDKs/WatchSimulator2.0.sdk")
        )
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/xctest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.WATCHSIMULATOR).build())
        .setName("watchsimulator2.0")
        .setVersion("2.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "2.0",
            "armv7k",
            appleSdkPaths,
            new FakeBuckConfig(),
            new FakeExecutableFinder(paths));

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-mwatchos-simulator-version-min=2.0"));
    assertThat(
        cxxPlatform.getCxxldflags(),
        hasItem("-mwatchos-simulator-version-min=2.0"));
  }

  enum Operation {
    PREPROCESS,
    COMPILE,
    PREPROCESS_AND_COMPILE,
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<Flavor, RuleKey> constructCompileRuleKeys(
      Operation operation,
      ImmutableMap<Flavor, AppleCxxPlatform> cxxPlatforms) {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    String source = "source.cpp";
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("source.cpp", Strings.repeat("a", 40))
                    .build()),
            pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<Flavor, RuleKey> ruleKeys =
        ImmutableMap.builder();
    for (Map.Entry<Flavor, AppleCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxSourceRuleFactory cxxSourceRuleFactory =
          new CxxSourceRuleFactory(
              BuildRuleParamsFactory.createTrivialBuildRuleParams(target),
              resolver,
              pathResolver,
              entry.getValue().getCxxPlatform(),
              ImmutableList.<CxxPreprocessorInput>of(),
              ImmutableList.<String>of());
      CxxPreprocessAndCompile rule;
      switch (operation) {
        case PREPROCESS_AND_COMPILE:
          rule =
              cxxSourceRuleFactory.createPreprocessAndCompileBuildRule(
                  resolver,
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new TestSourcePath(source),
                      ImmutableList.<String>of()),
                  CxxSourceRuleFactory.PicType.PIC,
                  CxxPreprocessMode.COMBINED);
          break;
        case PREPROCESS:
          rule =
              cxxSourceRuleFactory.createPreprocessBuildRule(
                  resolver,
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new TestSourcePath(source),
                      ImmutableList.<String>of()),
                  CxxSourceRuleFactory.PicType.PIC);
          break;
        case COMPILE:
          rule =
              cxxSourceRuleFactory.createCompileBuildRule(
                  resolver,
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX_CPP_OUTPUT,
                      new TestSourcePath(source),
                      ImmutableList.<String>of()),
                  CxxSourceRuleFactory.PicType.PIC);
          break;
        default:
          throw new IllegalStateException();
      }
      RuleKey.Builder builder = ruleKeyBuilderFactory.newInstance(rule);
      ruleKeys.put(entry.getKey(), builder.build());
    }
    return ruleKeys.build();
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<Flavor, RuleKey> constructLinkRuleKeys(
      ImmutableMap<Flavor, AppleCxxPlatform> cxxPlatforms) {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("input.o", Strings.repeat("a", 40))
                    .build()),
            pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<Flavor, RuleKey> ruleKeys =
        ImmutableMap.builder();
    for (Map.Entry<Flavor, AppleCxxPlatform> entry : cxxPlatforms.entrySet()) {
      BuildRule rule =
          CxxLinkableEnhancer.createCxxLinkableBuildRule(
              entry.getValue().getCxxPlatform(),
              BuildRuleParamsFactory.createTrivialBuildRuleParams(target),
              pathResolver,
              ImmutableList.<String>of(),
              ImmutableList.<String>of(),
              target,
              Linker.LinkType.EXECUTABLE,
              Optional.<String>absent(),
              Paths.get("output"),
              ImmutableList.<SourcePath>of(new TestSourcePath("input.o")),
              Linker.LinkableDepType.SHARED,
              ImmutableList.<BuildRule>of(),
              Optional.<Linker.CxxRuntimeType>absent(),
              Optional.<SourcePath>absent());
      RuleKey.Builder builder = ruleKeyBuilderFactory.newInstance(rule);
      ruleKeys.put(entry.getKey(), builder.build());
    }
    return ruleKeys.build();
  }

  private AppleCxxPlatform buildAppleCxxPlatform(Path root) {
    AppleSdkPaths appleSdkPaths = AppleSdkPaths.builder()
        .setDeveloperPath(root)
        .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
        .setPlatformPath(root.resolve("Platforms/iPhoneOS.platform"))
        .setSdkPath(
            root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
        .build();
    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();
    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(
            ApplePlatform.builder().setName(ApplePlatform.Name.IPHONESIMULATOR).build())
        .setName("iphonesimulator8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();
    return AppleCxxPlatforms.buildWithExecutableChecker(
        targetSdk,
        "7.0",
        "armv7",
        appleSdkPaths,
        new FakeBuckConfig(),
        new AlwaysFoundExecutableFinder());
  }

  // The important aspects we check for in rule keys is that the host platform and the path
  // to the NDK don't cause changes.
  @Test
  public void checkRootAndPlatformDoNotAffectRuleKeys() throws IOException {
    Map<String, ImmutableMap<Flavor, RuleKey>> preprocessAndCompileRukeKeys = Maps.newHashMap();
    Map<String, ImmutableMap<Flavor, RuleKey>> preprocessRukeKeys = Maps.newHashMap();
    Map<String, ImmutableMap<Flavor, RuleKey>> compileRukeKeys = Maps.newHashMap();
    Map<String, ImmutableMap<Flavor, RuleKey>> linkRukeKeys = Maps.newHashMap();

    // Iterate building up rule keys for combinations of different platforms and NDK root
    // directories.
    for (String dir : ImmutableList.of("something", "something else")) {
      AppleCxxPlatform platform = buildAppleCxxPlatform(Paths.get(dir));
      preprocessAndCompileRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructCompileRuleKeys(
              Operation.PREPROCESS_AND_COMPILE,
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
      preprocessRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructCompileRuleKeys(
              Operation.PREPROCESS,
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
      compileRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructCompileRuleKeys(
              Operation.COMPILE,
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
      linkRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructLinkRuleKeys(
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
    }

    // If everything worked, we should be able to collapse all the generated rule keys down
    // to a singleton set.
    assertThat(
        Arrays.toString(preprocessAndCompileRukeKeys.entrySet().toArray()),
        Sets.newHashSet(preprocessAndCompileRukeKeys.values()),
        Matchers.hasSize(1));
    assertThat(
        Arrays.toString(preprocessRukeKeys.entrySet().toArray()),
        Sets.newHashSet(preprocessRukeKeys.values()),
        Matchers.hasSize(1));
    assertThat(
        Arrays.toString(compileRukeKeys.entrySet().toArray()),
        Sets.newHashSet(compileRukeKeys.values()),
        Matchers.hasSize(1));
    assertThat(
        Arrays.toString(linkRukeKeys.entrySet().toArray()),
        Sets.newHashSet(linkRukeKeys.values()),
        Matchers.hasSize(1));

  }

}
