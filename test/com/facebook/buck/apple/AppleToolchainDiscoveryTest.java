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
import static org.junit.Assert.assertThat;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleToolchainDiscoveryTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void shouldReturnAnEmptyMapIfNoToolchainsFound() throws IOException {
    Path path = temp.newFolder().toPath().toAbsolutePath();

    ImmutableMap<String, AppleToolchain> toolchains =
        AppleToolchainDiscovery.discoverAppleToolchains(
            Optional.of(path),
            ImmutableList.<Path>of());
    assertThat(toolchains.entrySet(), empty());
  }

  @Test
  public void appleToolchainPathsBuiltFromDirectory() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/toolchain-discovery");
    ImmutableMap<String, AppleToolchain> expected = ImmutableMap.of(
        "com.facebook.foo.toolchain.XcodeDefault",
        AppleToolchain.builder()
            .setIdentifier("com.facebook.foo.toolchain.XcodeDefault")
            .setVersion("23B456")
            .setPath(root.resolve("Toolchains/foo.xctoolchain"))
            .build(),
        "com.facebook.bar.toolchain.XcodeDefault",
        AppleToolchain.builder()
            .setIdentifier("com.facebook.bar.toolchain.XcodeDefault")
            .setVersion("23B456")
            .setPath(root.resolve("Toolchains/bar.xctoolchain"))
            .build());

    assertThat(
        AppleToolchainDiscovery.discoverAppleToolchains(
            Optional.of(root),
            ImmutableList.<Path>of()),
        equalTo(expected));
  }

  @Test
  public void appleToolchainPathsBuiltFromExtraDirectories() throws Exception {
    Path path = temp.newFolder().toPath().toAbsolutePath();

    Path root = Paths.get("test/com/facebook/buck/apple/testdata/toolchain-discovery");
    ImmutableMap<String, AppleToolchain> expected = ImmutableMap.of(
        "com.facebook.foo.toolchain.XcodeDefault",
        AppleToolchain.builder()
            .setIdentifier("com.facebook.foo.toolchain.XcodeDefault")
            .setVersion("23B456")
            .setPath(root.resolve("Toolchains/foo.xctoolchain"))
            .build(),
        "com.facebook.bar.toolchain.XcodeDefault",
        AppleToolchain.builder()
            .setIdentifier("com.facebook.bar.toolchain.XcodeDefault")
            .setVersion("23B456")
            .setPath(root.resolve("Toolchains/bar.xctoolchain"))
            .build());

    assertThat(
        AppleToolchainDiscovery.discoverAppleToolchains(
            Optional.of(path),
            ImmutableList.of(root.resolve("Toolchains"))),
        equalTo(expected));
  }

  @Test
  public void appleToolchainPathsIgnoresInvalidExtraPath() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/toolchain-discovery");
    ImmutableMap<String, AppleToolchain> expected = ImmutableMap.of(
        "com.facebook.foo.toolchain.XcodeDefault",
        AppleToolchain.builder()
            .setIdentifier("com.facebook.foo.toolchain.XcodeDefault")
            .setVersion("23B456")
            .setPath(root.resolve("Toolchains/foo.xctoolchain"))
            .build(),
        "com.facebook.bar.toolchain.XcodeDefault",
        AppleToolchain.builder()
            .setIdentifier("com.facebook.bar.toolchain.XcodeDefault")
            .setVersion("23B456")
            .setPath(root.resolve("Toolchains/bar.xctoolchain"))
            .build());

    assertThat(
        AppleToolchainDiscovery.discoverAppleToolchains(
            Optional.of(root),
            ImmutableList.<Path>of(Paths.get("invalid"))),
        equalTo(expected));
  }
}
