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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.file.Paths;

public class DefaultJavaPackageFinderTest {

  /**
   * If no paths are specified, then the project root should be considered a Java source root.
   */
  @Test
  public void testNoPathsSpecified() {
    DefaultJavaPackageFinder javaPackageFinder = DefaultJavaPackageFinder
        .createDefaultJavaPackageFinder(ImmutableList.<String>of());
    assertEquals(
        Paths.get(""),
        javaPackageFinder.findJavaPackageFolder(Paths.get("Base.java")));
    assertEquals(
        Paths.get("java/com/example/base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("java/com/example/base/Base.java")));
  }

  @Test
  public void testSinglePathFromRoot() {
    DefaultJavaPackageFinder javaPackageFinder = DefaultJavaPackageFinder
        .createDefaultJavaPackageFinder(ImmutableList.of("/java/"));
    assertEquals(
        Paths.get("com/example/base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("java/com/example/base/Base.java")));
    assertEquals("com.example.base",
        javaPackageFinder.findJavaPackage(Paths.get("java/com/example/base/Base.java")));
    assertEquals(
        Paths.get(""),
        javaPackageFinder.findJavaPackageFolder(Paths.get("java/Weird.java")));
    assertEquals("",
        javaPackageFinder.findJavaPackage(Paths.get("java/Weird.java")));
    assertEquals(
        "When there is no match, the project root should be treated as a Java source root.",
        Paths.get("notjava/"),
        javaPackageFinder.findJavaPackageFolder(Paths.get("notjava/Weird.java")));
    assertEquals(
        "When there is no match, the project root should be treated as a Java source root.",
        "notjava",
        javaPackageFinder.findJavaPackage(Paths.get("notjava/Weird.java")));
  }

  @Test
  public void testOverlappingPathsFromRoot() {
    DefaultJavaPackageFinder javaPackageFinder = DefaultJavaPackageFinder
        .createDefaultJavaPackageFinder(ImmutableList.of(
            "/java",
            "/java/more/specific",
            "/javatests"));
    assertEquals(
        Paths.get("com/example/base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("java/com/example/base/Base.java")));
    assertEquals(
        Paths.get("base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("java/more/specific/base/Base.java")));
    assertEquals(
        Paths.get("com/example/base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("javatests/com/example/base/BaseTest.java")));
  }

  @Test
  public void testSinglePathElement() {
    DefaultJavaPackageFinder javaPackageFinder = DefaultJavaPackageFinder
        .createDefaultJavaPackageFinder(ImmutableList.of("src"));
    assertEquals(
        Paths.get("com/example/base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("java/main/src/com/example/base/Base.java")));
    assertEquals(
        "When the path element appears more than once, use the rightmost instance as the base.",
        Paths.get("com/example/base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("java/main/src/other/project/src/com/example/base/Base.java")));
    assertEquals(
        Paths.get(""),
        javaPackageFinder.findJavaPackageFolder(Paths.get("src/Weird.java")));
    assertEquals(
        "When there is no match, the project root should be treated as a Java source root.",
        Paths.get("notjava/"),
        javaPackageFinder.findJavaPackageFolder(Paths.get("notjava/Weird.java")));
  }

  @Test
  public void testMultiplePathElements() {
    DefaultJavaPackageFinder javaPackageFinder = DefaultJavaPackageFinder
        .createDefaultJavaPackageFinder(ImmutableList.of("src", "src-gen"));
    assertEquals(
        Paths.get("com/example/base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("java/main/src-gen/other/project/src/com/example/base/Base.java")));
  }

  @Test
  public void testMixOfPrefixesAndPathElements() {
    DefaultJavaPackageFinder javaPackageFinder = DefaultJavaPackageFinder
        .createDefaultJavaPackageFinder(ImmutableList.of("/java", "src"));
    assertEquals(
        "Prefixes take precedence over path elements",
        Paths.get("com/example/base/"),
        javaPackageFinder.findJavaPackageFolder(
            Paths.get("/java/main/src/com/example/base/Base.java")));
  }

  @Test
  public void testInvalidPath() {
    try {
      DefaultJavaPackageFinder.createDefaultJavaPackageFinder(ImmutableList.of("src/"));
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(
          "Path pattern that does not start with a slash cannot contain a slash: src/",
          e.getHumanReadableErrorMessage());
    }
  }
}
