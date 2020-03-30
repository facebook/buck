/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CellPathResolverViewTest {

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  @Test
  public void presentsSubsetOfCellsInDelegate() {
    CellPathResolverView view =
        new CellPathResolverView(
            getTestDelegate(),
            getTestCellNameResolver("c", Optional.empty(), "b", "c"),
            ImmutableSet.of("b", "c"),
            filesystem.resolve("foo/c"));

    Assert.assertEquals(filesystem.resolve("foo/b"), view.getCellPath(Optional.of("b")).get());
    Assert.assertEquals(filesystem.resolve("foo/c"), view.getCellPath(Optional.of("c")).get());

    Assert.assertEquals(
        "Looking up undeclared cell should return empty",
        Optional.empty(),
        view.getCellPath(Optional.of("a")));

    Assert.assertEquals(
        ImmutableMap.of(
            "b", AbsPath.of(filesystem.resolve("foo/b")),
            "c", AbsPath.of(filesystem.resolve("foo/c"))),
        view.getCellPathsByRootCellExternalName());
  }

  @Test
  public void returnsOwnCellPathWhenCellNameIsEmpty() {
    CellPathResolverView view =
        new CellPathResolverView(
            getTestDelegate(),
            getTestCellNameResolver("c", Optional.empty(), "b", "c"),
            ImmutableSet.of("b", "c"),
            filesystem.resolve("foo/c"));
    Assert.assertEquals(filesystem.resolve("foo/c"), view.getCellPathOrThrow(Optional.empty()));
  }

  @Test
  public void canonicalCellNameRelativeToDelegateCell() {
    CellPathResolverView view =
        new CellPathResolverView(
            getTestDelegate(),
            getTestCellNameResolver("c", Optional.empty(), "b", "c"),
            ImmutableSet.of("b", "c"),
            filesystem.resolve("foo/c"));
    Assert.assertEquals(
        "root cell resolves to no prefix.",
        Optional.empty(),
        view.getCanonicalCellName(filesystem.getRootPath()));
    Assert.assertEquals(
        "current cell resolves to current cell's prefix.",
        Optional.of("c"),
        view.getCanonicalCellName(filesystem.resolve("foo/c")));
  }

  @Test
  public void testGetKnownRootsReturnDeclaredCellsOnly() {
    CellPathResolverView view =
        new CellPathResolverView(
            getTestDelegate(),
            getTestCellNameResolver("c", Optional.empty(), "b"),
            ImmutableSet.of("b"),
            filesystem.resolve("foo/c"));

    ImmutableSortedSet<AbsPath> knownRoots = view.getKnownRoots();

    Assert.assertEquals(
        knownRoots,
        ImmutableSortedSet.orderedBy(AbsPath.comparator())
            .add(AbsPath.of(filesystem.resolve("foo/b")), AbsPath.of(filesystem.resolve("foo/c")))
            .build());
  }

  @Test
  public void isEqualAndHashable() {
    CellPathResolverView view1 =
        new CellPathResolverView(
            getTestDelegate(),
            getTestCellNameResolver("c", Optional.empty(), "b"),
            ImmutableSet.of("b"),
            filesystem.resolve("foo/c"));

    CellPathResolverView view2 =
        new CellPathResolverView(
            getTestDelegate(),
            getTestCellNameResolver("c", Optional.empty(), "b"),
            ImmutableSet.of("b"),
            filesystem.resolve("foo/c"));

    Assert.assertEquals(view1, view2);
    Assert.assertEquals(view1.hashCode(), view2.hashCode());
  }

  private CellNameResolver getTestCellNameResolver(
      String selfName, Optional<String> rootName, String... visibleRootNames) {
    return TestCellNameResolver.forSecondary(selfName, rootName, visibleRootNames);
  }

  private CellPathResolver getTestDelegate() {
    return TestCellPathResolver.create(
        filesystem.getRootPath(),
        ImmutableMap.of(
            "a", filesystem.resolve("foo/a"),
            "b", filesystem.resolve("foo/b"),
            "c", filesystem.resolve("foo/c")));
  }
}
