/*
 * Copyright 2012-present Facebook, Inc.
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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PathsTest {

  @Test
  public void testComputeRelativePath() {
    assertEquals("foo/bar", Paths.computeRelativePath("", "foo/bar"));
    assertEquals("foo/bar/", Paths.computeRelativePath("", "foo/bar/"));

    assertEquals("../baz/", Paths.computeRelativePath("foo/bar/", "foo/baz/"));
    assertEquals("../../../", Paths.computeRelativePath("a/b/c/d/e/", "a/b/"));
    assertEquals("c/d/e/", Paths.computeRelativePath("a/b/", "a/b/c/d/e/"));
    assertEquals("../../../1/2/3/", Paths.computeRelativePath("a/b/c/d/e/f/", "a/b/c/1/2/3/"));

    assertEquals("foo/bar", Paths.computeRelativePath("/prefix/who/cares/",
        "/prefix/who/cares/foo/bar"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testComputeRelativePathThrows() {
    Paths.computeRelativePath("project.properties", "foo/bar.txt");
  }

  @Test
  public void testGetParentPath() {
    assertEquals("", Paths.getParentPath("project.properties"));
    assertEquals("foo/", Paths.getParentPath("foo/bar"));
    assertEquals("foo/", Paths.getParentPath("foo/bar/"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetParentPathThrows() {
    Paths.getParentPath("");
  }

  @Test(expected = NullPointerException.class)
  public void testGetBasenameThrows() {
    Paths.getBasename(null, ".json");
  }

  @Test
  public void testGetBasenameWithSuffix() {
    assertEquals("bar", Paths.getBasename("bar.json", ".json"));
    assertEquals("bar", Paths.getBasename("/foo/bar.json", ".json"));
    assertEquals("bar.simple", Paths.getBasename("/foo/bar.simple", ".json"));
  }

  @Test
  public void testGetBasenameWithoutSuffix() {
    assertEquals("bar.json", Paths.getBasename("/foo/bar.json", null));
    assertEquals("bar", Paths.getBasename("/foo/bar", ""));
  }

  @Test
  public void testNormalizePathSeparator() {
    assertEquals("C:/Windows/System32/drivers.dll",
        Paths.normalizePathSeparator("C:\\Windows\\System32\\drivers.dll"));
  }

  @Test
  public void testContainsBackslash() {
    assertTrue(Paths.containsBackslash("C:\\Windows\\System32\\drivers.dll"));
    assertFalse(Paths.containsBackslash("C:/Windows"));
    assertFalse(Paths.containsBackslash("/usr/bin"));
  }
}
