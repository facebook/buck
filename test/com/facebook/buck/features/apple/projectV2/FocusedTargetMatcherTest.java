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

package com.facebook.buck.features.apple.projectV2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.model.BuildTargetFactory;
import org.junit.Test;

public class FocusedTargetMatcherTest {

  @Test
  public void testNoFocus() {
    FocusedTargetMatcher focusTargetMatcher = FocusedTargetMatcher.noFocus();

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:bar")));
  }

  @Test
  public void testFocusSingleTarget() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher("//foo:bar", TestCellNameResolver.forRoot());

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:bar")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:baz")));
  }

  @Test
  public void testFocusPackageTarget() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher("//foo:", TestCellNameResolver.forRoot());

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:bar")));
    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:baz")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//bar:foo")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/bar:baz")));
  }

  @Test
  public void testFocusRecursiveTarget() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher("//foo/...", TestCellNameResolver.forRoot());

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:bar")));
    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:baz")));
    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/bar:baz")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//bar:foo")));
  }

  @Test
  public void testFocusRegex() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher("oo", TestCellNameResolver.forRoot("oo"));

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:bar")));
    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:baz")));
    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/bar:baz")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//bar:fubar")));
  }

  @Test
  public void testCell() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher("foo//bar/...", TestCellNameResolver.forRoot("foo"));

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("foo//bar:baz")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("baz//bar:foo")));
  }

  @Test
  public void testCombination() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher(
            "//foo:bar //foo/alpha: //foo/baz/... beta", TestCellNameResolver.forRoot());

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:bar")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:barbaz")));

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/alpha:bar")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/alpha/bar:baz")));

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/baz:bar")));
    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/baz/beta:bar")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/bazbar:alpha")));

    assertTrue(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo/barbeta:baz")));
    assertFalse(focusTargetMatcher.matches(BuildTargetFactory.newInstance("//foo:fubar")));
  }
}
