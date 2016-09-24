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

package com.facebook.buck.swift;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SwiftPlatformsIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private VersionedTool swiftTool;
  private VersionedTool swiftStdTool;

  @Before
  public void setUp() {
    swiftTool = VersionedTool.of(Paths.get("swift"), "foo", "1.0");
    swiftStdTool = VersionedTool.of(Paths.get("swift-std"), "foo", "1.0");
  }

  @Test
  public void testBuildSwiftPlatformWithEmptyToolchainPaths() {
    SwiftPlatform swiftPlatform = SwiftPlatforms.build(
        "iphoneos",
        ImmutableSet.<Path>of(),
        swiftTool, swiftStdTool);
    assertEquals(swiftPlatform.getSwiftStdlibTool(), swiftStdTool);
    assertEquals(swiftPlatform.getSwift(), swiftTool);
    assertTrue(swiftPlatform.getSwiftRuntimePaths().isEmpty());
    assertTrue(swiftPlatform.getSwiftStaticRuntimePaths().isEmpty());
  }

  @Test
  public void testBuildSwiftPlatformWithNonEmptyLookupPathWithoutTools() throws IOException {
    Path dir = tmp.newFolder("foo");
    SwiftPlatform swiftPlatform = SwiftPlatforms.build(
        "iphoneos",
        ImmutableSet.of(dir),
        swiftTool, swiftStdTool);
    assertTrue(swiftPlatform.getSwiftRuntimePaths().isEmpty());
    assertTrue(swiftPlatform.getSwiftStaticRuntimePaths().isEmpty());
  }

  @Test
  public void testBuildSwiftPlatformWithNonEmptyLookupPathWithTools() throws IOException {
    tmp.newFolder("foo/usr/lib/swift/iphoneos");
    tmp.newFolder("foo2/usr/lib/swift_static/iphoneos");
    tmp.newFolder("foo3/usr/lib/swift_static/iphoneos");
    SwiftPlatform swiftPlatform = SwiftPlatforms.build(
        "iphoneos",
        ImmutableSet.of(
            tmp.getRoot().resolve("foo"),
            tmp.getRoot().resolve("foo2"),
            tmp.getRoot().resolve("foo3")),
        swiftTool, swiftStdTool);
    assertEquals(swiftPlatform.getSwiftRuntimePaths().size(), 1);
    assertEquals(swiftPlatform.getSwiftStaticRuntimePaths().size(), 2);
  }

}
