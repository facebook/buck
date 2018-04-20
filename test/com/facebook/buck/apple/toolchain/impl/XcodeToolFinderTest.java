/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class XcodeToolFinderTest {

  @Rule public TemporaryPaths tempPath = new TemporaryPaths();

  @Before
  public void setUp() {
    Assume.assumeTrue(Platform.detect() == Platform.MACOS);
  }

  @Test
  public void doesNotMatchDirectories() throws Exception {
    Path searchRoot = tempPath.newFolder("SEARCH_ROOT");
    Path directory = searchRoot.resolve("clang");
    Files.createDirectory(directory);

    XcodeToolFinder finder =
        new XcodeToolFinder(FakeBuckConfig.builder().build().getView(AppleConfig.class));

    assertTrue("Directory should look like something executable.", Files.isExecutable(directory));
    assertFalse(
        "But the finder still should not select it.",
        finder.getToolPath(ImmutableList.of(searchRoot), "clang").isPresent());
  }

  @Test
  public void doesNotMatchNonExecutableFiles() throws Exception {
    Path searchRoot = tempPath.newFolder("SEARCH_ROOT");
    Path file = searchRoot.resolve("clang");
    Files.createFile(file);

    XcodeToolFinder finder =
        new XcodeToolFinder(FakeBuckConfig.builder().build().getView(AppleConfig.class));
    assertFalse("Created file should not be accessible", Files.isExecutable(file));
    assertFalse(finder.getToolPath(ImmutableList.of(searchRoot), "clang").isPresent());
  }

  @Test
  public void matchesExecutableFiles() throws Exception {
    Path searchRoot = tempPath.newFolder("SEARCH_ROOT");
    Path file = searchRoot.resolve("clang");
    Files.createFile(
        file,
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));

    XcodeToolFinder finder =
        new XcodeToolFinder(FakeBuckConfig.builder().build().getView(AppleConfig.class));
    assertEquals(
        Optional.of(searchRoot.resolve("clang")),
        finder.getToolPath(ImmutableList.of(searchRoot), "clang"));
  }

  @Test
  public void picksFirstMatchingEntry() throws Exception {
    Path searchRoot1 = tempPath.newFolder("SEARCH_ROOT1");
    Files.createFile(
        searchRoot1.resolve("clang"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));
    Path searchRoot2 = tempPath.newFolder("SEARCH_ROOT2");
    Files.createFile(
        searchRoot2.resolve("clang"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));

    XcodeToolFinder finder =
        new XcodeToolFinder(FakeBuckConfig.builder().build().getView(AppleConfig.class));
    assertEquals(
        Optional.of(searchRoot1.resolve("clang")),
        finder.getToolPath(ImmutableList.of(searchRoot1, searchRoot2), "clang"));
    assertEquals(
        Optional.of(searchRoot2.resolve("clang")),
        finder.getToolPath(ImmutableList.of(searchRoot2, searchRoot1), "clang"));
  }

  @Test
  public void rejectsInvalidPathsUsingCache() throws Exception {
    Path searchRoot = tempPath.newFolder("SEARCH_ROOT");
    XcodeToolFinder finder =
        new XcodeToolFinder(FakeBuckConfig.builder().build().getView(AppleConfig.class));
    assertFalse(
        "First search should not find the executable.",
        finder.getToolPath(ImmutableList.of(searchRoot), "exe").isPresent());
    Files.createFile(
        searchRoot.resolve("exe"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));
    assertFalse(
        "Second search should still not find the executable, since it's cached.",
        finder.getToolPath(ImmutableList.of(searchRoot), "exe").isPresent());
    Files.createFile(
        searchRoot.resolve("bob"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));
    assertFalse(
        "The entire directory is cached, any other executables would also be not found.",
        finder.getToolPath(ImmutableList.of(searchRoot), "bob").isPresent());
    assertTrue(
        "A new instance would find the entries again.",
        new XcodeToolFinder(FakeBuckConfig.builder().build().getView(AppleConfig.class))
            .getToolPath(ImmutableList.of(searchRoot), "bob")
            .isPresent());
  }

  @Test
  public void matchesReplacementTool() throws Exception {
    Path searchRoot = tempPath.newFolder("SEARCH_ROOT");
    Path outsideRoot = tempPath.newFolder("OUTSIDE_ROOT");
    Files.createFile(
        searchRoot.resolve("clang"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));
    Files.createFile(
        searchRoot.resolve("my_clang"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));
    Files.createFile(
        outsideRoot.resolve("clang"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));
    assertEquals(
        Optional.of(searchRoot.resolve("clang")),
        new XcodeToolFinder(FakeBuckConfig.builder().build().getView(AppleConfig.class))
            .getToolPath(ImmutableList.of(searchRoot), "clang"));
    assertEquals(
        Optional.of(outsideRoot.resolve("clang")),
        new XcodeToolFinder(
                FakeBuckConfig.builder()
                    .setSections(
                        "[apple]",
                        "clang_xcode_tool_name_override=my_clang",
                        "clang_replacement=" + outsideRoot.resolve("clang"))
                    .build()
                    .getView(AppleConfig.class))
            .getToolPath(ImmutableList.of(searchRoot), "clang"));
  }

  @Test
  public void matchesRenamedToolName() throws Exception {
    Path searchRoot = tempPath.newFolder("SEARCH_ROOT");
    Files.createFile(
        searchRoot.resolve("clang"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));
    Files.createFile(
        searchRoot.resolve("my_clang"),
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)));
    assertEquals(
        Optional.of(searchRoot.resolve("clang")),
        new XcodeToolFinder(FakeBuckConfig.builder().build().getView(AppleConfig.class))
            .getToolPath(ImmutableList.of(searchRoot), "clang"));
    assertEquals(
        Optional.of(searchRoot.resolve("my_clang")),
        new XcodeToolFinder(
                FakeBuckConfig.builder()
                    .setSections("[apple]", "clang_xcode_tool_name_override=my_clang")
                    .build()
                    .getView(AppleConfig.class))
            .getToolPath(ImmutableList.of(searchRoot), "clang"));
  }
}
