/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.maven;

import static com.facebook.buck.testutil.MoreAsserts.assertIterablesEquals;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.Arrays;
import java.util.SortedSet;
import java.util.TreeSet;

public class BuckDepComparatorTest {

  private BuckDepComparator comparator = new BuckDepComparator();
  private SortedSet<String> sorted = new TreeSet<>(comparator);

  @Test
  public void orderingOfLocalTargetsWorksAsExpected() {
    sorted.addAll(Arrays.asList(":b", ":c", ":a"));

    ImmutableSet<String> expected = ImmutableSet.of(":a", ":b", ":c");
    assertIterablesEquals(expected, sorted);
  }

  @Test
  public void orderingOfAbsoluteTargetsWorksAsExpected() {
    sorted.addAll(Arrays.asList("//beta:b", "//gamma:c", "//alpha:a"));

    ImmutableSet<String> expected = ImmutableSet.of("//alpha:a", "//beta:b", "//gamma:c");
    assertIterablesEquals(expected, sorted);
  }

  @Test
  public void localTargetsAreListedBeforeAbsoluteTargets() {
    sorted.addAll(Arrays.asList("//alpha:a", ":b"));

    ImmutableSet<String> expected = ImmutableSet.of(":b", "//alpha:a");
    assertIterablesEquals(expected, sorted);
  }

  @Test
  public void targetsWithShorterPathsAreListedBeforeOnesWithLongerPaths() {
    sorted.addAll(Arrays.asList("//alpha:b", "//alpha/beta:c"));

    ImmutableSet<String> expected = ImmutableSet.of("//alpha:b", "//alpha/beta:c");
    assertIterablesEquals(expected, sorted);
  }
}
