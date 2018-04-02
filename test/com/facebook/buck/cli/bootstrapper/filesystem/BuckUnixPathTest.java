/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.cli.bootstrapper.filesystem;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class BuckUnixPathTest {

  private BuckUnixPath createPath(String pathString) {
    BuckFileSystemProvider provider =
        new BuckFileSystemProvider(FileSystems.getDefault().provider());
    Path path = provider.getFileSystem(URI.create("file:///")).getPath(pathString);
    assertTrue(path instanceof BuckUnixPath);
    return (BuckUnixPath) path;
  }

  @Test
  @Parameters({"", "/", "some/relative/path", "/some/absolute/path", "filename.txt"})
  public void toStringMethod(String data) {
    Path path = createPath(data);
    assertEquals(data, path.toString());
  }

  @Test
  @Parameters({
    "/,/,=",
    ",,=",
    "/some/path,/some/path,=",
    "a,b,<",
    "b,a,>",
    "some/a,some/b,<",
    "some/b,some/a,>",
    "/some,/some/a,<"
  })
  public void compareToMethod(String data1, String data2, String expected) {
    Path path1 = createPath(data1);
    Path path2 = createPath(data2);
    int actual = path1.compareTo(path2);
    switch (expected) {
      case "=":
        assertEquals(0, actual);
        break;
      case ">":
        assertTrue(actual > 0);
        break;
      case "<":
        assertTrue(actual < 0);
        break;
      default:
        throw new IllegalArgumentException(expected);
    }
  }

  @Test
  @Parameters({
    ",,true",
    "/,/,true",
    "/path/1/2,1/2,true",
    "/,,false",
    ",/,false",
    "/path/1,/path/1,true",
    "/path1/1,path1/1,true",
    "/path1/1/2,/path2/1/2,false",
    "/path1/1/2,1/3,false",
    "path1/1,path1,false"
  })
  public void endsWithMethod(String data1, String data2, boolean expected) {
    Path path1 = createPath(data1);
    Path path2 = createPath(data2);
    assertEquals(expected, path1.endsWith(path2));
  }

  @Test
  @Parameters({
    ",,true",
    "/,/,true",
    "/path/to/something,/path/to/something,true",
    "1/2/3,1/2/3,true",
    "/,/a,false",
    "a,b,false",
    "/a/b,/b/a,false",
    "/a,/a/a,false"
  })
  public void equalsMethod(String data1, String data2, boolean expected) {
    Path path1 = createPath(data1);
    Path path2 = createPath(data2);
    assertEquals(expected, path1.equals(path2));
  }

  @Test
  @Parameters({
    ",null",
    "/,/",
    "/path/to/something,something",
    "path/1/2,2",
    "filename.txt,filename.txt"
  })
  public void getFileNameMethod(String data, String expected) {
    Path path = createPath(data);
    Path fileName = path.getFileName();
    if (expected.equals("null")) {
      assertNull(fileName);
    } else {
      assertEquals(expected, fileName.toString());
    }
  }

  @Test
  @Parameters({"", "/", "file", "dir/file", "/dir/file"})
  public void getFileSystemMethod(String data) {
    Path path = createPath(data);
    assertTrue(path.getFileSystem() instanceof BuckFileSystem);
  }

  @Test
  @Parameters({"a,0,a", "/a,0,a", "a/b,0,a", "a/b,1,b", "a/b/c,1,b", "a/b/c,2,c"})
  public void getNameMethod(String data, int index, String expected) {
    Path path = createPath(data);
    assertEquals(expected, path.getName(index).toString());
  }

  @Test(expected = IllegalArgumentException.class)
  @Parameters({"a,-1", "/a,-1", ",0", "/,0", "a/b,2", "/a/b,2", "/a/b/c/d,1000"})
  public void getNameMethodException(String data, int index) {
    Path path = createPath(data);
    path.getName(index);
  }

  @Test
  @Parameters({",0", "/,0", "a,1", "/a,1", "/a/b/c,3"})
  public void getNameCountMethod(String data, int expected) {
    Path path = createPath(data);
    assertEquals(expected, path.getNameCount());
  }

  @Test
  @Parameters({",null", "/,null", "a,null", "/a/b,/a", "a/b,a", "a/b/c,a/b"})
  public void getParentMethod(String data, String expected) {
    Path path = createPath(data);
    Path parent = path.getParent();
    if (expected.equals("null")) {
      assertNull(parent);
    } else {
      assertEquals(expected, parent.toString());
    }
  }

  @Test
  @Parameters({",null", "/,/", "a,null", "/a,/", "a/b/c,null", "/a/b/c,/"})
  public void getRootMethod(String data, String expected) {
    Path path = createPath(data);
    Path root = path.getRoot();
    if (expected.equals("null")) {
      assertNull(root);
    } else {
      assertEquals(expected, root.toString());
    }
  }

  @Test
  @Parameters({"", "/", "/a", "a", "a/b/c", "/a/b/c"})
  public void hashcodeMethodEquals(String data) {
    String data1 = new String(data);
    String data2 = new String(data);
    Path path1 = createPath(data1);
    Path path2 = createPath(data2);
    assertEquals(path1.hashCode(), path2.hashCode());
  }

  @Test
  @Parameters({",false", "/,true", "a,false", "/a,true", "a/b/c,false", "/a/b/c,true"})
  public void isAbsoluteMethod(String data, boolean expected) {
    Path path = createPath(data);
    assertEquals(expected, path.isAbsolute());
  }

  private Object[] iteratorMethodData() {
    return new Object[] {
      new Object[] {"", new String[] {}},
      new Object[] {"/", new String[] {}},
      new Object[] {"/a", new String[] {"a"}},
      new Object[] {"a", new String[] {"a"}},
      new Object[] {"/a/b/c", new String[] {"a", "b", "c"}},
      new Object[] {"a/b/c", new String[] {"a", "b", "c"}}
    };
  }

  @Test
  @Parameters(method = "iteratorMethodData")
  public void iteratorMethod(String data, String[] expected) {
    Path path = createPath(data);
    List<Path> list = new ArrayList<>();
    path.iterator().forEachRemaining(list::add);

    assertArrayEquals(expected, list.stream().map(Path::toString).toArray(String[]::new));
  }

  @Test
  @Parameters({
    ",",
    "/,/",
    "a/b/c,a/b/c",
    "/a/b,/a/b",
    "a/..,",
    "/a/..,/",
    "/a/.,/a",
    "a/././b/././c,a/b/c",
    "a/b/..,a",
    "a/b/c/../d/../e,a/b/e",
    "a/b/./../c/../d/.,a/d"
  })
  public void normalizeMethod(String data, String expected) {
    Path path = createPath(data);
    assertEquals(expected, path.normalize().toString());
  }

  @Test
  @Parameters({"/a/b,/a,..", "/a/b,/a/b/c/d,c/d", "a/b,a/b/c/d,c/d", "/a,/a,"})
  public void relativizeMethod(String data1, String data2, String expected) {
    Path path1 = createPath(data1);
    Path path2 = createPath(data2);
    assertEquals(expected, path1.relativize(path2).toString());
  }

  @Test
  @Parameters({"/a/b,c,/a/b/c", "/a,/b,/b", "/a/b,c/d,/a/b/c/d", "a/b,c/d,a/b/c/d"})
  public void resolveMethod(String data1, String data2, String expected) {
    Path path1 = createPath(data1);
    Path path2 = createPath(data2);
    assertEquals(expected, path1.resolve(path2).toString());
  }

  @Test
  @Parameters({
    ",,true",
    "/,/,true",
    "path/1/2,path/1,true",
    "/,,false",
    ",/,false",
    "/path/1,/path/1,true",
    "/path1/1,/path1,true",
    "/path1/1/2,/path1/1/3,false",
    "/path1/1/2,path1/1/2,false",
    "path1/1,1,false"
  })
  public void startsWithMethod(String data1, String data2, boolean expected) {
    Path path1 = createPath(data1);
    Path path2 = createPath(data2);
    assertEquals(expected, path1.startsWith(path2));
  }

  @Test
  @Parameters({
    "a,0,1,a",
    "/a,0,1,a",
    "/a/b/c/d,1,3,b/c",
    "a/b/c/d,1,3,b/c",
    "/a/b,1,2,b",
    "a/b,1,2,b"
  })
  public void subpathMethod(String data, int beginIndex, int endIndex, String expected) {
    Path path = createPath(data);
    assertEquals(expected, path.subpath(beginIndex, endIndex).toString());
  }

  @Test(expected = IllegalArgumentException.class)
  @Parameters({",0,1", "a,-1,1", "/a,-1,1", "a,0,2", "/a,0,2", "a/b,1,0", "/a/b/c/d,0,5"})
  public void subpathMethodException(String data, int beginIndex, int endIndex) {
    Path path = createPath(data);
    path.subpath(beginIndex, endIndex);
  }
}
