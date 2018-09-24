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

package com.facebook.buck.util.filesystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.filesystem.FileSystemMap.Entry;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class FileSystemMapTest {

  private FileSystemMap.ValueLoader<Boolean> loader = path -> true;
  private FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void testPutLeafNodeWithEmptyTrie() {
    Path fooPath = Paths.get("foo");
    Path barPath = Paths.get("foo/bar");
    Path path = Paths.get("foo/bar/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);
    fsMap.put(path, true);
    FileSystemMap.Entry<Boolean> foo = fsMap.root.subLevels.get(fooPath);
    assertNotNull(foo);
    FileSystemMap.Entry<Boolean> bar = foo.subLevels.get(barPath);
    assertNotNull(bar);
    FileSystemMap.Entry<Boolean> file = bar.subLevels.get(path);
    assertNotNull(file);
    assertTrue(file.getWithoutLoading());
    assertEquals(1, fsMap.map.size());
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodeWithNonEmptyTrie() {
    Path fooPath = Paths.get("foo");
    Path barPath = Paths.get("foo/bar");
    Path usrPath = Paths.get("usr");
    Path path = Paths.get("foo/bar/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);

    // Set up the trie with one child and ensure the trie is in the state we want.
    fsMap.put(Paths.get("usr"), true);
    assertNotNull(fsMap.root.subLevels.get(usrPath));

    // Write the new entry and check data structure state.
    fsMap.put(path, true);
    assertEquals(0, fsMap.root.subLevels.get(usrPath).size());
    Entry<Boolean> file =
        fsMap.root.subLevels.get(fooPath).subLevels.get(barPath).subLevels.get(path);
    assertTrue(file.getWithoutLoading());
    assertEquals(2, fsMap.map.size());
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodeAlreadyInserted() {

    Path parent = Paths.get("usr");
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);

    // Insert the entry into the map, verify resulting state.
    fsMap.put(path, true);
    FileSystemMap.Entry<Boolean> usr = fsMap.root.subLevels.get(parent);
    Entry<Boolean> helloWorld = fsMap.map.get(path);
    assertTrue(helloWorld.getWithoutLoading());
    assertSame(helloWorld, fsMap.root.subLevels.get(parent).subLevels.get(path));

    // Insert the entry again with a different value.
    fsMap.put(path, false);

    // We check that the object hasn't been reinstantiated => reference is the same.
    assertSame(fsMap.root.subLevels.get(parent), usr);
    assertSame(usr.subLevels.get(path), helloWorld);
    Entry<Boolean> helloWorldEntry = usr.subLevels.get(path);
    assertNotNull(helloWorldEntry);
    assertFalse(helloWorldEntry.getWithoutLoading());
    assertEquals(fsMap.map.size(), 1);
    assertFalse(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testPutLeafNodePathPartiallyInserted() {
    Path parent = Paths.get("usr");
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);

    // Insert another entry with the same initial path.
    fsMap.put(Paths.get("usr/OtherPath"), false);
    FileSystemMap.Entry<Boolean> usr = fsMap.root.subLevels.get(parent);

    // Now insert the entry.
    fsMap.put(path, true);

    // We check that the object hasn't been reinstantiated => reference is the same.
    assertSame(fsMap.root.subLevels.get(parent), usr);
    Entry<Boolean> file = usr.subLevels.get(path);
    assertNotNull(file);
    assertTrue(file.getWithoutLoading());
    assertEquals(2, fsMap.map.size());
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testRemovePathThatExistsAndIntermediateNodesAreRemovedToo() {
    Path parent = Paths.get("usr");
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);

    // Insert the item and ensure data structure is correct.
    fsMap.put(path, true);
    assertTrue(fsMap.root.subLevels.get(parent).subLevels.get(path).getWithoutLoading());

    // Remove the item and check intermediate nodes are deleted.
    fsMap.remove(path);
    assertFalse(fsMap.root.subLevels != null && fsMap.root.subLevels.containsKey(parent));
    assertEquals(0, fsMap.map.size());
  }

  @Test
  public void testRemovePathThatExistsAndIntermediateIsNotRemovedButValueIsRemoved() {
    Path parent = Paths.get("usr");
    Path path1 = Paths.get("usr/HelloWorld.java");
    Path path2 = Paths.get("usr/Yo.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);
    fsMap.put(parent, true);
    fsMap.put(path1, true);
    fsMap.put(path2, true);

    fsMap.remove(path1);
    assertNull(fsMap.root.subLevels.get(parent).getWithoutLoading());
    assertFalse(fsMap.root.subLevels.get(parent).subLevels.containsKey(path1));
    assertTrue(fsMap.root.subLevels.get(parent).subLevels.containsKey(path2));
    assertEquals(2, fsMap.map.size());
    assertTrue(fsMap.map.get(path2).getWithoutLoading());
  }

  @Test
  public void testRemovePathThatDoesntExist() {
    Path parent = Paths.get("usr");
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);
    fsMap.put(parent, true);
    fsMap.remove(path);
    assertFalse(fsMap.root.subLevels != null && fsMap.root.subLevels.containsKey(parent));
    assertEquals(0, fsMap.map.size());
    assertFalse(fsMap.map.containsKey(path));
  }

  @Test
  public void testRemoveIntermediateNode() {
    Path parent = Paths.get("usr");
    Path path1 = Paths.get("usr/HelloWorld.java");
    Path path2 = Paths.get("usr/Yo.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);
    fsMap.put(parent, true);
    fsMap.put(path1, true);
    fsMap.put(path2, true);
    assertEquals(3, fsMap.map.size());

    fsMap.remove(parent);
    assertFalse(fsMap.root.subLevels != null && fsMap.root.subLevels.containsKey(parent));
    assertFalse(fsMap.map.containsKey(parent));
    assertFalse(fsMap.map.containsKey(path1));
    assertFalse(fsMap.map.containsKey(path2));
  }

  @Test
  public void testRemoveAll() {
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);
    fsMap.put(Paths.get("usr/HelloWorld.java"), true);
    fsMap.put(Paths.get("usr/Yo.java"), true);
    assertEquals(1, fsMap.root.size());
    assertEquals(2, fsMap.map.size());

    fsMap.removeAll();
    assertEquals(0, fsMap.root.size());
    assertEquals(0, fsMap.map.size());
  }

  @Test
  public void testRemoveAllWithEmptyTrie() {
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);
    fsMap.removeAll();
    assertEquals(fsMap.root.size(), 0);
    assertEquals(fsMap.map.size(), 0);
  }

  @Test
  public void testGetWithPathThatExists() {
    Path path = Paths.get("usr/HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);
    fsMap.put(path, true);
    assertTrue(fsMap.get(path));
    assertEquals(fsMap.root.size(), 1);
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testGetAtRootLevelWithPathThatExists() {
    Path path = Paths.get("HelloWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);
    fsMap.put(path, true);
    assertTrue(fsMap.get(path));
    assertEquals(fsMap.root.size(), 1);
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }

  @Test
  public void testGetWithPathDoesntExist() {
    Path path = Paths.get("usr/GoodbyeCruelWorld.java");
    FileSystemMap<Boolean> fsMap = new FileSystemMap<>(loader, filesystem);

    // Put a path that does exist.
    fsMap.put(Paths.get("usr/HelloWorld.java"), true);

    // Fetch a value that does not exist, see that it is loaded and cached in the map.
    Boolean entry = fsMap.get(path);
    assertNotNull(entry);
    assertTrue(entry);
    assertEquals(fsMap.map.size(), 2);
    assertTrue(fsMap.map.get(path).getWithoutLoading());
  }
}
