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

package com.facebook.buck.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTarget;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class BuildTargetTest {

  private static final Path ROOT = Paths.get("/opt/src/buck");

  @Test
  public void testRootBuildTarget() {
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT, "//", "fb4a");
    assertEquals("fb4a", rootTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", rootTarget.getBaseName());
    assertEquals(Paths.get(""), rootTarget.getBasePath());
    assertEquals("//:fb4a", rootTarget.getFullyQualifiedName());
    assertEquals("//:fb4a", rootTarget.toString());
  }

  @Test
  public void testBuildTargetTwoLevelsDeep() {
    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT, "//java/com/facebook", "fb4a");
    assertEquals("fb4a", rootTarget.getShortNameAndFlavorPostfix());
    assertEquals("//java/com/facebook", rootTarget.getBaseName());
    assertEquals(Paths.get("java/com/facebook"), rootTarget.getBasePath());
    assertEquals("//java/com/facebook:fb4a", rootTarget.getFullyQualifiedName());
    assertEquals("//java/com/facebook:fb4a", rootTarget.toString());
  }

  @Test
  public void testEqualsNullReturnsFalse() {
    BuildTarget utilTarget =
        BuildTargetFactory.newInstance(ROOT, "//src/com/facebook/buck/util", "util");
    assertNotNull(utilTarget);
  }

  @Test
  public void testEqualsOtherBuildTarget() {
    BuildTarget utilTarget1 =
        BuildTargetFactory.newInstance(ROOT, "//src/com/facebook/buck/util", "util");
    assertEquals(utilTarget1, utilTarget1);

    BuildTarget utilTarget2 =
        BuildTargetFactory.newInstance(ROOT, "//src/com/facebook/buck/util", "util");
    assertEquals(utilTarget1, utilTarget2);
  }

  @Test
  public void testNotEquals() {
    BuildTarget utilTarget =
        BuildTargetFactory.newInstance(ROOT, "//src/com/facebook/buck/util", "util");
    BuildTarget ioTarget =
        BuildTargetFactory.newInstance(ROOT, "//src/com/facebook/buck/util", "io");
    assertFalse(utilTarget.equals(ioTarget));
  }

  @Test
  public void testBuildTargetWithFlavor() {
    BuildTarget target =
        BuildTargetFactory.newInstance(ROOT, "//foo/bar", "baz", InternalFlavor.of("dex"));
    assertEquals("baz#dex", target.getShortNameAndFlavorPostfix());
    assertEquals(ImmutableSortedSet.of(InternalFlavor.of("dex")), target.getFlavors());
    assertTrue(target.isFlavored());
  }

  @Test
  public void testBuildTargetWithoutFlavor() {
    BuildTarget target = BuildTargetFactory.newInstance(ROOT, "//foo/bar", "baz");
    assertEquals(target.getShortNameAndFlavorPostfix(), "baz");
    assertEquals(ImmutableSortedSet.<Flavor>of(), target.getFlavors());
    assertFalse(target.isFlavored());
  }

  @Test
  public void testFlavorIsValid() {
    try {
      BuildTargetFactory.newInstance(ROOT, "//foo/bar", "baz", InternalFlavor.of("d!x"));
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Invalid characters in flavor name: d!x", e.getMessage());
    }
  }

  @Test
  public void testShortNameCannotContainHashWhenFlavorSet() {
    try {
      BuildTargetFactory.newInstance(ROOT, "//foo/bar", "baz#dex", InternalFlavor.of("src-jar"));
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Build target name cannot contain '#' but was: baz#dex.", e.getMessage());
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testShortNamesMustNotContainTheFlavorSeparator() {
    @SuppressWarnings("unused")
    BuildTarget unused = BuildTargetFactory.newInstance(ROOT, "//foo/bar", "baz#dex");
  }

  @Test
  public void testFlavorDefaultsToNoneIfNotSet() {
    assertEquals(
        ImmutableSet.<Flavor>of(),
        BuildTargetFactory.newInstance(ROOT, "//foo/bar", "baz").getFlavors());
  }

  @Test
  public void testGetUnflavoredTarget() {
    UnflavoredBuildTarget unflavoredTarget =
        ImmutableUnflavoredBuildTarget.builder()
            .setBaseName("//foo/bar")
            .setShortName("baz")
            .setCellPath(ROOT)
            .build();

    BuildTarget flavoredTarget =
        BuildTargetFactory.newInstance(ROOT, "//foo/bar", "baz", InternalFlavor.of("biz"));
    assertEquals(unflavoredTarget, flavoredTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testNumbersAreValidFlavors() {
    @SuppressWarnings("unused")
    BuildTarget unused =
        BuildTargetFactory.newInstance(ROOT, "//foo", "bar", InternalFlavor.of("1234"));
  }

  @Test
  public void testAppendingFlavors() {
    Flavor aaa = InternalFlavor.of("aaa");
    Flavor biz = InternalFlavor.of("biz");

    BuildTarget flavoredTarget = BuildTargetFactory.newInstance(ROOT, "//foo/bar", "baz", biz);
    BuildTarget appendedFlavor = flavoredTarget.withAppendedFlavors(aaa);
    assertThat(appendedFlavor, Matchers.not(Matchers.equalTo(flavoredTarget)));
    ImmutableSortedSet<Flavor> expectedFlavors = ImmutableSortedSet.of(biz, aaa);
    assertThat(appendedFlavor.getFlavors(), Matchers.equalTo(expectedFlavors));
  }

  @Test
  public void unflavoredBuildTargetsAreInterned() {
    UnflavoredBuildTarget target1 =
        ImmutableUnflavoredBuildTarget.builder()
            .setCellPath(ROOT)
            .setBaseName("//foo")
            .setShortName("bar")
            .build();
    UnflavoredBuildTarget target2 =
        ImmutableUnflavoredBuildTarget.builder()
            .setCellPath(ROOT)
            .setBaseName("//foo")
            .setShortName("bar")
            .build();
    assertSame(target1, target2);
  }
}
