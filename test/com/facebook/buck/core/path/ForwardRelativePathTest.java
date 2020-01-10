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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.filesystems.BuckFileSystem;
import com.facebook.buck.core.filesystems.BuckFileSystemProvider;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ForwardRelativePathTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testEquals() {
    assertEquals(ForwardRelativePath.of("ab/cd"), ForwardRelativePath.of("ab/cd"));
    assertNotEquals(ForwardRelativePath.of("ab"), ForwardRelativePath.of("ab/cd"));
    assertNotEquals(ForwardRelativePath.of("a"), ForwardRelativePath.of("ab"));
  }

  @Test
  public void testHashCode() {
    assertEquals(
        ForwardRelativePath.of("ab/cd").hashCode(), ForwardRelativePath.of("ab/cd").hashCode());
  }

  @Test
  public void ofSubstring() {
    assertEquals(ForwardRelativePath.of("ab/cd"), ForwardRelativePath.ofSubstring("xy/ab/cd", 3));
    assertEquals(ForwardRelativePath.of(""), ForwardRelativePath.ofSubstring("//", 2));
  }

  @Test
  public void ofSubstringError() {
    thrown.expectMessage("path must not end with slash: foo/");
    ForwardRelativePath.ofSubstring("//foo/", 2);
  }

  @Test
  public void empty() {
    assertSame(ForwardRelativePath.EMPTY, ForwardRelativePath.of(""));
    assertSame(ForwardRelativePath.EMPTY, ForwardRelativePath.ofSubstring("ab/c", 4));
  }

  @Test
  public void testToString() {
    assertEquals("", ForwardRelativePath.of("").toString());
    assertEquals("ab", ForwardRelativePath.of("ab").toString());
    assertEquals("ab/cd", ForwardRelativePath.of("ab/cd").toString());
    assertEquals(".b/c.", ForwardRelativePath.of(".b/c.").toString());
  }

  @Test
  public void incorrectPath() {
    String[] badPaths =
        new String[] {
          "/", "a/", "/a", "a//b", "a//b", "a/./b", "a/../b", "a/.", "./", ".",
        };

    for (String badPath : badPaths) {
      try {
        ForwardRelativePath.of(badPath);
        fail("expecting bad path: " + badPath);
      } catch (Exception e) {
        // expected
      }
    }
  }

  @Test
  public void toPathDefaultFileSystem() {
    assertEquals(Paths.get("ab/cd"), ForwardRelativePath.of("ab/cd").toPathDefaultFileSystem());
  }

  @Test
  public void toPathBuckFileSystem() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    BuckFileSystemProvider bfsProvider = new BuckFileSystemProvider(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");
    assertEquals(bfs.getPath("ab/cd"), ForwardRelativePath.of("ab/cd").toPath(bfs));
    assertEquals(bfs.getPath("ab"), ForwardRelativePath.of("ab").toPath(bfs));
    assertEquals(bfs.getPath(""), ForwardRelativePath.of("").toPath(bfs));
  }

  @Test
  public void ofPath() {
    assertEquals(ForwardRelativePath.of(""), ForwardRelativePath.ofPath(Paths.get("")));
    assertEquals(ForwardRelativePath.of("a"), ForwardRelativePath.ofPath(Paths.get("a")));
    assertEquals(ForwardRelativePath.of("a/bbb"), ForwardRelativePath.ofPath(Paths.get("a/bbb")));
    assertEquals(ForwardRelativePath.of(""), ForwardRelativePath.ofPath(Paths.get("././aa/..")));
  }

  @Test
  public void ofPathBuckFileSystem() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    BuckFileSystemProvider bfsProvider = new BuckFileSystemProvider(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");
    assertEquals(ForwardRelativePath.of(""), ForwardRelativePath.ofPath(bfs.getPath("")));
    assertEquals(ForwardRelativePath.of("a"), ForwardRelativePath.ofPath(bfs.getPath("a")));
    assertEquals(ForwardRelativePath.of("a/bbb"), ForwardRelativePath.ofPath(bfs.getPath("a/bbb")));
    assertEquals(ForwardRelativePath.of(""), ForwardRelativePath.ofPath(bfs.getPath("././aa/..")));
  }

  @Test
  public void ofPathBadPaths() {
    thrown.expect(IllegalArgumentException.class);
    ForwardRelativePath.ofPath(Paths.get("../b"));
  }

  @Test
  public void ofPathBadPathsUnixFileSystem() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    BuckFileSystemProvider bfsProvider = new BuckFileSystemProvider(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");

    thrown.expect(IllegalArgumentException.class);
    ForwardRelativePath.ofPath(bfs.getPath("../b"));
  }

  @Test
  public void relativize() {
    assertEquals(
        "../xy", ForwardRelativePath.of("ab/cd").relativize(ForwardRelativePath.of("ab/xy")));
    assertEquals("", ForwardRelativePath.of("ab/cd").relativize(ForwardRelativePath.of("ab/cd")));

    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    BuckFileSystemProvider bfsProvider = new BuckFileSystemProvider(vfs);
    BuckFileSystem bfs = new BuckFileSystem(bfsProvider, "/");

    String[] paths = new String[] {"", "ab", "ab/cd", "xy", "xy/ab"};

    for (String p1 : paths) {
      for (String p2 : paths) {
        assertEquals(
            p1 + " -> " + p2,
            bfs.getPath(p1).relativize(bfs.getPath(p2)).toString(),
            ForwardRelativePath.of(p1).relativize(ForwardRelativePath.of(p2)));
      }
    }
  }

  @Test
  public void compareToIsConsistentWithPaths() {
    assertEquals(0, ForwardRelativePath.of("").compareTo(ForwardRelativePath.of("")));
    assertTrue(ForwardRelativePath.of("a").compareTo(ForwardRelativePath.of("")) > 0);
    assertTrue(ForwardRelativePath.of("a/b").compareTo(ForwardRelativePath.of("")) > 0);
    assertTrue(ForwardRelativePath.of("").compareTo(ForwardRelativePath.of("a")) < 0);
    assertTrue(ForwardRelativePath.of("").compareTo(ForwardRelativePath.of("a/b")) < 0);
    assertTrue(ForwardRelativePath.of("a/b").compareTo(ForwardRelativePath.of("aa")) < 0);

    String[] paths = new String[] {"", "ab", "ab/cd", "xy", "xy/ab"};

    for (String p1 : paths) {
      for (String p2 : paths) {
        assertEquals(
            p1 + " <=> " + p2,
            Integer.signum(Paths.get(p1).compareTo(Paths.get(p2))),
            Integer.signum(ForwardRelativePath.of(p1).compareTo(ForwardRelativePath.of(p2))));
      }
    }
  }

  @Test
  public void startsWith() {
    assertTrue(ForwardRelativePath.of("").startsWith(ForwardRelativePath.of("")));
    assertTrue(ForwardRelativePath.of("ab").startsWith(ForwardRelativePath.of("")));
    assertTrue(ForwardRelativePath.of("ab/cd").startsWith(ForwardRelativePath.of("")));
    assertTrue(ForwardRelativePath.of("ab/cd").startsWith(ForwardRelativePath.of("ab")));
    assertTrue(ForwardRelativePath.of("ab/cd").startsWith(ForwardRelativePath.of("ab/cd")));

    assertFalse(ForwardRelativePath.of("").startsWith(ForwardRelativePath.of("ab")));
    assertFalse(ForwardRelativePath.of("").startsWith(ForwardRelativePath.of("ab/cd")));
    assertFalse(ForwardRelativePath.of("ab").startsWith(ForwardRelativePath.of("ab/cd")));

    assertFalse(ForwardRelativePath.of("ab").startsWith(ForwardRelativePath.of("a")));
  }

  @Test
  public void endsWith() {
    assertTrue(ForwardRelativePath.of("").endsWith(ForwardRelativePath.of("")));
    assertTrue(ForwardRelativePath.of("ab").endsWith(ForwardRelativePath.of("")));
    assertTrue(ForwardRelativePath.of("ab/cd").endsWith(ForwardRelativePath.of("")));
    assertTrue(ForwardRelativePath.of("ab/cd").endsWith(ForwardRelativePath.of("cd")));
    assertTrue(ForwardRelativePath.of("ab/cd").endsWith(ForwardRelativePath.of("ab/cd")));

    assertFalse(ForwardRelativePath.of("").endsWith(ForwardRelativePath.of("ab")));
    assertFalse(ForwardRelativePath.of("").endsWith(ForwardRelativePath.of("ab/cd")));
    assertFalse(ForwardRelativePath.of("cd").endsWith(ForwardRelativePath.of("ab/cd")));

    assertFalse(ForwardRelativePath.of("ab").endsWith(ForwardRelativePath.of("b")));
  }

  @Test
  public void resolve() {
    String[] paths = new String[] {"", "foo", "bar/baz"};
    for (String p1 : paths) {
      for (String p2 : paths) {
        ForwardRelativePath naive =
            ForwardRelativePath.of(p1 + (!p1.isEmpty() && !p2.isEmpty() ? "/" : "") + p2);
        ForwardRelativePath fast = ForwardRelativePath.of(p1).resolve(ForwardRelativePath.of(p2));
        assertEquals(naive, fast);
      }
    }
  }

  @Test
  public void nameAsPath() {
    assertEquals(Optional.empty(), ForwardRelativePath.of("").nameAsPath());
    assertEquals(
        Optional.of(ForwardRelativePath.of("foo")), ForwardRelativePath.of("foo").nameAsPath());
    assertEquals(
        Optional.of(ForwardRelativePath.of("bar")), ForwardRelativePath.of("foo/bar").nameAsPath());
  }

  @Test
  public void dirname() {
    assertEquals(Optional.empty(), ForwardRelativePath.of("").parent());
    assertEquals(Optional.of(ForwardRelativePath.of("")), ForwardRelativePath.of("foo").parent());
    assertEquals(
        Optional.of(ForwardRelativePath.of("foo")), ForwardRelativePath.of("foo/bar").parent());
    assertEquals(
        Optional.of(ForwardRelativePath.of("foo/bar")),
        ForwardRelativePath.of("foo/bar/baz").parent());
  }

  @Test
  public void toPathPrefix() {
    assertEquals("", ForwardRelativePath.of("").toPathPrefix());
    assertEquals("foo/", ForwardRelativePath.of("foo").toPathPrefix());
    assertEquals("foo/bar/", ForwardRelativePath.of("foo/bar").toPathPrefix());
  }
}
