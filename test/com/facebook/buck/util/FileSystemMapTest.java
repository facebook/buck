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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.FileSystemMap.Entry;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class FileSystemMapTest {

  private FileSystemMap.ValueLoader<Boolean> loader = path -> true;

  @Test
  public void testPutLeafNodeWithEmptyTrie() {
    Path path = Paths.get("foo/bar/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.put(path, true);
    FileSystemMap.Entry<Boolean> foo = fsMap.root.subLevels.get("foo");
    assertNotNull(foo);
    FileSystemMap.Entry<Boolean> bar = foo.subLevels.get("bar");
    assertNotNull(bar);
    FileSystemMap.Entry<Boolean> file = bar.subLevels.get("HelloWorld.java");
    assertNotNull(file);
    assertTrue(file.getWithoutLoading());
    assertEquals(fsMap.map.size(), 1);
    assertTrue(fsMap.map.containsKey(path));
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodeWithNonEmptyTrie() {
    Path path = Paths.get("foo/bar/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"));
    fsMap.root.subLevels.put("usr", usr);
    fsMap.map.put(usr.getKey(), usr);
    fsMap.put(path, true);
    assertEquals(fsMap.root.subLevels.get("usr").size(), 0);
    Entry<Boolean> foo = fsMap.root.subLevels.get("foo");
    assertNotNull(foo);
    Entry<Boolean> bar = foo.subLevels.get("bar");
    assertNotNull(bar);
    Entry<Boolean> file = bar.subLevels.get("HelloWorld.java");
    assertNotNull(file);
    assertTrue(file.getWithoutLoading());
    assertEquals(fsMap.map.size(), 2);
    assertTrue(fsMap.map.containsKey(path));
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodeAlreadyInserted() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"));
    fsMap.root.subLevels.put("usr", usr);
    FileSystemMap.Entry<Boolean> helloWorld = new FileSystemMap.Entry<>(path, true);
    usr.subLevels.put("HelloWorld.java", helloWorld);
    fsMap.map.put(path, helloWorld);
    fsMap.put(path, false);
    // We check that the object hasn't been reinstantiated => reference is the same.
    assertSame(fsMap.root.subLevels.get("usr"), usr);
    assertSame(usr.subLevels.get("HelloWorld.java"), helloWorld);
    Entry<Boolean> helloWorldEntry = usr.subLevels.get("HelloWorld.java");
    assertNotNull(helloWorldEntry);
    assertFalse(helloWorldEntry.getWithoutLoading());
    assertEquals(fsMap.map.size(), 1);
    assertTrue(fsMap.map.containsKey(path));
    assertFalse(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodePathPartiallyInserted() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"));
    fsMap.root.subLevels.put("usr", usr);
    fsMap.put(path, true);
    // We check that the object hasn't been reinstantiated => reference is the same.
    assertSame(fsMap.root.subLevels.get("usr"), usr);
    Entry<Boolean> file = usr.subLevels.get("HelloWorld.java");
    assertNotNull(file);
    assertTrue(file.getWithoutLoading());
    assertEquals(fsMap.map.size(), 1);
    assertTrue(fsMap.map.containsKey(path));
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testRemovePathThatExistsAndIntermediateNodesAreRemovedToo() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"));
    fsMap.root.subLevels.put("usr", usr);
    FileSystemMap.Entry<Boolean> helloWorld = new FileSystemMap.Entry<>(path, true);
    fsMap.map.put(helloWorld.getKey(), helloWorld);
    usr.subLevels.put("HelloWorld.java", helloWorld);
    fsMap.remove(path);
    assertFalse(fsMap.root.subLevels.containsKey("usr"));
    assertFalse(usr.subLevels.containsKey("HelloWorld.java"));
    assertEquals(fsMap.map.size(), 0);
  }

  @Test
  public void testRemovePathThatExistsAndIntermediateIsNotRemovedButValueIsRemoved() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"), true);
    fsMap.map.put(usr.getKey(), usr);
    fsMap.root.subLevels.put("usr", usr);

    FileSystemMap.Entry<Boolean> helloWorld = new FileSystemMap.Entry<>(path, true);
    fsMap.map.put(helloWorld.getKey(), helloWorld);
    usr.subLevels.put("HelloWorld.java", helloWorld);

    FileSystemMap.Entry<Boolean> yo = new FileSystemMap.Entry<>(Paths.get("usr/Yo.java"), true);
    fsMap.map.put(yo.getKey(), yo);
    usr.subLevels.put("Yo.java", yo);

    fsMap.remove(path);
    assertTrue(fsMap.root.subLevels.containsKey("usr"));
    assertNull(fsMap.root.subLevels.get("usr").getWithoutLoading());
    assertFalse(usr.subLevels.containsKey("HelloWorld.java"));
    assertTrue(usr.subLevels.containsKey("Yo.java"));
    assertEquals(fsMap.map.size(), 1);
    assertTrue(fsMap.map.containsKey(yo.getKey()));
    assertTrue(fsMap.map.get(yo.getKey()).getWithoutLoading());
  }

  @Test
  public void testRemovePathThatDoesntExist() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"));
    fsMap.root.subLevels.put("usr", usr);
    fsMap.map.put(path, usr);
    fsMap.remove(path);
    assertFalse(fsMap.root.subLevels.containsKey("usr"));
    assertEquals(fsMap.map.size(), 1);
    assertTrue(fsMap.map.containsKey(path));
  }

  @Test
  public void testRemoveIntermediateNode() {
    Path path = Paths.get("usr");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(path, true);
    fsMap.map.put(usr.getKey(), usr);
    fsMap.root.subLevels.put("usr", usr);

    FileSystemMap.Entry<Boolean> helloWorld =
        new FileSystemMap.Entry<>(Paths.get("usr/HelloWorld.java"), true);
    fsMap.map.put(helloWorld.getKey(), helloWorld);
    usr.subLevels.put("HelloWorld.java", helloWorld);

    FileSystemMap.Entry<Boolean> yo = new FileSystemMap.Entry<>(Paths.get("usr/Yo.java"), true);
    fsMap.map.put(yo.getKey(), yo);
    usr.subLevels.put("Yo.java", yo);

    fsMap.remove(path);
    assertFalse(fsMap.root.subLevels.containsKey("usr"));
    assertFalse(fsMap.map.containsKey(path));
    assertFalse(fsMap.map.containsKey(Paths.get("usr/HelloWorld.java")));
    assertFalse(fsMap.map.containsKey(Paths.get("usr/Yo.java")));
  }

  @Test
  public void testRemoveAll() {
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"));
    fsMap.map.put(usr.getKey(), usr);
    fsMap.root.subLevels.put("usr", usr);

    FileSystemMap.Entry<Boolean> helloWorld =
        new FileSystemMap.Entry<>(Paths.get("usr/HelloWorld.java"), true);
    fsMap.map.put(helloWorld.getKey(), helloWorld);
    usr.subLevels.put("HelloWorld.java", helloWorld);

    FileSystemMap.Entry<Boolean> yo = new FileSystemMap.Entry<>(Paths.get("usr/Yo.java"), true);
    fsMap.map.put(yo.getKey(), yo);
    usr.subLevels.put("Yo.java", yo);

    fsMap.removeAll();
    assertEquals(fsMap.root.size(), 0);
    assertEquals(fsMap.map.size(), 0);
  }

  @Test
  public void testRemoveAllWithEmptyTrie() {
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    fsMap.removeAll();
    assertEquals(fsMap.root.size(), 0);
    assertEquals(fsMap.map.size(), 0);
  }

  @Test
  public void testGetWithPathThatExists() throws IOException {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"));
    fsMap.root.subLevels.put("usr", usr);
    FileSystemMap.Entry<Boolean> helloWorld = new FileSystemMap.Entry<>(path, true);
    usr.subLevels.put("HelloWorld.java", helloWorld);
    assertTrue(fsMap.get(path));
    assertEquals(fsMap.root.size(), 1);
    assertTrue(fsMap.map.containsKey(path));
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testGetAtRootLevelWithPathThatExists() throws IOException {
    Path path = Paths.get("HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> helloWorld = new FileSystemMap.Entry<>(path, true);
    fsMap.root.subLevels.put("HelloWorld.java", helloWorld);
    assertTrue(fsMap.get(path));
    assertEquals(fsMap.root.size(), 1);
    assertTrue(fsMap.map.containsKey(path));
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testGetWithPathDoesntExist() throws IOException {
    Path path = Paths.get("usr/GoodbyeCruelWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader);
    FileSystemMap.Entry<Boolean> usr = new FileSystemMap.Entry<>(Paths.get("usr"));
    fsMap.root.subLevels.put("usr", usr);
    FileSystemMap.Entry<Boolean> helloWorld =
        new FileSystemMap.Entry<>(Paths.get("usr/HelloWorld.java"), true);
    usr.subLevels.put("HelloWorld.java", helloWorld);
    fsMap.map.put(helloWorld.getKey(), helloWorld);
    Boolean entry = fsMap.get(path);
    assertNotNull(entry);
    assertTrue(entry);
    assertEquals(fsMap.map.size(), 2);
    assertTrue(fsMap.map.containsKey(path));
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }
}
