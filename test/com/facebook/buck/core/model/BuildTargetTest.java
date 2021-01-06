/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class BuildTargetTest {

  private static final Path ROOT = Paths.get("/opt/src/buck");

  @Test
  public void testRootBuildTarget() {
    BuildTarget rootTarget = BuildTargetFactory.newInstance("//", "fb4a");
    assertEquals("fb4a", rootTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", rootTarget.getBaseName().toString());
    assertEquals(
        Paths.get(""), rootTarget.getCellRelativeBasePath().getPath().toPath(ROOT.getFileSystem()));
    assertEquals("//:fb4a", rootTarget.getFullyQualifiedName());
    assertEquals("//:fb4a", rootTarget.toString());
  }

  @Test
  public void testBuildTargetTwoLevelsDeep() {
    BuildTarget rootTarget = BuildTargetFactory.newInstance("//java/com/facebook", "fb4a");
    assertEquals("fb4a", rootTarget.getShortNameAndFlavorPostfix());
    assertEquals("//java/com/facebook", rootTarget.getBaseName().toString());
    assertEquals(
        Paths.get("java/com/facebook"),
        rootTarget.getCellRelativeBasePath().getPath().toPath(ROOT.getFileSystem()));
    assertEquals("//java/com/facebook:fb4a", rootTarget.getFullyQualifiedName());
    assertEquals("//java/com/facebook:fb4a", rootTarget.toString());
  }

  @Test
  public void testEqualsNullReturnsFalse() {
    BuildTarget utilTarget = BuildTargetFactory.newInstance("//src/com/facebook/buck/util", "util");
    assertNotNull(utilTarget);
  }

  @Test
  public void testEqualsOtherBuildTarget() {
    BuildTarget utilTarget1 =
        BuildTargetFactory.newInstance("//src/com/facebook/buck/util", "util");
    assertEquals(utilTarget1, utilTarget1);

    BuildTarget utilTarget2 =
        BuildTargetFactory.newInstance("//src/com/facebook/buck/util", "util");
    assertEquals(utilTarget1, utilTarget2);
  }

  @Test
  public void testNotEquals() {
    BuildTarget utilTarget = BuildTargetFactory.newInstance("//src/com/facebook/buck/util", "util");
    BuildTarget ioTarget = BuildTargetFactory.newInstance("//src/com/facebook/buck/util", "io");
    assertNotEquals(utilTarget, ioTarget);
  }

  @Test
  public void testBuildTargetWithFlavor() {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo/bar", "baz", InternalFlavor.of("d8"));
    assertEquals("baz#d8", target.getShortNameAndFlavorPostfix());
    assertEquals(FlavorSet.of(InternalFlavor.of("d8")), target.getFlavors());
    assertTrue(target.isFlavored());
  }

  @Test
  public void testBuildTargetWithoutFlavor() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar", "baz");
    assertEquals(target.getShortNameAndFlavorPostfix(), "baz");
    assertEquals(FlavorSet.NO_FLAVORS, target.getFlavors());
    assertFalse(target.isFlavored());
  }

  @Test
  public void testFlavorIsValid() {
    try {
      BuildTargetFactory.newInstance("//foo/bar", "baz", InternalFlavor.of("d!x"));
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Invalid characters in flavor name: d!x", e.getMessage());
    }
  }

  @Test
  public void testShortNameCannotContainHashWhenFlavorSet() {
    try {
      BuildTargetFactory.newInstance("//foo/bar", "baz#d8", InternalFlavor.of("src-jar"));
      fail("Should have thrown IllegalArgumentException.");
    } catch (IllegalArgumentException e) {
      assertEquals("Build target name cannot contain '#' but was: baz#d8.", e.getMessage());
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testShortNamesMustNotContainTheFlavorSeparator() {
    @SuppressWarnings("unused")
    BuildTarget unused = BuildTargetFactory.newInstance("//foo/bar", "baz#d8");
  }

  @Test
  public void testFlavorDefaultsToNoneIfNotSet() {
    assertEquals(
        FlavorSet.NO_FLAVORS, BuildTargetFactory.newInstance("//foo/bar", "baz").getFlavors());
  }

  @Test
  public void testGetUnflavoredTarget() {
    UnflavoredBuildTarget unflavoredTarget =
        UnflavoredBuildTarget.of(CanonicalCellName.rootCell(), BaseName.of("//foo/bar"), "baz");

    BuildTarget flavoredTarget =
        BuildTargetFactory.newInstance("//foo/bar", "baz", InternalFlavor.of("biz"));
    assertEquals(unflavoredTarget, flavoredTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testNumbersAreValidFlavors() {
    @SuppressWarnings("unused")
    BuildTarget unused = BuildTargetFactory.newInstance("//foo", "bar", InternalFlavor.of("1234"));
  }

  @Test
  public void testAppendingFlavors() {
    Flavor aaa = InternalFlavor.of("aaa");
    Flavor biz = InternalFlavor.of("biz");

    BuildTarget flavoredTarget = BuildTargetFactory.newInstance("//foo/bar", "baz", biz);
    BuildTarget appendedFlavor = flavoredTarget.withAppendedFlavors(aaa);
    assertThat(appendedFlavor, Matchers.not(Matchers.equalTo(flavoredTarget)));
    FlavorSet expectedFlavors = FlavorSet.of(biz, aaa);
    assertThat(appendedFlavor.getFlavors(), Matchers.equalTo(expectedFlavors));
  }

  @Test
  public void unflavoredBuildTargetsAreInterned() {
    UnflavoredBuildTarget target1 =
        UnflavoredBuildTarget.of(CanonicalCellName.rootCell(), BaseName.of("//foo"), "bar");

    UnflavoredBuildTarget target2 =
        UnflavoredBuildTarget.of(CanonicalCellName.rootCell(), BaseName.of("//foo"), "bar");

    assertSame(target1, target2);
  }
}
