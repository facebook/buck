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

public class ImmediateDirectoryBuildTargetPatternTest {

  @Test
  public void testApply() {
    ImmediateDirectoryBuildTargetPattern pattern =
        new ImmediateDirectoryBuildTargetPattern("src/com/facebook/buck/");

    assertFalse(pattern.apply(null));
    assertTrue(pattern.apply(BuildTarget.builder("//src/com/facebook/buck", "buck").build()));
    assertFalse(pattern.apply(BuildTarget.builder("//src/com/facebook/foo/", "foo").build()));
    assertFalse(pattern.apply(BuildTarget.builder("//src/com/facebook/buck/bar", "bar").build()));
  }

  @Test
  public void testEquals() {
    ImmediateDirectoryBuildTargetPattern pattern =
        new ImmediateDirectoryBuildTargetPattern("src/com/facebook/buck/");
    ImmediateDirectoryBuildTargetPattern samePattern =
        new ImmediateDirectoryBuildTargetPattern("src/com/facebook/buck/");
    ImmediateDirectoryBuildTargetPattern cliPattern =
        new ImmediateDirectoryBuildTargetPattern("src/com/facebook/buck/cli/");

    assertEquals(pattern, samePattern);
    assertFalse(pattern.equals(null));
    assertFalse(pattern.equals(cliPattern));
  }

  @Test
  public void testHashCode() {
    ImmediateDirectoryBuildTargetPattern pattern =
        new ImmediateDirectoryBuildTargetPattern("src/com/facebook/buck/");
    ImmediateDirectoryBuildTargetPattern samePattern =
        new ImmediateDirectoryBuildTargetPattern("src/com/facebook/buck/");
    ImmediateDirectoryBuildTargetPattern cliPattern =
        new ImmediateDirectoryBuildTargetPattern("src/com/facebook/buck/cli/");

    assertEquals(pattern.hashCode(), samePattern.hashCode());
    assertNotSame(pattern.hashCode(), cliPattern.hashCode());
  }
}
