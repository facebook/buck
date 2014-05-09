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

package com.facebook.buck.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BuildTargetsTest {

  @Test
  public void testCreateFlavoredBuildTarget() {
    BuildTarget fooBar = new BuildTarget("//foo", "bar");
    BuildTarget fooBarBaz = BuildTargets.createFlavoredBuildTarget(fooBar, "baz");
    assertTrue(fooBarBaz.isFlavored());
    assertEquals("//foo:bar#baz", fooBarBaz.getFullyQualifiedName());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCreateFlavoredBuildTargetRejectsFlavoredBuildTarget() {
    BuildTarget fooBarBaz = new BuildTarget("//foo", "bar", "baz");
    BuildTargets.createFlavoredBuildTarget(fooBarBaz, "buzz");
  }
}
