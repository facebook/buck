/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.model.ImmutableUnflavoredBuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SourcePathTypeCoercerTest {
  private final BuildTargetParser buildTargetParser = new BuildTargetParser();
  private FakeProjectFilesystem projectFilesystem;
  private final Path pathRelativeToProjectRoot = Paths.get("");
  private final SourcePathTypeCoercer sourcePathTypeCoercer =
      new SourcePathTypeCoercer(new BuildTargetTypeCoercer(), new PathTypeCoercer());

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
  }

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Test
  public void coercePath() throws CoerceFailedException, IOException {
    String path = "hello.a";
    projectFilesystem.touch(Paths.get(path));

    SourcePath sourcePath = sourcePathTypeCoercer.coerce(
        buildTargetParser,
        projectFilesystem,
        pathRelativeToProjectRoot,
        path);

    assertEquals(new PathSourcePath(projectFilesystem, Paths.get(path)), sourcePath);
  }

  @Test
  public void coerceAbsoluteBuildTarget() throws CoerceFailedException, IOException {
    SourcePath sourcePath = sourcePathTypeCoercer.coerce(
        buildTargetParser,
        projectFilesystem,
        pathRelativeToProjectRoot,
        "//:hello");

    assertEquals(
        new BuildTargetSourcePath(
            projectFilesystem,
            ImmutableBuildTarget.of(
                ImmutableUnflavoredBuildTarget.of(
                    Optional.<String>absent(),
                    "//",
                    "hello"),
                ImmutableSortedSet.<Flavor>of())),
        sourcePath);
  }

  @Test
  public void coerceRelativeBuildTarget() throws CoerceFailedException, IOException {
    SourcePath sourcePath = sourcePathTypeCoercer.coerce(
        buildTargetParser,
        projectFilesystem,
        pathRelativeToProjectRoot,
        ":hello");

    assertEquals(
        new BuildTargetSourcePath(
            projectFilesystem,
            ImmutableBuildTarget.of(
                ImmutableUnflavoredBuildTarget.of(
                      Optional.<String>absent(),
                      "//",
                      "hello"),
                ImmutableSortedSet.<Flavor>of())),
        sourcePath);
  }

  @Test
  public void coerceCrossRepoBuildTarget() throws CoerceFailedException, IOException {
    BuildTargetParser buildTargetParser = new BuildTargetParser(
        ImmutableMap.of(Optional.of("hello"), Optional.of("hello")));

    SourcePath sourcePath = sourcePathTypeCoercer.coerce(
        buildTargetParser,
        projectFilesystem,
        pathRelativeToProjectRoot,
        "@hello//:hello");

    assertEquals(
        new BuildTargetSourcePath(
            projectFilesystem,
            ImmutableBuildTarget.of(
                ImmutableUnflavoredBuildTarget.of(
                    Optional.of("hello"),
                    "//",
                    "hello"),
                ImmutableSortedSet.<Flavor>of())),
        sourcePath);
  }

  @Test
  public void coercingAbsolutePathThrows() throws CoerceFailedException, IOException {
    Path path = MorePathsForTests.rootRelativePath("hello.a");
    projectFilesystem.touch(path);

    exception.expect(CoerceFailedException.class);
    exception.expectMessage(
        "SourcePath cannot contain an absolute path");

    sourcePathTypeCoercer.coerce(
        buildTargetParser,
        projectFilesystem,
        pathRelativeToProjectRoot,
        path.toString());
  }
}
