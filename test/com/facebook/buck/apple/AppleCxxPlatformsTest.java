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
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Functions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Unit tests for {@link AppleCxxPlatforms}.
 */
public class AppleCxxPlatformsTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void appleSdkPathsBuiltFromDirectory() throws Exception {
    AppleSdkPaths appleSdkPaths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableMap<Path, Boolean> paths = ImmutableMap.<Path, Boolean>builder()
        .put(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"), true)
        .put(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"), true)
        .put(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"), true)
        .put(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"), true)
        .build();

    CxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            ApplePlatform.IPHONEOS,
            "iphoneos8.0",
            "6A2008a",
            "7.0",
            "armv7",
            appleSdkPaths,
            new FakeBuckConfig(),
            Functions.forMap(paths, false)
        );

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());

    assertEquals(
        ImmutableFlavor.of("iphoneos8.0-armv7"),
        appleCxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        appleCxxPlatform.getCc().getCommandPrefix(resolver).get(0));
    assertThat(
        appleCxxPlatform.getCc().getCommandPrefix(resolver),
        hasConsecutiveItems(
            "-isysroot",
            Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk").toString()));
    assertThat(
        appleCxxPlatform.getCc().getCommandPrefix(resolver),
        hasConsecutiveItems("-arch", "armv7"));
    assertThat(
        appleCxxPlatform.getCc().getCommandPrefix(resolver),
        hasConsecutiveItems("-mios-version-min=7.0"));

    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        appleCxxPlatform.getCxx().getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar")
            .toString(),
        appleCxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }

  @Test
  public void cxxToolParamsReadFromBuckConfig() throws Exception {
    AppleSdkPaths appleSdkPaths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableMap<Path, Boolean> paths = ImmutableMap.<Path, Boolean>builder()
        .put(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"), true)
        .put(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"), true)
        .put(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"), true)
        .put(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"), true)
        .build();

    CxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            ApplePlatform.IPHONEOS,
            "iphoneos8.0",
            "6A2008a",
            "7.0",
            "armv7",
            appleSdkPaths,
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>of(
                    "cxx", ImmutableMap.of(
                        "cflags", "-std=gnu11",
                        "cppflags", "-DCTHING",
                        "cxxflags", "-std=c++11",
                        "cxxppflags", "-DCXXTHING"))),
            Functions.forMap(paths, false));

    assertThat(
        appleCxxPlatform.getCflags(),
        hasItem("-std=gnu11"));
    assertThat(
        appleCxxPlatform.getCppflags(),
        hasItems("-std=gnu11", "-DCTHING"));
    assertThat(
        appleCxxPlatform.getCxxflags(),
        hasItem("-std=c++11"));
    assertThat(
        appleCxxPlatform.getCxxppflags(),
        hasItems("-std=c++11", "-DCXXTHING"));
  }

  @Test
  public void pathNotFoundThrows() throws Exception {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("Cannot find tool"));
    AppleSdkPaths appleSdkPaths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleCxxPlatforms.buildWithExecutableChecker(
        ApplePlatform.IPHONEOS,
        "iphoneos8.0",
        "6A2008a",
        "7.0",
        "armv7",
        appleSdkPaths,
        new FakeBuckConfig(),
        Functions.forPredicate(Predicates.<Path>alwaysFalse()));
  }

  @Test
  public void simulatorPlatformSetsLinkerFlags() throws Exception {
    AppleSdkPaths appleSdkPaths =
        ImmutableAppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableMap<Path, Boolean> paths = ImmutableMap.<Path, Boolean>builder()
        .put(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"), true)
        .put(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"), true)
        .put(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"), true)
        .put(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"), true)
        .build();

    CxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            ApplePlatform.IPHONESIMULATOR,
            "iphonesimulator8.0",
            "6A2008a",
            "7.0",
            "armv7",
            appleSdkPaths,
            new FakeBuckConfig(),
            Functions.forMap(paths, false)
        );

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    assertThat(
        appleCxxPlatform.getCc().getCommandPrefix(resolver),
        hasItem("-mios-simulator-version-min=7.0"));
    assertThat(
        appleCxxPlatform.getCxxld().getCommandPrefix(resolver),
        hasItem("-mios-simulator-version-min=7.0"));
  }
}
