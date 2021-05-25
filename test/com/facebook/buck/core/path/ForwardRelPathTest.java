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

package com.facebook.buck.core.path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.filesystems.BuckFileSystem;
import com.facebook.buck.core.filesystems.BuckFileSystemProvider;
import com.facebook.buck.core.filesystems.FileName;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ForwardRelPathTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testEquals() {
    assertEquals(ForwardRelPath.of("ab/cd"), ForwardRelPath.of("ab/cd"));
    assertNotEquals(ForwardRelPath.of("ab"), ForwardRelPath.of("ab/cd"));
    assertNotEquals(ForwardRelPath.of("a"), ForwardRelPath.of("ab"));
  }

  @Test
  public void testHashCode() {
    assertEquals(ForwardRelPath.of("ab/cd").hashCode(), ForwardRelPath.of("ab/cd").hashCode());
  }

  @Test
  public void ofSubstring() {
    assertEquals(ForwardRelPath.of("ab/cd"), ForwardRelPath.ofSubstring("xy/ab/cd", 3));
    assertEquals(ForwardRelPath.of(""), ForwardRelPath.ofSubstring("//", 2));
  }

  @Test
  public void ofSubstringError() {
    thrown.expectMessage("path must not end with slash: 'foo/'");
    ForwardRelPath.ofSubstring("//foo/", 2);
  }

  @Test
  public void empty() {
    assertSame(ForwardRelPath.EMPTY, ForwardRelPath.of(""));
    assertSame(ForwardRelPath.EMPTY, ForwardRelPath.ofSubstring("ab/c", 4));
  }

  @Test
  public void testToString() {
    assertEquals("", ForwardRelPath.of("").toString());
    assertEquals("ab", ForwardRelPath.of("ab").toString());
    assertEquals("ab/cd", ForwardRelPath.of("ab/cd").toString());
    assertEquals(".b/c.", ForwardRelPath.of(".b/c.").toString());
  }

  @Test
  public void incorrectPath() {
    String[] badPaths =
        new String[] {
          "/", "a/", "/a", "a//b", "a//b", "a/./b", "a/../b", "a/.", "./", ".",
        };

    for (String badPath : badPaths) {
      try {
        ForwardRelPath.of(badPath);
        fail("expecting bad path: " + badPath);
      } catch (Exception e) {
        // expected
      }
    }
  }

  @Test
  public void toPathDefaultFileSystem() {
    assertEquals(Paths.get("ab/cd"), ForwardRelPath.of("ab/cd").toPathDefaultFileSystem());
  }

  @Test
  public void toPathBuckFileSystem() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    BuckFileSystemProvider bfsProvider = new BuckFileSystemProvider(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");
    assertEquals(bfs.getPath("ab/cd"), ForwardRelPath.of("ab/cd").toPath(bfs));
    assertEquals(bfs.getPath("ab"), ForwardRelPath.of("ab").toPath(bfs));
    assertEquals(bfs.getPath(""), ForwardRelPath.of("").toPath(bfs));
  }

  @Test
  public void ofPath() {
    assertEquals(ForwardRelPath.of(""), ForwardRelPath.ofPath(Paths.get("")));
    assertEquals(ForwardRelPath.of("a"), ForwardRelPath.ofPath(Paths.get("a")));
    assertEquals(ForwardRelPath.of("a/bbb"), ForwardRelPath.ofPath(Paths.get("a/bbb")));
    assertEquals(ForwardRelPath.of(""), ForwardRelPath.ofPath(Paths.get("././aa/..")));
  }

  @Test
  public void ofPathBuckFileSystem() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    BuckFileSystemProvider bfsProvider = new BuckFileSystemProvider(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");
    assertEquals(ForwardRelPath.of(""), ForwardRelPath.ofPath(bfs.getPath("")));
    assertEquals(ForwardRelPath.of("a"), ForwardRelPath.ofPath(bfs.getPath("a")));
    assertEquals(ForwardRelPath.of("a/bbb"), ForwardRelPath.ofPath(bfs.getPath("a/bbb")));
    assertEquals(ForwardRelPath.of(""), ForwardRelPath.ofPath(bfs.getPath("././aa/..")));
  }

  @Test
  public void ofPathBadPaths() {
    thrown.expect(IllegalArgumentException.class);
    ForwardRelPath.ofPath(Paths.get("../b"));
  }

  @Test
  public void ofPathBadPathsUnixFileSystem() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    BuckFileSystemProvider bfsProvider = new BuckFileSystemProvider(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");

    thrown.expect(IllegalArgumentException.class);
    ForwardRelPath.ofPath(bfs.getPath("../b"));
  }

  @Test
  public void relativize() {
    assertEquals("../xy", ForwardRelPath.of("ab/cd").relativize(ForwardRelPath.of("ab/xy")));
    assertEquals("", ForwardRelPath.of("ab/cd").relativize(ForwardRelPath.of("ab/cd")));

    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    BuckFileSystemProvider bfsProvider = new BuckFileSystemProvider(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");

    String[] paths = new String[] {"", "ab", "ab/cd", "xy", "xy/ab"};

    for (String p1 : paths) {
      for (String p2 : paths) {
        assertEquals(
            p1 + " -> " + p2,
            bfs.getPath(p1).relativize(bfs.getPath(p2)).toString(),
            ForwardRelPath.of(p1).relativize(ForwardRelPath.of(p2)));
      }
    }
  }

  @Test
  public void compareToIsConsistentWithPaths() {
    assertEquals(0, ForwardRelPath.of("").compareTo(ForwardRelPath.of("")));
    assertTrue(ForwardRelPath.of("a").compareTo(ForwardRelPath.of("")) > 0);
    assertTrue(ForwardRelPath.of("a/b").compareTo(ForwardRelPath.of("")) > 0);
    assertTrue(ForwardRelPath.of("").compareTo(ForwardRelPath.of("a")) < 0);
    assertTrue(ForwardRelPath.of("").compareTo(ForwardRelPath.of("a/b")) < 0);
    assertTrue(ForwardRelPath.of("a/b").compareTo(ForwardRelPath.of("aa")) < 0);

    String[] paths = new String[] {"", "ab", "ab/cd", "xy", "xy/ab"};

    for (String p1 : paths) {
      for (String p2 : paths) {
        assertEquals(
            p1 + " <=> " + p2,
            Integer.signum(Paths.get(p1).compareTo(Paths.get(p2))),
            Integer.signum(ForwardRelPath.of(p1).compareTo(ForwardRelPath.of(p2))));
      }
    }
  }

  @Test
  public void startsWith() {
    assertTrue(ForwardRelPath.of("").startsWith(ForwardRelPath.of("")));
    assertTrue(ForwardRelPath.of("ab").startsWith(ForwardRelPath.of("")));
    assertTrue(ForwardRelPath.of("ab/cd").startsWith(ForwardRelPath.of("")));
    assertTrue(ForwardRelPath.of("ab/cd").startsWith(ForwardRelPath.of("ab")));
    assertTrue(ForwardRelPath.of("ab/cd").startsWith(ForwardRelPath.of("ab/cd")));

    assertFalse(ForwardRelPath.of("").startsWith(ForwardRelPath.of("ab")));
    assertFalse(ForwardRelPath.of("").startsWith(ForwardRelPath.of("ab/cd")));
    assertFalse(ForwardRelPath.of("ab").startsWith(ForwardRelPath.of("ab/cd")));

    assertFalse(ForwardRelPath.of("ab").startsWith(ForwardRelPath.of("a")));
  }

  @Test
  public void endsWith() {
    assertTrue(ForwardRelPath.of("").endsWith(ForwardRelPath.of("")));
    assertTrue(ForwardRelPath.of("ab").endsWith(ForwardRelPath.of("")));
    assertTrue(ForwardRelPath.of("ab/cd").endsWith(ForwardRelPath.of("")));
    assertTrue(ForwardRelPath.of("ab/cd").endsWith(ForwardRelPath.of("cd")));
    assertTrue(ForwardRelPath.of("ab/cd").endsWith(ForwardRelPath.of("ab/cd")));

    assertFalse(ForwardRelPath.of("").endsWith(ForwardRelPath.of("ab")));
    assertFalse(ForwardRelPath.of("").endsWith(ForwardRelPath.of("ab/cd")));
    assertFalse(ForwardRelPath.of("cd").endsWith(ForwardRelPath.of("ab/cd")));

    assertFalse(ForwardRelPath.of("ab").endsWith(ForwardRelPath.of("b")));
  }

  @Test
  public void endsWithFileName() {
    assertTrue(ForwardRelPath.of("a").endsWith(FileName.of("a")));
    assertTrue(ForwardRelPath.of("b/a").endsWith(FileName.of("a")));
    assertTrue(ForwardRelPath.of("c/b/a").endsWith(FileName.of("a")));

    assertFalse(ForwardRelPath.of("").endsWith(FileName.of("a")));
    assertFalse(ForwardRelPath.of("b").endsWith(FileName.of("a")));
    assertFalse(ForwardRelPath.of("a/b").endsWith(FileName.of("a")));
  }

  @Test
  public void resolve() {
    String[] paths = new String[] {"", "foo", "bar/baz"};
    for (String p1 : paths) {
      for (String p2 : paths) {
        ForwardRelPath naive =
            ForwardRelPath.of(p1 + (!p1.isEmpty() && !p2.isEmpty() ? "/" : "") + p2);
        ForwardRelPath fast = ForwardRelPath.of(p1).resolve(ForwardRelPath.of(p2));
        assertEquals(naive, fast);
      }
    }
  }

  @Test
  public void nameAsPath() {
    assertEquals(Optional.empty(), ForwardRelPath.of("").nameAsPath());
    assertEquals(Optional.of(ForwardRelPath.of("foo")), ForwardRelPath.of("foo").nameAsPath());
    assertEquals(Optional.of(ForwardRelPath.of("bar")), ForwardRelPath.of("foo/bar").nameAsPath());
  }

  @Test
  public void dirname() {
    assertEquals(Optional.empty(), ForwardRelPath.of("").parent());
    assertEquals(Optional.of(ForwardRelPath.of("")), ForwardRelPath.of("foo").parent());
    assertEquals(Optional.of(ForwardRelPath.of("foo")), ForwardRelPath.of("foo/bar").parent());
    assertEquals(
        Optional.of(ForwardRelPath.of("foo/bar")), ForwardRelPath.of("foo/bar/baz").parent());
  }

  @Test
  public void toPathPrefix() {
    assertEquals("", ForwardRelPath.of("").toPathPrefix());
    assertEquals("foo/", ForwardRelPath.of("foo").toPathPrefix());
    assertEquals("foo/bar/", ForwardRelPath.of("foo/bar").toPathPrefix());
  }

  @Test
  public void getNameCount() {
    // Paths.get("").getNameCount() returns 1, while
    // BuckUnixPath of "" .getNameCount() returns 0
    assertEquals(0, ForwardRelPath.of("").getNameCount());
    assertEquals(1, ForwardRelPath.of("foo").getNameCount());
    assertEquals(1, Paths.get("foo").getNameCount());
    assertEquals(2, ForwardRelPath.of("foo/bar").getNameCount());
    assertEquals(2, Paths.get("foo/bar").getNameCount());
  }

  @Test
  public void getParent() {
    assertNull(ForwardRelPath.of("").getParent());
    assertNull(Paths.get("").getParent());
    assertNull(ForwardRelPath.of("foo").getParent());
    assertNull(Paths.get("foo").getParent());
    assertEquals(ForwardRelPath.of("foo"), ForwardRelPath.of("foo/bar").getParent());
    assertEquals(Paths.get("foo"), Paths.get("foo/bar").getParent());
    assertEquals(ForwardRelPath.of("foo/bar"), ForwardRelPath.of("foo/bar/baz").getParent());
    assertEquals(Paths.get("foo/bar"), Paths.get("foo/bar/baz").getParent());
  }

  @Test
  public void getParentButEmptyForSingleSegment() {
    assertNull(ForwardRelPath.of("").getParentButEmptyForSingleSegment());
    assertEquals(ForwardRelPath.of(""), ForwardRelPath.of("a").getParentButEmptyForSingleSegment());
    assertEquals(
        ForwardRelPath.of("a"), ForwardRelPath.of("a/b").getParentButEmptyForSingleSegment());
    assertEquals(
        ForwardRelPath.of("a/b"), ForwardRelPath.of("a/b/c").getParentButEmptyForSingleSegment());
  }

  @Test
  public void asIterable() {
    for (String path : new String[] {"", "ab", "ab/c", "ab/c/de"}) {
      assertEquals(
          "for path: '" + path + "'",
          ImmutableList.copyOf(Paths.get(path)).stream()
              .map(Path::toString)
              .collect(ImmutableList.toImmutableList()),
          ImmutableList.copyOf(ForwardRelPath.of(path).asIterable()).stream()
              .map(ForwardRelPath::toString)
              .collect(ImmutableList.toImmutableList()));
    }
  }

  @Test
  public void nameComponents() {
    assertEquals(ImmutableList.of(), ForwardRelPath.of("").nameComponents());
    assertEquals(ImmutableList.of(FileName.of("foo")), ForwardRelPath.of("foo").nameComponents());
    assertEquals(
        ImmutableList.of(FileName.of("foo"), FileName.of("bar")),
        ForwardRelPath.of("foo/bar").nameComponents());
  }

  @Test
  public void getFileName() {
    assertEquals(Optional.empty(), ForwardRelPath.of("").getFileName());
    assertEquals(Optional.of(FileName.of("x")), ForwardRelPath.of("x").getFileName());
    assertEquals(Optional.of(FileName.of("y")), ForwardRelPath.of("x/y").getFileName());
  }
}
