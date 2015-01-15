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

package com.facebook.buck.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class BuildTargetTest {

  @Test
  public void testRootBuildTarget() {
    BuildTarget rootTarget = BuildTarget.builder("//", "fb4a").build();
    assertEquals("fb4a", rootTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", rootTarget.getBaseName());
    assertEquals("//", rootTarget.getBaseNameWithSlash());
    assertEquals(Paths.get(""), rootTarget.getBasePath());
    assertEquals("", rootTarget.getBasePathWithSlash());
    assertEquals("//:fb4a", rootTarget.getFullyQualifiedName());
    assertEquals("//:fb4a", rootTarget.toString());
  }

  @Test
  public void testBuildTargetTwoLevelsDeep() {
    BuildTarget rootTarget = BuildTarget.builder("//java/com/facebook", "fb4a").build();
    assertEquals("fb4a", rootTarget.getShortNameAndFlavorPostfix());
    assertEquals("//java/com/facebook", rootTarget.getBaseName());
    assertEquals("//java/com/facebook/", rootTarget.getBaseNameWithSlash());
    assertEquals(Paths.get("java/com/facebook"), rootTarget.getBasePath());
    assertEquals("java/com/facebook/", rootTarget.getBasePathWithSlash());
    assertEquals("//java/com/facebook:fb4a", rootTarget.getFullyQualifiedName());
    assertEquals("//java/com/facebook:fb4a", rootTarget.toString());
  }

  @Test
  public void testBuildTargetWithBackslash() throws IOException {
    BuildTarget rootTargetWithBackslash = BuildTarget.builder(
        "//com\\microsoft\\windows",
        "something").build();
    assertEquals("//com/microsoft/windows", rootTargetWithBackslash.getBaseName());
  }

  @Test
  public void testEqualsNullReturnsFalse() {
    BuildTarget utilTarget = BuildTarget.builder("//src/com/facebook/buck/util", "util").build();
    assertNotNull(utilTarget);
  }

  @Test
  public void testEqualsOtherBuildTarget() {
    BuildTarget utilTarget1 = BuildTarget.builder("//src/com/facebook/buck/util", "util").build();
    assertEquals(utilTarget1, utilTarget1);

    BuildTarget utilTarget2 = BuildTarget.builder("//src/com/facebook/buck/util", "util").build();
    assertEquals(utilTarget1, utilTarget2);
  }

  @Test
  public void testNotEquals() {
    BuildTarget utilTarget = BuildTarget.builder("//src/com/facebook/buck/util", "util").build();
    BuildTarget ioTarget = BuildTarget.builder("//src/com/facebook/buck/util", "io").build();
    assertFalse(utilTarget.equals(ioTarget));
  }

  @Test
  public void testBuildTargetWithFlavor() {
    BuildTarget target = BuildTarget.builder("//foo/bar", "baz").setFlavor("dex").build();
    assertEquals("baz#dex", target.getShortNameAndFlavorPostfix());
    assertEquals(ImmutableSortedSet.of(new Flavor("dex")), target.getFlavors());
    assertTrue(target.isFlavored());
  }

  @Test
  public void testBuildTargetWithoutFlavor() {
    BuildTarget target = BuildTarget.builder("//foo/bar", "baz").build();
    assertEquals(target.getShortNameAndFlavorPostfix(), "baz");
    assertEquals(ImmutableSortedSet.<Flavor>of(), target.getFlavors());
    assertFalse(target.isFlavored());
  }

  @Test
  public void testFlavorIsValid() {
    try {
      BuildTarget.builder("//foo/bar", "baz").addFlavor("d!x").build();
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Invalid flavor: d!x", e.getMessage());
    }
  }

  @Test
  public void testShortNameCannotContainHashWhenFlavorSet() {
    try {
      BuildTarget.builder("//foo/bar", "baz#dex").addFlavor("src-jar").build();
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Build target name cannot contain '#' but was: baz#dex.", e.getMessage());
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testShortNamesMustNotContainTheFlavorSeparator() {
      BuildTarget.builder("//foo/bar", "baz#dex").build();
  }

  @Test
  public void testFlavorDefaultsToNoneIfNotSet() {
    assertEquals(
        ImmutableSet.<Flavor>of(),
        BuildTarget.builder("//foo/bar", "baz").build().getFlavors());
  }

  @Test
  public void testGetUnflavoredTarget() {
    BuildTarget unflavoredTarget = BuildTarget.builder("//foo/bar", "baz").build();
    assertSame(unflavoredTarget, unflavoredTarget.getUnflavoredTarget());

    BuildTarget flavoredTarget = BuildTarget.builder("//foo/bar", "baz").addFlavor("biz").build();
    assertEquals(unflavoredTarget, flavoredTarget.getUnflavoredTarget());
  }

  @Test
  public void testNumbersAreValidFlavors() {
    BuildTarget.builder("//foo", "bar")
        .addFlavor(new Flavor("1234"))
        .build();
  }

}
