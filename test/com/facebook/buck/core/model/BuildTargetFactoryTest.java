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

package com.facebook.buck.core.model;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTargetView;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class BuildTargetFactoryTest {

  private static final Path ROOT = Paths.get("/opt/might/exist");

  @Test
  public void testTargetWithoutFlavor() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(ROOT, "//example/base:one");
    assertEquals(BuildTargetFactory.newInstance(ROOT, "//example/base", "one"), buildTarget);
  }

  @Test
  public void testTargetWithFlavor() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(ROOT, "//example/base:one#two");
    assertEquals(
        BuildTargetFactory.newInstance(ROOT, "//example/base", "one", InternalFlavor.of("two")),
        buildTarget);
  }

  @Test
  public void testTargetWithMultipleFlavors() {
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(ROOT, "//example/base:shortName#one,two,three");
    assertEquals(
        BuildTargetFactory.newInstance(
            ROOT,
            "//example/base",
            "shortName",
            InternalFlavor.of("one"),
            InternalFlavor.of("two"),
            InternalFlavor.of("three")),
        buildTarget);
  }

  @Test
  public void testTargetWithCell() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(ROOT, "xplat//example/base:one");
    assertEquals(
        ImmutableUnconfiguredBuildTargetView.of(
                ImmutableUnflavoredBuildTargetView.of(
                    ROOT, Optional.of("xplat"), "//example/base", "one"))
            .configure(EmptyTargetConfiguration.INSTANCE),
        buildTarget);
  }
}
