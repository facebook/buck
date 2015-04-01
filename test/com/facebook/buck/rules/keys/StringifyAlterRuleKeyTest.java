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

package com.facebook.buck.rules.keys;

import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class StringifyAlterRuleKeyTest {

  @Test
  public void findAbsolutePathsInAbsolutePath() {
    Path path = MorePathsForTests.rootRelativePath("some/thing");
    Assert.assertEquals(
        ImmutableSet.of(path),
        ImmutableSet.copyOf(
            StringifyAlterRuleKey.findAbsolutePaths(path)));
  }

  @Test
  public void findAbsolutePathsInRelativePath() {
    Path path = Paths.get("some/thing");
    Assert.assertEquals(
        ImmutableSet.of(),
        ImmutableSet.copyOf(
            StringifyAlterRuleKey.findAbsolutePaths(path)));
  }

  @Test
  public void findAbsolutePathsInListOfPaths() {
    Path path1 = MorePathsForTests.rootRelativePath("some/thing");
    Path path2 = Paths.get("some/thing");
    List<Path> input = ImmutableList.of(path1, path2);
    Assert.assertEquals(
        ImmutableSet.of(path1),
        ImmutableSet.copyOf(
            StringifyAlterRuleKey.findAbsolutePaths(input)));
  }

  @Test
  public void findAbsolutePathsInMapOfPaths() {
    Path path1 = MorePathsForTests.rootRelativePath("some/thing");
    Path path2 = Paths.get("some/thing");
    Path path3 = Paths.get("other/thing");
    Path path4 = MorePathsForTests.rootRelativePath("other/thing");
    Map<Path, Path> input = ImmutableMap.of(
        path1, path2,
        path3, path4);
    Assert.assertEquals(
        ImmutableSet.of(path1, path4),
        ImmutableSet.copyOf(
            StringifyAlterRuleKey.findAbsolutePaths(input)));
  }

  @Test
  public void findAbsolutePathsInAbsentOptional() {
    Optional<Path> input = Optional.absent();
    Assert.assertEquals(
        ImmutableSet.<Path>of(),
        ImmutableSet.copyOf(
            StringifyAlterRuleKey.findAbsolutePaths(input)));
  }

  @Test
  public void findAbsolutePathsInListOfOptionals() {
    Path path1 = MorePathsForTests.rootRelativePath("some/thing");
    Path path2 = Paths.get("some/thing");
    List<Optional<Path>> input = ImmutableList.of(
        Optional.<Path>absent(),
        Optional.of(path2),
        Optional.<Path>absent(),
        Optional.of(path1));
    Assert.assertEquals(
        ImmutableSet.of(path1),
        ImmutableSet.copyOf(
            StringifyAlterRuleKey.findAbsolutePaths(input)));
  }

  @Test
  public void findAbsolutePathsInListOfPathSourcePaths() {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path path1 = MorePathsForTests.rootRelativePath("some/thing");
    Path path2 = Paths.get("some/thing");
    List<SourcePath> input = ImmutableList.<SourcePath>of(
        new PathSourcePath(projectFilesystem, path2),
        new PathSourcePath(projectFilesystem, path1));
    Assert.assertEquals(
        ImmutableSet.of(path1),
        ImmutableSet.copyOf(
            StringifyAlterRuleKey.findAbsolutePaths(input)));
  }

  @Test
  public void findAbsolutePathsInRecursiveStructure() {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path path1 = MorePathsForTests.rootRelativePath("some/thing");
    Path path2 = MorePathsForTests.rootRelativePath("other/thing");
    Path path3 = MorePathsForTests.rootRelativePath("yet/another/thing");
    Object input = ImmutableList.of(
        ImmutableMap.of(
            Optional.absent(), path1),
        ImmutableSet.of(Optional.of(path2)),
        Optional.absent(),
        Optional.of(new PathSourcePath(projectFilesystem, path3)));
    Assert.assertEquals(
        ImmutableSet.of(path1, path2, path3),
        ImmutableSet.copyOf(
            StringifyAlterRuleKey.findAbsolutePaths(input)));
  }

}
