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

public class SingletonBuildTargetPatternTest {

  @Test
  public void testApply() {
    SingletonBuildTargetPattern pattern =
        new SingletonBuildTargetPattern("//src/com/facebook/buck:buck");

    assertFalse(pattern.apply(null));
    assertTrue(pattern.apply(BuildTarget.builder("//src/com/facebook/buck", "buck").build()));
    assertFalse(pattern.apply(
            BuildTarget.builder("//src/com/facebook/buck", "otherTarget").build()));
    assertFalse(pattern.apply(BuildTarget.builder("//src/com/facebook/foo", "foo").build()));
    assertFalse(pattern.apply(BuildTarget.builder("//src/com/facebook/buck/bar", "bar").build()));
  }

  @Test
  public void testEquals() {
    SingletonBuildTargetPattern singletonPattern1 = new SingletonBuildTargetPattern(
        "//src/com/facebook/buck:buck");
    SingletonBuildTargetPattern singletonPattern2 = new SingletonBuildTargetPattern(
        "//src/com/facebook/buck:buck");
    SingletonBuildTargetPattern singletonPattern3 = new SingletonBuildTargetPattern(
        "//src/com/facebook/buck/cli:cli");

    assertFalse(singletonPattern1.equals(null));
    assertEquals(singletonPattern1, singletonPattern2);
    assertFalse(singletonPattern2.equals(singletonPattern3));
  }

  @Test
  public void testHashCode() {
    SingletonBuildTargetPattern singletonPattern1 = new SingletonBuildTargetPattern(
        "//src/com/facebook/buck:buck");
    SingletonBuildTargetPattern singletonPattern2 = new SingletonBuildTargetPattern(
        "//src/com/facebook/buck:buck");
    SingletonBuildTargetPattern singletonPattern3 = new SingletonBuildTargetPattern(
        "//src/com/facebook/buck/cli:cli");

    assertEquals(singletonPattern1.hashCode(), singletonPattern2.hashCode());
    assertNotSame(singletonPattern1.hashCode(), singletonPattern3.hashCode());
  }
}
