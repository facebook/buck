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

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CellPathResolverViewTest {

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  @Test
  public void presentsSubsetOfCellsInDelegate() {
    CellPathResolverView view =
        new CellPathResolverView(
            getTestDelegate(), ImmutableSet.of("b", "c"), filesystem.getPath("foo/c"));

    Assert.assertEquals(filesystem.getPath("foo/b"), view.getCellPath(Optional.of("b")).get());
    Assert.assertEquals(filesystem.getPath("foo/c"), view.getCellPath(Optional.of("c")).get());

    Assert.assertEquals(
        "Looking up undeclared cell should return empty",
        Optional.empty(),
        view.getCellPath(Optional.of("a")));

    Assert.assertEquals(
        ImmutableMap.of(
            "b", filesystem.getPath("foo/b"),
            "c", filesystem.getPath("foo/c")),
        view.getCellPaths());
  }

  @Test
  public void returnsOwnCellPathWhenCellNameIsEmpty() {
    CellPathResolverView view =
        new CellPathResolverView(
            getTestDelegate(), ImmutableSet.of("b", "c"), filesystem.getPath("foo/c"));
    Assert.assertEquals(filesystem.getPath("foo/c"), view.getCellPathOrThrow(Optional.empty()));
  }

  @Test
  public void canonicalCellNameRelativeToDelegateCell() {
    CellPathResolverView view =
        new CellPathResolverView(
            getTestDelegate(), ImmutableSet.of("b", "c"), filesystem.getPath("foo/c"));
    Assert.assertEquals(
        "root cell resolves to no prefix.",
        Optional.empty(),
        view.getCanonicalCellName(filesystem.getPath("foo/root")));
    Assert.assertEquals(
        "current cell resolves to current cell's prefix.",
        Optional.of("c"),
        view.getCanonicalCellName(filesystem.getPath("foo/c")));
  }

  @Test
  public void testGetKnownRootsReturnDeclaredCellsOnly() {
    CellPathResolverView view =
        new CellPathResolverView(
            getTestDelegate(), ImmutableSet.of("b"), filesystem.getPath("foo/c"));

    ImmutableSortedSet<Path> knownRoots = view.getKnownRoots();

    Assert.assertEquals(
        knownRoots,
        ImmutableSortedSet.of(filesystem.getPath("foo/b"), filesystem.getPath("foo/c")));
  }

  private CellPathResolver getTestDelegate() {
    return DefaultCellPathResolver.of(
        filesystem.getPath("foo/root"),
        ImmutableMap.of(
            "a", filesystem.getPath("foo/a"),
            "b", filesystem.getPath("foo/b"),
            "c", filesystem.getPath("foo/c")));
  }
}
