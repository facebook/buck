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
  public void shouldReturnAnEmptyMapIfNoPlatformsFound() throws IOException {
    Path path = temp.newFolder().toPath().toAbsolutePath();

    ImmutableMap<String, Path> toolchains =
        AppleToolchainDiscovery.discoverAppleToolchainPaths(path);
    assertThat(toolchains.entrySet(), empty());
  }

  @Test
  public void appleToolchainPathsBuiltFromDirectory() throws Exception {
    Path root = Paths.get("test/com/facebook/buck/apple/testdata/toolchain-discovery");
    ImmutableMap<String, Path> expected = ImmutableMap.of(
        "com.facebook.foo.toolchain.XcodeDefault", root.resolve("Toolchains/foo.xctoolchain"),
        "com.facebook.bar.toolchain.XcodeDefault", root.resolve("Toolchains/bar.xctoolchain"));

    assertThat(
        AppleToolchainDiscovery.discoverAppleToolchainPaths(root),
        equalTo(expected));
  }
}
