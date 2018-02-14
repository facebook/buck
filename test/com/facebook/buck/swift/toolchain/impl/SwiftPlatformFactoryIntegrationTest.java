/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift.toolchain.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftPlatformFactoryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private Tool swiftcTool;
  private Tool swiftStdTool;

  @Before
  public void setUp() {
    swiftcTool = VersionedTool.of(Paths.get("swiftc"), "foo", "1.0");
    swiftStdTool = VersionedTool.of(Paths.get("swift-std"), "foo", "1.0");
  }

  @Test
  public void testBuildSwiftPlatformWithEmptyToolchainPaths() {
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            "iphoneos", ImmutableSet.of(), swiftcTool, Optional.of(swiftStdTool));
    assertThat(swiftPlatform.getSwiftStdlibTool().get(), equalTo(swiftStdTool));
    assertThat(swiftPlatform.getSwiftc(), equalTo(swiftcTool));
    assertThat(swiftPlatform.getSwiftRuntimePaths(), empty());
    assertThat(swiftPlatform.getSwiftStaticRuntimePaths(), empty());
  }

  @Test
  public void testBuildSwiftPlatformWithNonEmptyLookupPathWithoutTools() throws IOException {
    Path dir = tmp.newFolder("foo");
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            "iphoneos", ImmutableSet.of(dir), swiftcTool, Optional.of(swiftStdTool));
    assertThat(swiftPlatform.getSwiftRuntimePaths(), empty());
    assertThat(swiftPlatform.getSwiftStaticRuntimePaths(), empty());
  }

  @Test
  public void testBuildSwiftPlatformWithNonEmptyLookupPathWithTools() throws IOException {
    tmp.newFolder("foo/usr/lib/swift/iphoneos");
    tmp.newFolder("foo2/usr/lib/swift_static/iphoneos");
    tmp.newFolder("foo3/usr/lib/swift_static/iphoneos");
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            "iphoneos",
            ImmutableSet.of(
                tmp.getRoot().resolve("foo"),
                tmp.getRoot().resolve("foo2"),
                tmp.getRoot().resolve("foo3")),
            swiftcTool,
            Optional.of(swiftStdTool));
    assertThat(swiftPlatform.getSwiftRuntimePaths(), hasSize(1));
    assertThat(swiftPlatform.getSwiftStaticRuntimePaths(), hasSize(2));
  }
}
