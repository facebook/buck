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

package com.facebook.buck.intellij.ideabuck.config;

import com.google.common.testing.EqualsTester;
import org.junit.Assert;
import org.junit.Test;

public class BuckCellTest {

  @Test
  public void defaultConstructor() {
    BuckCell buckCell = new BuckCell();
    Assert.assertEquals("", buckCell.getName());
    Assert.assertEquals("$PROJECT_DIR$", buckCell.getRoot());
    Assert.assertEquals("BUCK", buckCell.getBuildFileName());
  }

  @Test
  public void gettersAndSetters() {
    BuckCell buckCell = new BuckCell();
    String name = "Banzai";
    String root = "/var/dimension/8";
    String buildFileName = "Buckaroo";
    buckCell.setName(name);
    buckCell.setRoot(root);
    buckCell.setBuildFileName(buildFileName);
    Assert.assertEquals(name, buckCell.getName());
    Assert.assertEquals(root, buckCell.getRoot());
    Assert.assertEquals(buildFileName, buckCell.getBuildFileName());
  }

  @Test
  public void copy() {
    BuckCell original = new BuckCell();
    original.setName("foo");
    BuckCell copy = original.copy();
    Assert.assertEquals("Should inherit values", "foo", copy.getName());

    copy.setName("bar");
    Assert.assertEquals("Should modify copy", "bar", copy.getName());
    Assert.assertEquals("Should not modify original", "foo", original.getName());
  }

  @Test
  public void builderPattern() {
    BuckCell buckCell =
        new BuckCell().withName("foo").withRoot("/tmp/foo").withBuildFileName("FOO");
    BuckCell sameName = buckCell.withName("foo");
    BuckCell differentName = buckCell.withName("bar");
    BuckCell differentRoot = buckCell.withName("/path/to/bar");
    BuckCell differentBuildFileName = buckCell.withName("BAR");
    Assert.assertEquals(buckCell, sameName);
    Assert.assertNotEquals(buckCell, differentName);
    Assert.assertNotEquals(buckCell, differentRoot);
    Assert.assertNotEquals(buckCell, differentBuildFileName);
  }

  @Test
  public void equalityAndHashCode() {
    BuckCell defaultCell = new BuckCell();
    BuckCell namedCell = new BuckCell();
    namedCell.setName("two");
    BuckCell rootedCell = new BuckCell();
    rootedCell.setRoot("/path/to/root");
    BuckCell buildfileCell = new BuckCell();
    buildfileCell.setBuildFileName("BUILD");

    new EqualsTester()
        .addEqualityGroup(defaultCell, defaultCell.copy(), new BuckCell())
        .addEqualityGroup(namedCell, namedCell.copy(), new BuckCell().withName(namedCell.getName()))
        .addEqualityGroup(
            rootedCell, rootedCell.copy(), new BuckCell().withRoot(rootedCell.getRoot()))
        .addEqualityGroup(
            buildfileCell,
            buildfileCell.copy(),
            new BuckCell().withBuildFileName(buildfileCell.getBuildFileName()))
        .testEquals();
  }
}
