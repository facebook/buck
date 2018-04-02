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
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BuckUnixPathTest {

  private BuckUnixPath createPath(String pathString) {
    BuckFileSystemProvider provider =
        new BuckFileSystemProvider(FileSystems.getDefault().provider());
    Path path = provider.getFileSystem(URI.create("file:///")).getPath(pathString);
    assertTrue(path instanceof BuckUnixPath);
    return (BuckUnixPath) path;
  }

  @Test
  public void toStringMethod() {
    String data = "/";
    Path path = createPath(data);
    assertEquals(data, path.toString());
  }

  @Test
  public void compareToMethod() {
    String data1 = "/";
    String data2 = "/";
    int expected = 0;

    Path path1 = createPath(data1);
    Path path2 = createPath(data2);

    assertEquals(expected, path1.compareTo(path2));
  }

  @Test
  public void endsWithMethod() {
    String data1 = "/path/1/2";
    String data2 = "1/2";
    boolean expected = true;

    Path path1 = createPath(data1);
    Path path2 = createPath(data2);

    assertEquals(expected, path1.endsWith(path2));
  }

  @Test
  public void equalsMethod() {
    String data1 = "/path/to/something";
    String data2 = "/path/to/something";
    boolean expected = true;

    Path path1 = createPath(data1);
    Path path2 = createPath(data2);

    assertEquals(expected, path1.equals(path2));
  }

  @Test
  public void getFileNameMethod() {
    String data = "/path/to/something";
    String expected = "something";

    Path path = createPath(data);

    assertEquals(expected, path.getFileName().toString());
  }

  @Test
  public void getFileSystemMethod() {
    String data = "/path/to/something";

    Path path = createPath(data);

    assertTrue(path.getFileSystem() instanceof BuckFileSystem);
  }

  @Test
  public void getNameMethod() {
    String data = "/path/to/something";
    int index = 1;
    String expected = "to";

    Path path = createPath(data);

    assertEquals(expected, path.getName(index).toString());
  }

  @Test
  public void getNameCountMethod() {
    String data = "/path/to/something";
    int expected = 3;

    Path path = createPath(data);

    assertEquals(expected, path.getNameCount());
  }

  @Test
  public void getParentMethod() {
    String data = "/path/to/something";
    String expected = "/path/to";

    Path path = createPath(data);

    assertEquals(expected, path.getParent().toString());
  }

  @Test
  public void getRootMethod() {
    String data = "/path/to/something";
    String expected = "/";

    Path path = createPath(data);

    assertEquals(expected, path.getRoot().toString());
  }

  @Test
  public void hashcodeMethod() {
    String data1 = "/";
    String data2 = "/";

    Path path1 = createPath(data1);
    Path path2 = createPath(data2);

    assertEquals(path1.hashCode(), path2.hashCode());
  }

  @Test
  public void isAbsoluteMethod() {
    String data = "/path/to/something";
    boolean expected = true;

    Path path = createPath(data);

    assertEquals(expected, path.isAbsolute());
  }

  @Test
  public void iteratorMethod() {
    String data = "/path/to/something";
    String[] expected = {"path", "to", "something"};

    Path path = createPath(data);

    List<Path> list = new ArrayList<>();
    path.iterator().forEachRemaining(list::add);

    assertArrayEquals(expected, list.stream().map(Path::toString).toArray(String[]::new));
  }

  @Test
  public void normalizeMethod() {
    String data = "/path/to/something/../something/.";
    String expected = "/path/to/something";

    Path path = createPath(data);

    assertEquals(expected, path.normalize().toString());
  }

  @Test
  public void relativizeMethod() {
    String data1 = "/path/to/something/";
    String data2 = "/path/to";
    String expected = "..";

    Path path1 = createPath(data1);
    Path path2 = createPath(data2);

    assertEquals(expected, path1.relativize(path2).toString());
  }

  @Test
  public void resolveMethod() {
    String data1 = "/path/to";
    String data2 = "something";
    String expected = "/path/to/something";

    Path path1 = createPath(data1);
    Path path2 = createPath(data2);

    assertEquals(expected, path1.resolve(path2).toString());
  }

  @Test
  public void startsWithMethod() {
    String data1 = "/path/1/2";
    String data2 = "/path/1";
    boolean expected = true;

    Path path1 = createPath(data1);
    Path path2 = createPath(data2);

    assertEquals(expected, path1.startsWith(path2));
  }

  @Test
  public void subpathMethod() {
    String data = "/path/to/something/great";
    int beginIndex = 1;
    int endIndex = 3;
    String expected = "to/something";

    Path path = createPath(data);

    assertEquals(expected, path.subpath(beginIndex, endIndex).toString());
  }
}
