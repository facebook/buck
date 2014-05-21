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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public class BuildTargetTest {

  @Test
  public void testRootBuildTarget() {
    BuildTarget rootTarget = new BuildTarget("//", "fb4a");
    assertEquals("fb4a", rootTarget.getShortName());
    assertEquals("//", rootTarget.getBaseName());
    assertEquals("//", rootTarget.getBaseNameWithSlash());
    assertEquals("", rootTarget.getBasePath());
    assertEquals("", rootTarget.getBasePathWithSlash());
    assertEquals("//:fb4a", rootTarget.getFullyQualifiedName());
    assertEquals("//:fb4a", rootTarget.toString());
  }

  @Test
  public void testBuildTargetTwoLevelsDeep() {
    BuildTarget rootTarget = new BuildTarget("//java/com/facebook", "fb4a");
    assertEquals("fb4a", rootTarget.getShortName());
    assertEquals("//java/com/facebook", rootTarget.getBaseName());
    assertEquals("//java/com/facebook/", rootTarget.getBaseNameWithSlash());
    assertEquals("java/com/facebook", rootTarget.getBasePath());
    assertEquals("java/com/facebook/", rootTarget.getBasePathWithSlash());
    assertEquals("//java/com/facebook:fb4a", rootTarget.getFullyQualifiedName());
    assertEquals("//java/com/facebook:fb4a", rootTarget.toString());
  }

  @Test
  public void testBuildTargetWithBackslash() throws IOException {
    BuildTarget rootTargetWithBackslash = new BuildTarget(
        "//com\\microsoft\\windows",
        "something");
    assertEquals("//com/microsoft/windows", rootTargetWithBackslash.getBaseName());
  }

  @Test
  public void testEqualsNullReturnsFalse() {
    BuildTarget utilTarget = new BuildTarget("//src/com/facebook/buck/util", "util");
    assertFalse(utilTarget.equals(null));
  }

  @Test
  public void testEqualsOtherBuildTarget() {
    BuildTarget utilTarget1 = new BuildTarget("//src/com/facebook/buck/util", "util");
    assertEquals(utilTarget1, utilTarget1);

    BuildTarget utilTarget2 = new BuildTarget("//src/com/facebook/buck/util", "util");
    assertEquals(utilTarget1, utilTarget2);
  }

  @Test
  public void testNotEquals() {
    BuildTarget utilTarget = new BuildTarget("//src/com/facebook/buck/util", "util");
    BuildTarget ioTarget = new BuildTarget("//src/com/facebook/buck/util", "io");
    assertFalse(utilTarget.equals(ioTarget));
  }

  @Test
  public void testBuildTargetWithFlavor() {
    BuildTarget target = new BuildTarget("//foo/bar", "baz", "dex");
    assertEquals("baz#dex", target.getShortName());
    assertEquals(new Flavor("dex"), target.getFlavor());
    assertTrue(target.isFlavored());
  }

  @Test
  public void testBuildTargetWithoutFlavor() {
    BuildTarget target = new BuildTarget("//foo/bar", "baz");
    assertEquals(target.getShortName(), "baz");
    assertEquals(Flavor.DEFAULT, target.getFlavor());
    assertFalse(target.isFlavored());
  }

  @Test
  public void testFlavorIsValid() {
    try {
      new BuildTarget("//foo/bar", "baz", "d3x");
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Invalid flavor: d3x", e.getMessage());
    }
  }

  @Test
  public void testShortNameCannotContainHashWhenFlavorSet() {
    try {
      new BuildTarget("//foo/bar", "baz#dex", "src-jar");
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Build target name cannot contain '#' but was: baz#dex.", e.getMessage());
    }
  }

  @Test
  public void testFlavorDerivedFromShortNameIfAbsent() {
    assertEquals(new Flavor("dex"), new BuildTarget("//foo/bar", "baz#dex").getFlavor());
  }

  @Test
  public void testFlavorDefaultsToTheEmptyStringIfNotSet() {
    assertEquals(Flavor.DEFAULT, new BuildTarget("//foo/bar", "baz").getFlavor());
  }

  @Test
  public void testNotSettingTheFlavorInTheShortStringButLookingLikeYouMightIsTeasingAndWrong() {
    try {
      // Hilarious case that might result in an IndexOutOfBoundsException
      new BuildTarget("//foo/bar", "baz#");
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Invalid flavor: ", e.getMessage());
    }
  }
}
