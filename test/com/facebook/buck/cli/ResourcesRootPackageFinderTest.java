/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.ResourcesRootPackageFinder;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ResourcesRootPackageFinderTest {
  @Test
  public void testResourcesPath() {
    Path resourcesRoot = Paths.get("java/example/resources");
    ResourcesRootPackageFinder finder = new ResourcesRootPackageFinder(
        resourcesRoot,
        new FakeJavaPackageFinder());
    assertEquals(
        Paths.get("com/facebook/"),
        finder.findJavaPackageFolder(
            Paths.get("java/example/resources/com/facebook/bar.txt")));
    assertEquals(
        "com.facebook",
        finder.findJavaPackage(Paths.get("java/example/resources/com/facebook/bar.txt")));
  }

  @Test
  public void testNonmatchingPath() {
    Path resourcesRoot = Paths.get("java/example/resources");
    ResourcesRootPackageFinder finder = new ResourcesRootPackageFinder(
        resourcesRoot,
        new FakeJavaPackageFinder());
    assertNull(
        "Should fall back to the FakeJavaPackageFinder and return null.",
        finder.findJavaPackageFolder(Paths.get("does/not/match.txt")));
  }
}
