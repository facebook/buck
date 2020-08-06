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

import static org.junit.Assert.*;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import org.junit.Ignore;
import org.junit.Test;

public class UnconfiguredBuildTargetTest {
  @Test
  public void intern() {
    assertSame(
        UnconfiguredBuildTarget.of(
            CanonicalCellName.rootCell(), BaseName.of("//foo"), "bar", FlavorSet.NO_FLAVORS),
        UnconfiguredBuildTarget.of(
            CanonicalCellName.rootCell(), BaseName.of("//foo"), "bar", FlavorSet.NO_FLAVORS));
  }

  @Test
  @Ignore
  public void collected() {
    // Run this test for a while, if it does not crash with OOM, it means GC collects objects
    for (long i = 0; ; ++i) {
      UnconfiguredBuildTarget.of(
          CanonicalCellName.rootCell(), BaseName.of("//foo"), "bar" + i, FlavorSet.NO_FLAVORS);
      if (i % 1000_000 == 0) {
        System.out.println(i);
      }
    }
  }
}
