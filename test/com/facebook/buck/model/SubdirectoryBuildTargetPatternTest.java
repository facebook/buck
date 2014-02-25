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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SubdirectoryBuildTargetPatternTest {

  @Test
  public void testApply() {
    SubdirectoryBuildTargetPattern pattern =
        new SubdirectoryBuildTargetPattern("src/com/facebook/buck/");

    assertFalse(pattern.apply(null));
    assertTrue(pattern.apply(new BuildTarget("//src/com/facebook/buck", "buck")));
    assertTrue(pattern.apply(new BuildTarget("//src/com/facebook/buck/bar", "bar")));
    assertFalse(pattern.apply(new BuildTarget("//src/com/facebook/foo", "foo")));
  }

  @Test
  public void testEquals() {
    SubdirectoryBuildTargetPattern subDirPattern1 = new SubdirectoryBuildTargetPattern("src/ex/");
    SubdirectoryBuildTargetPattern subDirPattern2 = new SubdirectoryBuildTargetPattern("src/ex/");
    SubdirectoryBuildTargetPattern subDirPattern3 = new SubdirectoryBuildTargetPattern("src/ex2/");

    assertFalse(subDirPattern1.equals(null));
    assertEquals(subDirPattern1, subDirPattern2);
    assertFalse(subDirPattern2.equals(subDirPattern3));
  }

  @Test
  public void testHashCode() {
    SubdirectoryBuildTargetPattern subDirPattern1 = new SubdirectoryBuildTargetPattern("src/ex/");
    SubdirectoryBuildTargetPattern subDirPattern2 = new SubdirectoryBuildTargetPattern("src/ex/");
    SubdirectoryBuildTargetPattern subDirPattern3 = new SubdirectoryBuildTargetPattern("src/ex2/");

    assertEquals(subDirPattern1.hashCode(), subDirPattern2.hashCode());
    assertNotSame(subDirPattern1.hashCode(), subDirPattern3.hashCode());
  }
}
