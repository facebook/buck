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

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDictionary;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.FakeAppleRuleDescriptions;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AppleCxxPlatformsTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Rule public TestLogSink logSink = new TestLogSink(AppleCxxPlatforms.class);

  private ProjectFilesystem projectFilesystem;
  private Path developerDir;

  @Before
  public void setUp() throws InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    developerDir = projectFilesystem.getPath("/Developer");
  }

  /** Get paths in a developer dir that should be set up for a sdk. */
  private static ImmutableSet<Path> getCommonKnownPaths(Path root) {
    return ImmutableSet.of(
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"),
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"),
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"),
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"),
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"),
        root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"),
        root.resolve("usr/bin/actool"),
        root.resolve("usr/bin/ibtool"),
        root.resolve("usr/bin/momc"),
        root.resolve("usr/bin/copySceneKitAssets"),
        root.resolve("usr/bin/lldb"),
        root.resolve("usr/bin/xctest"));
  }

  private void touchFile(Path file) {
    Preconditions.checkArgument(
        file.getFileSystem().equals(projectFilesystem.getRootPath().getFileSystem()),
        "Should only make changes to in-memory filesystem files.");
    try {
      Files.createDirectories(file.getParent());
      Files.createFile(file);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void iphoneOSSdkPathsBuiltFromDirectory() {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    ImmutableSet<Path> paths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(
                developerDir.resolve(
                    "Toolchains/XcodeDefault.xctoolchain/usr/bin/codesign_allocate"))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
            .add(developerDir.resolve("Tools/otest"))
            .build();
    paths.forEach(this::touchFile);

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            projectFilesystem,
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            new AppleCxxPlatforms.XcodeBuildVersionCache());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));

    assertEquals(
        ImmutableList.of("/Developer/usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of(
            "/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/codesign_allocate"),
        appleCxxPlatform.getCodesignAllocate().get().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("/Developer/usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));

    assertEquals(InternalFlavor.of("iphoneos8.0-armv7"), cxxPlatform.getFlavor());
    assertEquals(
        "/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang",
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot", "/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"));
    assertThat(cxxPlatform.getCflags(), hasConsecutiveItems("-arch", "armv7"));
    assertThat(cxxPlatform.getAsflags(), hasConsecutiveItems("-arch", "armv7"));
    assertThat(cxxPlatform.getCflags(), hasConsecutiveItems("-mios-version-min=7.0"));
    assertThat(cxxPlatform.getLdflags(), hasConsecutiveItems("-Wl,-sdk_version", "-Wl,8.0"));
    assertEquals(
        "/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++",
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertEquals(
        "/Developer/Platforms/iPhoneOS.platform/Developer/usr/bin/ar",
        cxxPlatform.getAr().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
  }

  @Test
  public void watchOSSdkPathsBuiltFromDirectory() {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/WatchOS.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/WatchOS.platform/Developer/SDKs/WatchOS2.0.sdk"))
            .build();

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.WATCHOS)
            .setName("watchos2.0")
            .setVersion("2.0")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    ImmutableSet<Path> paths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(developerDir.resolve("Platforms/WatchOS.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/WatchOS.platform/Developer/usr/bin/ar"))
            .build();
    paths.forEach(this::touchFile);

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            projectFilesystem,
            targetSdk,
            "2.0",
            "armv7k",
            appleSdkPaths,
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            new AppleCxxPlatforms.XcodeBuildVersionCache());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));

    assertEquals(
        ImmutableList.of("/Developer/usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("/Developer/usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));

    assertEquals(InternalFlavor.of("watchos2.0-armv7k"), cxxPlatform.getFlavor());
    assertEquals(
        "/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang",
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot", "/Developer/Platforms/WatchOS.platform/Developer/SDKs/WatchOS2.0.sdk"));
    assertThat(cxxPlatform.getCflags(), hasConsecutiveItems("-arch", "armv7k"));
    assertThat(cxxPlatform.getCflags(), hasConsecutiveItems("-mwatchos-version-min=2.0"));
    assertThat(cxxPlatform.getLdflags(), hasConsecutiveItems("-Wl,-sdk_version", "-Wl,2.0"));
    assertEquals(
        "/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++",
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertEquals(
        "/Developer/Platforms/WatchOS.platform/Developer/usr/bin/ar",
        cxxPlatform.getAr().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
  }

  @Test
  public void appleTVOSSdkPathsBuiltFromDirectory() {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/AppleTVOS.platform"))
            .setSdkPath(
                developerDir.resolve(
                    "Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS9.1.sdk"))
            .build();

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.APPLETVOS)
            .setName("appletvos9.1")
            .setVersion("9.1")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    ImmutableSet<Path> paths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(developerDir.resolve("Platforms/AppleTVOS.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/AppleTVOS.platform/Developer/usr/bin/ar"))
            .build();
    paths.forEach(this::touchFile);

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            projectFilesystem,
            targetSdk,
            "9.1",
            "arm64",
            appleSdkPaths,
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            new AppleCxxPlatforms.XcodeBuildVersionCache());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    assertEquals(
        ImmutableList.of("/Developer/usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("/Developer/usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));

    assertEquals(InternalFlavor.of("appletvos9.1-arm64"), cxxPlatform.getFlavor());
    assertEquals(
        "/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang",
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot",
            "/Developer/Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS9.1.sdk"));
    assertThat(cxxPlatform.getCflags(), hasConsecutiveItems("-arch", "arm64"));
    assertThat(cxxPlatform.getCflags(), hasConsecutiveItems("-mtvos-version-min=9.1"));
    assertThat(cxxPlatform.getLdflags(), hasConsecutiveItems("-Wl,-sdk_version", "-Wl,9.1"));
    assertEquals(
        "/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++",
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertEquals(
        "/Developer/Platforms/AppleTVOS.platform/Developer/usr/bin/ar",
        cxxPlatform.getAr().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
  }

  @Test
  public void invalidFlavorCharactersInSdkAreEscaped() {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableSet<Path> paths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
            .add(developerDir.resolve("Tools/otest"))
            .build();
    paths.forEach(this::touchFile);

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .setName("_(in)+va|id_")
            .setVersion("8.0")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            projectFilesystem,
            targetSdk,
            "7.0",
            "cha+rs",
            appleSdkPaths,
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            new AppleCxxPlatforms.XcodeBuildVersionCache());

    assertEquals(
        InternalFlavor.of("__in__va_id_-cha_rs"), appleCxxPlatform.getCxxPlatform().getFlavor());
  }

  @Test
  public void cxxToolParamsReadFromBuckConfig() {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableSet<Path> paths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
            .add(developerDir.resolve("Tools/otest"))
            .build();
    paths.forEach(this::touchFile);

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "cxx",
                    ImmutableMap.of(
                        "cflags", "-std=gnu11",
                        "cppflags", "-DCTHING",
                        "cxxflags", "-std=c++11",
                        "cxxppflags", "-DCXXTHING")))
            .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            projectFilesystem,
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            new AppleCxxPlatforms.XcodeBuildVersionCache());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(cxxPlatform.getCflags(), hasItem("-std=gnu11"));
    assertThat(cxxPlatform.getCppflags(), hasItems("-DCTHING"));
    assertThat(cxxPlatform.getCxxflags(), hasItem("-std=c++11"));
    assertThat(cxxPlatform.getCxxppflags(), hasItems("-DCXXTHING"));
  }

  @Test
  public void pathNotFoundThrows() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("Cannot find tool"));
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.IPHONEOS)
            .setName("iphoneos8.0")
            .setVersion("8.0")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleCxxPlatforms.buildWithXcodeToolFinder(
        projectFilesystem,
        targetSdk,
        "7.0",
        "armv7",
        appleSdkPaths,
        buckConfig,
        new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
        new AppleCxxPlatforms.XcodeBuildVersionCache());
  }

  @Test
  public void iphoneOSSimulatorPlatformSetsLinkerFlags() {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                developerDir.resolve(
                    "Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
            .build();

    ImmutableSet<Path> paths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
            .add(developerDir.resolve("Tools/otest"))
            .build();
    paths.forEach(this::touchFile);

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .setName("iphonesimulator8.0")
            .setVersion("8.0")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            projectFilesystem,
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            new AppleCxxPlatforms.XcodeBuildVersionCache());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(cxxPlatform.getCflags(), hasItem("-mios-simulator-version-min=7.0"));
    assertThat(cxxPlatform.getLdflags(), hasItem("-mios-simulator-version-min=7.0"));
  }

  @Test
  public void watchOSSimulatorPlatformSetsLinkerFlags() {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/WatchSimulator.platform"))
            .setSdkPath(
                developerDir.resolve(
                    "Platforms/WatchSimulator.platform/Developer/SDKs/WatchSimulator2.0.sdk"))
            .build();

    ImmutableSet<Path> paths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(
                developerDir.resolve("Platforms/WatchSimulator.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/WatchSimulator.platform/Developer/usr/bin/ar"))
            .build();
    paths.forEach(this::touchFile);

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.WATCHSIMULATOR)
            .setName("watchsimulator2.0")
            .setVersion("2.0")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            projectFilesystem,
            targetSdk,
            "2.0",
            "armv7k",
            appleSdkPaths,
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            new AppleCxxPlatforms.XcodeBuildVersionCache());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(cxxPlatform.getCflags(), hasItem("-mwatchos-simulator-version-min=2.0"));
    assertThat(cxxPlatform.getLdflags(), hasItem("-mwatchos-simulator-version-min=2.0"));
  }

  @Test
  public void appleTVOSSimulatorPlatformSetsLinkerFlags() {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/AppleTVSimulator.platform"))
            .setSdkPath(
                developerDir.resolve(
                    "Platforms/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator9.1.sdk"))
            .build();

    ImmutableSet<Path> paths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(
                developerDir.resolve(
                    "Platforms/AppleTVSimulator.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/AppleTVSimulator.platform/Developer/usr/bin/ar"))
            .build();
    paths.forEach(this::touchFile);

    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();

    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.APPLETVSIMULATOR)
            .setName("appletvsimulator9.1")
            .setVersion("9.1")
            .setToolchains(ImmutableList.of(toolchain))
            .build();

    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            projectFilesystem,
            targetSdk,
            "9.1",
            "arm64",
            appleSdkPaths,
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            new AppleCxxPlatforms.XcodeBuildVersionCache());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(cxxPlatform.getCflags(), hasItem("-mtvos-simulator-version-min=9.1"));
    assertThat(cxxPlatform.getLdflags(), hasItem("-mtvos-simulator-version-min=9.1"));
  }

  enum Operation {
    COMPILE,
    PREPROCESS_AND_COMPILE,
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<Flavor, RuleKey> constructCompileRuleKeys(
      Operation operation, ImmutableMap<Flavor, AppleCxxPlatform> cxxPlatforms) throws IOException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    String source = "source.cpp";
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            new FakeFileHashCache(
                ImmutableMap.<Path, HashCode>builder()
                    .put(projectFilesystem.resolve("source.cpp"), HashCode.fromInt(0))
                    .build()),
            pathResolver,
            ruleFinder);
    BuildTarget target =
        BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:target");
    ImmutableMap.Builder<Flavor, RuleKey> ruleKeys = ImmutableMap.builder();
    for (Map.Entry<Flavor, AppleCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxSourceRuleFactory cxxSourceRuleFactory =
          CxxSourceRuleFactory.builder()
              .setProjectFilesystem(projectFilesystem)
              .setBaseBuildTarget(target)
              .setActionGraphBuilder(graphBuilder)
              .setPathResolver(pathResolver)
              .setRuleFinder(ruleFinder)
              .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
              .setCxxPlatform(entry.getValue().getCxxPlatform())
              .setPicType(PicType.PIC)
              .build();
      CxxPreprocessAndCompile rule;
      switch (operation) {
        case PREPROCESS_AND_COMPILE:
          rule =
              cxxSourceRuleFactory.requirePreprocessAndCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      FakeSourcePath.of(projectFilesystem, source),
                      ImmutableList.of()));
          break;
        case COMPILE:
          rule =
              cxxSourceRuleFactory.requireCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX_CPP_OUTPUT,
                      FakeSourcePath.of(projectFilesystem, source),
                      ImmutableList.of()));
          break;
        default:
          throw new IllegalStateException();
      }
      ruleKeys.put(entry.getKey(), ruleKeyFactory.build(rule));
    }
    return ruleKeys.build();
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<Flavor, RuleKey> constructLinkRuleKeys(
      ImmutableMap<Flavor, AppleCxxPlatform> cxxPlatforms) throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("input.o", Strings.repeat("a", 40))
                    .build()),
            pathResolver,
            ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<Flavor, RuleKey> ruleKeys = ImmutableMap.builder();
    for (Map.Entry<Flavor, AppleCxxPlatform> entry : cxxPlatforms.entrySet()) {
      BuildRule rule =
          CxxLinkableEnhancer.createCxxLinkableBuildRule(
              CxxPlatformUtils.DEFAULT_CONFIG,
              entry.getValue().getCxxPlatform(),
              new FakeProjectFilesystem(),
              graphBuilder,
              pathResolver,
              ruleFinder,
              target,
              Linker.LinkType.EXECUTABLE,
              Optional.empty(),
              projectFilesystem.getPath("output"),
              ImmutableList.of(),
              Linker.LinkableDepType.SHARED,
              CxxLinkOptions.of(),
              ImmutableList.of(),
              Optional.empty(),
              Optional.empty(),
              ImmutableSet.of(),
              ImmutableSet.of(),
              NativeLinkableInput.builder()
                  .setArgs(SourcePathArg.from(FakeSourcePath.of("input.o")))
                  .build(),
              Optional.empty(),
              TestCellPathResolver.get(projectFilesystem));
      ruleKeys.put(entry.getKey(), ruleKeyFactory.build(rule));
    }
    return ruleKeys.build();
  }

  private AppleCxxPlatform buildAppleCxxPlatform(Path root, BuckConfig config) {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(root)
            .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(root.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
            .build();
    AppleToolchain toolchain =
        AppleToolchain.builder()
            .setIdentifier("com.apple.dt.XcodeDefault")
            .setPath(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setVersion("1")
            .build();
    AppleSdk targetSdk =
        AppleSdk.builder()
            .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
            .setName("iphonesimulator8.0")
            .setVersion("8.0")
            .setToolchains(ImmutableList.of(toolchain))
            .build();
    getCommonKnownPaths(root).forEach(this::touchFile);
    this.touchFile(root.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/ar"));
    return AppleCxxPlatforms.buildWithXcodeToolFinder(
        projectFilesystem,
        targetSdk,
        "7.0",
        "armv7",
        appleSdkPaths,
        config,
        new XcodeToolFinder(config.getView(AppleConfig.class)),
        FakeAppleRuleDescriptions.FAKE_XCODE_BUILD_VERSION_CACHE);
  }

  private AppleCxxPlatform buildAppleCxxPlatform(Path root) {
    return buildAppleCxxPlatform(
        root, FakeBuckConfig.builder().setFilesystem(projectFilesystem).build());
  }

  private AppleCxxPlatform buildAppleCxxPlatform() {
    return buildAppleCxxPlatform(
        developerDir, FakeBuckConfig.builder().setFilesystem(projectFilesystem).build());
  }

  @Test
  public void byDefaultCodesignToolIsConstant() {
    AppleCxxPlatform appleCxxPlatform = buildAppleCxxPlatform();
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    SourcePathResolver sourcePathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(buildRuleResolver));
    assertThat(
        appleCxxPlatform
            .getCodesignProvider()
            .resolve(buildRuleResolver)
            .getCommandPrefix(sourcePathResolver),
        is(Collections.singletonList("/usr/bin/codesign")));
  }

  private abstract static class NoopBinaryBuildRule extends NoopBuildRuleWithDeclaredAndExtraDeps
      implements BinaryBuildRule {
    public NoopBinaryBuildRule(
        BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRuleParams params) {
      super(buildTarget, projectFilesystem, params);
    }
  }

  private static Tool fakeTool() {
    return new Tool() {
      @Override
      public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
        return ImmutableList.of();
      }

      @Override
      public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
        return ImmutableMap.of();
      }
    };
  }

  @Test
  public void buckTargetIsUsedWhenBuildTargetIsSpecified() {
    AppleCxxPlatform appleCxxPlatform =
        buildAppleCxxPlatform(
            developerDir,
            FakeBuckConfig.builder().setSections("[apple]", "codesign = //foo:bar").build());
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    Tool codesign = fakeTool();
    BinaryBuildRule buildRule =
        new NoopBinaryBuildRule(
            buildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create()) {
          @Override
          public Tool getExecutableCommand() {
            return codesign;
          }
        };
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.computeIfAbsent(buildTarget, target -> buildRule);
    assertThat(appleCxxPlatform.getCodesignProvider().resolve(graphBuilder), is(codesign));
  }

  @Test
  public void filePathIsUsedWhenBuildTargetDoesNotExist() {
    Path codesignPath = projectFilesystem.getPath("/foo/fakecodesign");
    touchFile(codesignPath);
    AppleCxxPlatform appleCxxPlatform =
        buildAppleCxxPlatform(
            developerDir,
            FakeBuckConfig.builder()
                .setFilesystem(projectFilesystem)
                .setSections("[apple]", "codesign = " + codesignPath)
                .build());
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    SourcePathResolver sourcePathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(buildRuleResolver));
    assertThat(
        appleCxxPlatform
            .getCodesignProvider()
            .resolve(buildRuleResolver)
            .getCommandPrefix(sourcePathResolver),
        is(Collections.singletonList(codesignPath.toString())));
  }

  // The important aspects we check for in rule keys is that the host platform and the path
  // to the NDK don't cause changes.
  @Test
  public void checkRootAndPlatformDoNotAffectRuleKeys() throws Exception {
    Map<String, ImmutableMap<Flavor, RuleKey>> preprocessAndCompileRukeKeys = new HashMap<>();
    Map<String, ImmutableMap<Flavor, RuleKey>> compileRukeKeys = new HashMap<>();
    Map<String, ImmutableMap<Flavor, RuleKey>> linkRukeKeys = new HashMap<>();

    // Iterate building up rule keys for combinations of different platforms and NDK root
    // directories.
    for (String dir : ImmutableList.of("something", "something else")) {
      AppleCxxPlatform platform = buildAppleCxxPlatform(projectFilesystem.getPath(dir));
      preprocessAndCompileRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructCompileRuleKeys(
              Operation.PREPROCESS_AND_COMPILE,
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
      compileRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructCompileRuleKeys(
              Operation.COMPILE, ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
      linkRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructLinkRuleKeys(ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
    }

    // If everything worked, we should be able to collapse all the generated rule keys down
    // to a singleton set.
    assertThat(
        Arrays.toString(preprocessAndCompileRukeKeys.entrySet().toArray()),
        Sets.newHashSet(preprocessAndCompileRukeKeys.values()),
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
    AppleCxxPlatform platform =
        buildAppleCxxPlatform(projectFilesystem.getPath("/nonexistentjabberwock"));
    assertThat(platform.getBuildVersion(), equalTo(Optional.empty()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern(".*does not exist.*Build version will be unset.*"))));
  }

  @Test
  public void invalidPlatformVersionPlistIsLogged() throws Exception {
    Path platformRoot = developerDir.resolve("Platforms/iPhoneOS.platform");
    Files.createDirectories(platformRoot);
    Files.write(
        platformRoot.resolve("version.plist"),
        "I am, as a matter of fact, an extremely invalid plist.".getBytes(Charsets.UTF_8));
    AppleCxxPlatform platform = buildAppleCxxPlatform();
    assertThat(platform.getBuildVersion(), equalTo(Optional.empty()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern("Failed to parse.*Build version will be unset.*"))));
  }

  @Test
  public void platformVersionPlistWithMissingFieldIsLogged() throws Exception {
    Path platformRoot = developerDir.resolve("Platforms/iPhoneOS.platform");
    Files.createDirectories(platformRoot);
    Files.write(
        platformRoot.resolve("version.plist"),
        new NSDictionary().toXMLPropertyList().getBytes(Charsets.UTF_8));
    AppleCxxPlatform platform = buildAppleCxxPlatform();
    assertThat(platform.getBuildVersion(), equalTo(Optional.empty()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern(".*missing ProductBuildVersion. Build version will be unset.*"))));
  }

  @Test
  public void appleCxxPlatformWhenNoSwiftToolchainPreferredShouldUseDefaultSwift()
      throws IOException {
    AppleCxxPlatform platformWithDefaultSwift = buildAppleCxxPlatformWithSwiftToolchain();
    Optional<SwiftPlatform> swiftPlatformOptional = platformWithDefaultSwift.getSwiftPlatform();
    assertThat(swiftPlatformOptional.isPresent(), is(true));
    Tool swiftcTool = swiftPlatformOptional.get().getSwiftc();
    assertTrue(swiftcTool instanceof VersionedTool);
    PathSourcePath path = ((VersionedTool) swiftcTool).getPath();
    assertEquals(projectFilesystem, path.getFilesystem());
    assertEquals(
        "/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc",
        path.getRelativePath().toString());

    assertThat(swiftPlatformOptional.get().getSwiftRuntimePaths(), Matchers.empty());
  }

  @Test
  public void checkSwiftPlatformUsesCorrectMinTargetSdk() {
    AppleCxxPlatform platformWithConfiguredSwift = buildAppleCxxPlatformWithSwiftToolchain();
    Tool swiftc = platformWithConfiguredSwift.getSwiftPlatform().get().getSwiftc();
    assertThat(swiftc, notNullValue());
    assertThat(swiftc, instanceOf(VersionedTool.class));
    VersionedTool versionedSwiftc = (VersionedTool) swiftc;
    assertThat(versionedSwiftc.getExtraArgs(), hasItem("i386-apple-ios7.0"));
  }

  @Test
  public void testXcodeBuildVersionCache() throws Exception {
    Path developerDir = projectFilesystem.getPath("/Xcode.app/Contents/Developer");
    Path versionPlist = projectFilesystem.getPath("/Xcode.app/Contents/version.plist");
    Files.createDirectories(developerDir);
    Files.write(
        versionPlist,
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "\t<key>ProductBuildVersion</key>\n"
                + "\t<string>9F9999</string>\n"
                + "</dict>\n"
                + "</plist>")
            .getBytes(Charsets.UTF_8));
    AppleCxxPlatforms.XcodeBuildVersionCache cache = new AppleCxxPlatforms.XcodeBuildVersionCache();
    assertEquals(Optional.of("9F9999"), cache.lookup(developerDir));
  }

  @Test
  public void testXcodeToolVersionOverride() {
    AppleCxxPlatform appleCxxPlatform1 =
        buildAppleCxxPlatform(
            projectFilesystem.getPath("/Developer1"), FakeBuckConfig.builder().build());

    AppleCxxPlatform appleCxxPlatform2 =
        buildAppleCxxPlatform(
            projectFilesystem.getPath("/Developer2"),
            FakeBuckConfig.builder()
                .setSections("[apple]", "ibtool_version_override = custom_ibtool_version")
                .build());

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of());

    RuleKey actoolRuleKey1 =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .newBuilderForTesting(new FakeBuildRule("//:test"))
            .setReflectively("tool", appleCxxPlatform1.getActool())
            .build(RuleKey::new);

    RuleKey actoolRuleKey2 =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .newBuilderForTesting(new FakeBuildRule("//:test"))
            .setReflectively("tool", appleCxxPlatform2.getActool())
            .build(RuleKey::new);

    assertEquals(actoolRuleKey1, actoolRuleKey2);

    RuleKey ibtoolRuleKey1 =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .newBuilderForTesting(new FakeBuildRule("//:test"))
            .setReflectively("tool", appleCxxPlatform1.getIbtool())
            .build(RuleKey::new);

    RuleKey ibtoolRuleKey2 =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .newBuilderForTesting(new FakeBuildRule("//:test"))
            .setReflectively("tool", appleCxxPlatform2.getIbtool())
            .build(RuleKey::new);

    assertNotEquals(ibtoolRuleKey1, ibtoolRuleKey2);
  }

  private AppleCxxPlatform buildAppleCxxPlatformWithSwiftToolchain() {
    ImmutableSet<Path> knownPaths =
        ImmutableSet.<Path>builder()
            .addAll(getCommonKnownPaths(developerDir))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
            .add(developerDir.resolve("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
            .add(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc"))
            .add(
                developerDir.resolve(
                    "Toolchains/XcodeDefault.xctoolchain/usr/bin/swift-stdlib-tool"))
            .build();
    knownPaths.forEach(this::touchFile);
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    return AppleCxxPlatforms.buildWithXcodeToolFinder(
        projectFilesystem,
        FakeAppleRuleDescriptions.DEFAULT_IPHONEOS_SDK,
        "7.0",
        "i386",
        AppleSdkPaths.builder()
            .setDeveloperPath(developerDir)
            .addToolchainPaths(developerDir.resolve("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(developerDir.resolve("Platforms/iPhoneOS.platform"))
            .setSdkPath(
                developerDir.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
            .build(),
        buckConfig,
        new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
        FakeAppleRuleDescriptions.FAKE_XCODE_BUILD_VERSION_CACHE);
  }
}
