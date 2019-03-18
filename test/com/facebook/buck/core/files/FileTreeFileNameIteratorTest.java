/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.NoSuchElementException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class FileTreeFileNameIteratorTest {

  @Rule public ExpectedException expected = ExpectedException.none();

  private FileTreeFileNameIterator iterator;

  @Before
  public void setUp() throws Exception {
    iterator = createIterator();
  }

  private static FileTreeFileNameIterator createIterator() {
    DirectoryList dlist1 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("file")),
            ImmutableSortedSet.of(Paths.get("dir1")),
            ImmutableSortedSet.of());

    DirectoryList dlist2 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("dir1/file1")),
            ImmutableSortedSet.of(Paths.get("dir1/dir2")),
            ImmutableSortedSet.of());

    DirectoryList dlist3 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("dir1/dir2/file")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of());

    FileTree ftree3 = ImmutableFileTree.of(Paths.get("dir1/dir2"), dlist3, ImmutableMap.of());
    FileTree ftree2 =
        ImmutableFileTree.of(
            Paths.get("dir1"), dlist2, ImmutableMap.of(Paths.get("dir1/dir2"), ftree3));
    FileTree ftree1 =
        ImmutableFileTree.of(Paths.get(""), dlist1, ImmutableMap.of(Paths.get("dir1"), ftree2));

    return FileTreeFileNameIterator.of(ftree1, "file");
  }

  @Test
  public void canIterate() {
    List<Path> allFiles = Lists.newArrayList(iterator);
    assertEquals(2, allFiles.size());
    assertThat(
        allFiles, Matchers.containsInAnyOrder(Paths.get("file"), Paths.get("dir1/dir2/file")));
  }

  @Test
  public void whenHasNextIsCalledTwiceOnBegin() {
    assertTrue(iterator.hasNext());
    assertTrue(iterator.hasNext());
  }

  @Test
  public void whenHasNextIsCalledTwiceOnEnd() {
    Lists.newArrayList(iterator);
    assertFalse(iterator.hasNext());
    assertFalse(iterator.hasNext());
  }

  @Test
  public void whenHasNextIsNotCalledThenIteratorStillMoves() {
    Path path1 = iterator.next();
    Path path2 = iterator.next();
    assertNotEquals(path1, path2);
  }

  @Test
  public void whenNextIsCalledAndNoMoreItemsThenThrow() {
    expected.expect(NoSuchElementException.class);
    Lists.newArrayList(iterator);
    iterator.next();
  }
}
