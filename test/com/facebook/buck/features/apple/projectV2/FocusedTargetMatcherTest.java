/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.features.apple.projectV2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.model.CanonicalCellName;
import java.util.Optional;
import org.junit.Test;

public class FocusedTargetMatcherTest {

  @Test
  public void testNoFocus() {
    FocusedTargetMatcher focusTargetMatcher = FocusedTargetMatcher.noFocus();

    assertTrue(focusTargetMatcher.matches("//foo:bar"));
    assertTrue(focusTargetMatcher.matches("bar"));
  }

  @Test
  public void testFocusSingleTarget() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher(
            "//foo:bar", CanonicalCellName.rootCell(), TestCellNameResolver.forRoot());

    assertTrue(focusTargetMatcher.matches("//foo:bar"));
    assertFalse(focusTargetMatcher.matches(":bar"));
    assertFalse(focusTargetMatcher.matches("//foo:baz"));
  }

  @Test
  public void testFocusPackageTarget() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher(
            "//foo:", CanonicalCellName.rootCell(), TestCellNameResolver.forRoot());

    assertTrue(focusTargetMatcher.matches("//foo:bar"));
    assertTrue(focusTargetMatcher.matches("//foo:baz"));
    assertFalse(focusTargetMatcher.matches("//bar:foo"));
    assertFalse(focusTargetMatcher.matches("//foo/bar:baz"));
  }

  @Test
  public void testFocusRecursiveTarget() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher(
            "//foo/...", CanonicalCellName.rootCell(), TestCellNameResolver.forRoot());

    assertTrue(focusTargetMatcher.matches("//foo:bar"));
    assertTrue(focusTargetMatcher.matches("//foo:baz"));
    assertTrue(focusTargetMatcher.matches("//foo/bar:baz"));
    assertFalse(focusTargetMatcher.matches("//bar:foo"));
  }

  @Test
  public void testFocusRegex() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher(
            "oo",
            CanonicalCellName.unsafeOf(Optional.of("oo")),
            TestCellNameResolver.forRoot("oo"));

    assertTrue(focusTargetMatcher.matches("//foo:bar"));
    assertTrue(focusTargetMatcher.matches("//foo:baz"));
    assertTrue(focusTargetMatcher.matches("//foo/bar:baz"));
    assertFalse(focusTargetMatcher.matches("//bar:fubar"));
  }

  @Test
  public void testCell() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher(
            "foo//bar/...",
            CanonicalCellName.unsafeOf(Optional.of("foo")),
            TestCellNameResolver.forRoot("foo"));

    assertTrue(focusTargetMatcher.matches("foo//bar:baz"));
    assertTrue(focusTargetMatcher.matches("//bar:baz"));
    assertFalse(focusTargetMatcher.matches("baz//bar:foo"));
  }

  @Test
  public void testCombination() {
    FocusedTargetMatcher focusTargetMatcher =
        new FocusedTargetMatcher(
            "//foo:bar //foo/alpha: //foo/baz/... beta",
            CanonicalCellName.rootCell(),
            TestCellNameResolver.forRoot());

    assertTrue(focusTargetMatcher.matches("//foo:bar"));
    assertFalse(focusTargetMatcher.matches("//foo:barbaz"));

    assertTrue(focusTargetMatcher.matches("//foo/alpha:bar"));
    assertFalse(focusTargetMatcher.matches("//foo/alpha/bar:baz"));

    assertTrue(focusTargetMatcher.matches("//foo/baz:bar"));
    assertTrue(focusTargetMatcher.matches("//foo/baz/beta:bar"));
    assertFalse(focusTargetMatcher.matches("//foo/bazbar:alpha"));

    assertTrue(focusTargetMatcher.matches("//foo/barbeta:baz"));
    assertFalse(focusTargetMatcher.matches("//foo:fubar"));
  }
}
