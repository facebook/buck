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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDictionary;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

public class AppleCxxPlatformsTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public TestLogSink logSink = new TestLogSink(AppleCxxPlatforms.class);

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

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
        .setApplePlatform(ApplePlatform.IPHONEOS)
        .setName("iphoneos8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/codesign_allocate"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/momc"))
        .add(Paths.get("usr/bin/lldb"))
        .add(Paths.get("usr/bin/xctest"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.<ProcessExecutor>absent(),
            Optional.<AppleToolchain>absent());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    assertEquals(
        ImmutableList.of("usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Toolchains/XcodeDefault.xctoolchain/usr/bin/codesign_allocate"),
        appleCxxPlatform.getCodesignAllocate().get().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));

    assertEquals(
        ImmutableFlavor.of("iphoneos8.0-armv7"),
        cxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
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
            "-Wl,-sdk_version",
            "-Wl,8.0"));
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
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
        .setApplePlatform(ApplePlatform.WATCHOS)
        .setName("watchos2.0")
        .setVersion("2.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"))
        .add(Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/momc"))
        .add(Paths.get("usr/bin/lldb"))
        .add(Paths.get("usr/bin/xctest"))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "2.0",
            "armv7k",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.<ProcessExecutor>absent(),
            Optional.<AppleToolchain>absent());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    assertEquals(
        ImmutableList.of("usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
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
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
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
            "-Wl,-sdk_version",
            "-Wl,2.0"));
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/ar")
            .toString(),
        cxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }

  @Test
  public void appleTVOSSdkPathsBuiltFromDirectory() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/AppleTVOS.platform"))
            .setSdkPath(Paths.get("Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS9.1.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.APPLETVOS)
        .setName("appletvos9.1")
        .setVersion("9.1")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"))
        .add(Paths.get("Platforms/AppleTVOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/AppleTVOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/momc"))
        .add(Paths.get("usr/bin/lldb"))
        .add(Paths.get("usr/bin/xctest"))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "9.1",
            "arm64",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.<ProcessExecutor>absent(),
            Optional.<AppleToolchain>absent());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    assertEquals(
        ImmutableList.of("usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));

    assertEquals(
        ImmutableFlavor.of("appletvos9.1-arm64"),
        cxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot",
            Paths.get("Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS9.1.sdk").toString()));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-arch", "arm64"));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-mtvos-version-min=9.1"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasConsecutiveItems(
            "-Wl,-sdk_version",
            "-Wl,9.1"));
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/AppleTVOS.platform/Developer/usr/bin/ar")
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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/momc"))
        .add(Paths.get("usr/bin/lldb"))
        .add(Paths.get("usr/bin/xctest"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONEOS)
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
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.<ProcessExecutor>absent(),
            Optional.<AppleToolchain>absent());

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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/momc"))
        .add(Paths.get("usr/bin/lldb"))
        .add(Paths.get("usr/bin/xctest"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONEOS)
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
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "cxx", ImmutableMap.of(
                        "cflags", "-std=gnu11",
                        "cppflags", "-DCTHING",
                        "cxxflags", "-std=c++11",
                        "cxxppflags", "-DCXXTHING"))).build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.<ProcessExecutor>absent(),
            Optional.<AppleToolchain>absent());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-std=gnu11"));
    assertThat(
        cxxPlatform.getCppflags(),
        hasItems("-DCTHING"));
    assertThat(
        cxxPlatform.getCxxflags(),
        hasItem("-std=c++11"));
    assertThat(
        cxxPlatform.getCxxppflags(),
        hasItems("-DCXXTHING"));
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
        .setApplePlatform(ApplePlatform.IPHONEOS)
        .setName("iphoneos8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatforms.buildWithExecutableChecker(
        targetSdk,
        "7.0",
        "armv7",
        appleSdkPaths,
        FakeBuckConfig.builder().build(),
        new FakeAppleConfig(),
        new FakeExecutableFinder(ImmutableSet.<Path>of()),
        Optional.<ProcessExecutor>absent(),
        Optional.<AppleToolchain>absent());
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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/momc"))
        .add(Paths.get("usr/bin/lldb"))
        .add(Paths.get("usr/bin/xctest"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
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
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.<ProcessExecutor>absent(),
            Optional.<AppleToolchain>absent());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-mios-simulator-version-min=7.0"));
    assertThat(
        cxxPlatform.getLdflags(),
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
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/momc"))
        .add(Paths.get("usr/bin/lldb"))
        .add(Paths.get("usr/bin/xctest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.WATCHSIMULATOR)
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
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.<ProcessExecutor>absent(),
            Optional.<AppleToolchain>absent());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-mwatchos-simulator-version-min=2.0"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasItem("-mwatchos-simulator-version-min=2.0"));
  }

  @Test
  public void appleTVOSSimulatorPlatformSetsLinkerFlags() throws Exception {
    AppleSdkPaths appleSdkPaths = AppleSdkPaths.builder()
        .setDeveloperPath(Paths.get("."))
        .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setPlatformPath(Paths.get("Platforms/AppleTVSimulator.platform"))
        .setSdkPath(
            Paths.get("Platforms/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator9.1.sdk")
        )
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"))
        .add(Paths.get("Platforms/AppleTVSimulator.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/AppleTVSimulator.platform/Developer/usr/bin/ar"))
        .add(Paths.get("usr/bin/actool"))
        .add(Paths.get("usr/bin/ibtool"))
        .add(Paths.get("usr/bin/momc"))
        .add(Paths.get("usr/bin/lldb"))
        .add(Paths.get("usr/bin/xctest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.APPLETVSIMULATOR)
        .setName("appletvsimulator9.1")
        .setVersion("9.1")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "9.1",
            "arm64",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.<ProcessExecutor>absent(),
            Optional.<AppleToolchain>absent());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-mtvos-simulator-version-min=9.1"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasItem("-mtvos-simulator-version-min=9.1"));
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    String source = "source.cpp";
    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            0,
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("source.cpp", Strings.repeat("a", 40))
                    .build()),
            pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<Flavor, RuleKey> ruleKeys =
        ImmutableMap.builder();
    for (Map.Entry<Flavor, AppleCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(new FakeBuildRuleParamsBuilder(target).build())
          .setResolver(resolver)
          .setPathResolver(pathResolver)
          .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
          .setCxxPlatform(entry.getValue().getCxxPlatform())
          .setPicType(CxxSourceRuleFactory.PicType.PIC)
          .build();
      CxxPreprocessAndCompile rule;
      switch (operation) {
        case PREPROCESS_AND_COMPILE:
          rule =
              cxxSourceRuleFactory.createPreprocessAndCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new FakeSourcePath(source),
                      ImmutableList.<String>of()),
                  CxxPreprocessMode.COMBINED);
          break;
        case PREPROCESS:
          rule =
              cxxSourceRuleFactory.createPreprocessBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new FakeSourcePath(source),
                      ImmutableList.<String>of()));
          break;
        case COMPILE:
          rule =
              cxxSourceRuleFactory.createCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX_CPP_OUTPUT,
                      new FakeSourcePath(source),
                      ImmutableList.<String>of()));
          break;
        default:
          throw new IllegalStateException();
      }
      ruleKeys.put(entry.getKey(), ruleKeyBuilderFactory.build(rule));
    }
    return ruleKeys.build();
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<Flavor, RuleKey> constructLinkRuleKeys(
      ImmutableMap<Flavor, AppleCxxPlatform> cxxPlatforms) throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            0,
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
              CxxPlatformUtils.DEFAULT_CONFIG,
              entry.getValue().getCxxPlatform(),
              new FakeBuildRuleParamsBuilder(target).build(),
              resolver,
              pathResolver,
              target,
              Linker.LinkType.EXECUTABLE,
              Optional.<String>absent(),
              Paths.get("output"),
              Linker.LinkableDepType.SHARED,
              ImmutableList.<NativeLinkable>of(),
              Optional.<Linker.CxxRuntimeType>absent(),
              Optional.<SourcePath>absent(),
              ImmutableSet.<BuildTarget>of(),
              NativeLinkableInput.builder()
                  .setArgs(SourcePathArg.from(pathResolver, new FakeSourcePath("input.o")))
                  .build());
      ruleKeys.put(entry.getKey(), ruleKeyBuilderFactory.build(rule));
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
        .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
        .setName("iphonesimulator8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();
    return AppleCxxPlatforms.buildWithExecutableChecker(
        targetSdk,
        "7.0",
        "armv7",
        appleSdkPaths,
        FakeBuckConfig.builder().build(),
        new FakeAppleConfig(),
        new AlwaysFoundExecutableFinder(),
        Optional.<ProcessExecutor>absent(),
        Optional.<AppleToolchain>absent());
  }

  // The important aspects we check for in rule keys is that the host platform and the path
  // to the NDK don't cause changes.
  @Test
  public void checkRootAndPlatformDoNotAffectRuleKeys() throws Exception {
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

  @Test
  public void nonExistentPlatformVersionPlistIsLogged() {
    AppleCxxPlatform platform = buildAppleCxxPlatform(Paths.get("/nonexistentjabberwock"));
    assertThat(platform.getBuildVersion(), equalTo(Optional.<String>absent()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern(".*does not exist.*Build version will be unset.*"))));
  }

  @Test
  public void invalidPlatformVersionPlistIsLogged() throws Exception {
    Path tempRoot = temp.getRoot().toPath();
    Path platformRoot = tempRoot.resolve("Platforms/iPhoneOS.platform");
    Files.createDirectories(platformRoot);
    Files.write(
        platformRoot.resolve("version.plist"),
        "I am, as a matter of fact, an extremely invalid plist.".getBytes(Charsets.UTF_8));
    AppleCxxPlatform platform = buildAppleCxxPlatform(tempRoot);
    assertThat(platform.getBuildVersion(), equalTo(Optional.<String>absent()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern("Failed to parse.*Build version will be unset.*"))));
  }

  @Test
  public void platformVersionPlistWithMissingFieldIsLogged() throws Exception {
    Path tempRoot = temp.getRoot().toPath();
    Path platformRoot = tempRoot.resolve("Platforms/iPhoneOS.platform");
    Files.createDirectories(platformRoot);
    Files.write(
        platformRoot.resolve("version.plist"),
        new NSDictionary().toXMLPropertyList().getBytes(Charsets.UTF_8));
    AppleCxxPlatform platform = buildAppleCxxPlatform(tempRoot);
    assertThat(platform.getBuildVersion(), equalTo(Optional.<String>absent()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern(".*missing ProductBuildVersion. Build version will be unset.*"))));
  }
}
