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

package com.facebook.buck.intellij.ideabuck.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Lists;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

public class BuckTargetTest {

  // Helpers here...

  @NotNull
  private static <T> T assertPresent(Optional<T> t) {
    assertTrue(t.isPresent());
    return t.get();
  }

  private static <T> void assertOptionalEquals(@Nullable T expected, Optional<T> optionalActual) {
    assertEquals(expected, optionalActual.orElse(null));
  }

  private static BuckTarget assertCanParse(String src, String canonicalForm) {
    Optional<BuckTarget> optionalTarget = BuckTarget.parse(src);
    assertTrue("Should be able to parse as target: " + src, optionalTarget.isPresent());
    BuckTarget target = optionalTarget.get();
    assertEquals(canonicalForm, target.toString());
    return target;
  }

  private static BuckTarget assertCanParse(String src) {
    String canonical = src.startsWith("@") ? src.substring(1) : src;
    return assertCanParse(src, canonical);
  }

  // Actual tests start here...

  @Test
  public void parseFullyQualifiedTarget() {
    BuckTarget target = assertCanParse("cell//path/to/module:rule");
    assertOptionalEquals("cell", target.getCellName());
    assertOptionalEquals("path/to/module", target.getCellPath());
    assertEquals("rule", target.getRuleName());
    assertTrue(target.isAbsolute());
    assertFalse(target.isPackageRelative());
  }

  @Test
  public void parseEmptyCell() {
    BuckTarget target = assertCanParse("//path/to/module:rule");
    assertOptionalEquals(null, target.getCellName());
    assertOptionalEquals("path/to/module", target.getCellPath());
    assertEquals("rule", target.getRuleName());
    assertFalse(target.isAbsolute());
    assertFalse(target.isPackageRelative());
  }

  @Test
  public void parseCellAndEmptyPath() {
    BuckTarget target = assertCanParse("cell//:rule");
    assertOptionalEquals("cell", target.getCellName());
    assertOptionalEquals("", target.getCellPath());
    assertEquals("rule", target.getRuleName());
    assertTrue(target.isAbsolute());
    assertFalse(target.isPackageRelative());
  }

  @Test
  public void parseNoCellAndEmptyPath() {
    BuckTarget target = assertCanParse("//:rule");
    assertOptionalEquals(null, target.getCellName());
    assertOptionalEquals("", target.getCellPath());
    assertEquals("rule", target.getRuleName());
    assertFalse(target.isAbsolute());
    assertFalse(target.isPackageRelative());
  }

  @Test
  public void parseOnlyRule() {
    BuckTarget target = assertCanParse(":rule");
    assertOptionalEquals(null, target.getCellName());
    assertOptionalEquals(null, target.getCellPath());
    assertEquals("rule", target.getRuleName());
    assertEquals(":rule", target.toString());
  }

  @Test
  public void parseTargetWithSlashInRuleName() {
    BuckTarget target = assertCanParse("cell//path/to/module:rule/name");
    assertOptionalEquals("cell", target.getCellName());
    assertOptionalEquals("path/to/module", target.getCellPath());
    assertEquals("rule/name", target.getRuleName());
  }

  private void checkPathSegments(String source, String... expectedPathSegments) {
    BuckTarget target = assertCanParse(source);
    List<String> expected = Arrays.asList(expectedPathSegments);
    List<String> actual = target.getCellPathSegments().map(Lists::newArrayList).orElse(null);
    assertEquals("Path segments for target: " + source, expected, actual);
  }

  @Test
  public void testCellPathSegmentsWhenSeveralLevelsDeep() {
    checkPathSegments("cell//path/to/package:target", "path", "to", "package");
    checkPathSegments("//path/to/package:target", "path", "to", "package");
  }

  @Test
  public void testCellPathSegmentsWhenOneLevelDeep() {
    checkPathSegments("cell//package:target", "package");
    checkPathSegments("//package:target", "package");
  }

  @Test
  public void testCellPathSegmentsWhenAtTopLevel() {
    checkPathSegments("cell//:target");
    checkPathSegments("//:target");
  }

  @Test
  public void testCellPathSegmentsWhenNoPath() {
    BuckTarget target = assertCanParse(":target");
    assertOptionalEquals(null, target.getCellPathSegments());
  }

  // Tests for #asPattern

  @Test
  public void asPatternForFullyQualifiedTarget() {
    BuckTarget original = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern pattern = original.asPattern();
    assertEquals(pattern.toString(), original.toString());
  }

  @Test
  public void asPatternForNoCellTarget() {
    BuckTarget original = assertCanParse("//path/to/module:rule");
    BuckTargetPattern pattern = original.asPattern();
    assertEquals(pattern.toString(), original.toString());
  }

  @Test
  public void asPatternForNoCellEmptyPathTarget() {
    BuckTarget original = assertCanParse("//:rule");
    BuckTargetPattern pattern = original.asPattern();
    assertEquals(pattern.toString(), original.toString());
  }

  @Test
  public void asPatternForRelativeTarget() {
    BuckTarget original = assertCanParse(":rule");
    BuckTargetPattern pattern = original.asPattern();
    assertEquals(pattern.toString(), original.toString());
  }

  // Tests for #resolve(Target)

  @Test
  public void resolveWhenOtherIsFullyQualified() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse("foo//bar/baz:qux");
    BuckTarget resolved = base.resolve(other);
    assertEquals("foo//bar/baz:qux", resolved.toString());
  }

  @Test
  public void resolveWhenOtherHasNoCell() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse("//bar/baz:qux");
    BuckTarget resolved = base.resolve(other);
    assertEquals("cell//bar/baz:qux", resolved.toString());
  }

  @Test
  public void resolveWhenOtherHasNoCellAndNoPath() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse(":qux");
    BuckTarget resolved = base.resolve(other);
    assertEquals("cell//path/to/module:qux", resolved.toString());
  }

  @Test
  public void resolveReturnsNullWhenOtherIsNotATarget() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    assertOptionalEquals(null, base.resolve("not-a-target"));
  }

  // Tests for #relativize(Target)

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasDifferentCell() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse("foo//bar/baz:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals("foo//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasSameCellAndDifferentPath() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse("cell//bar/baz:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals("//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasSameCellAndSamePath() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse("cell//path/to/module:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasNoCellAndDifferentPath() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse("//bar/baz:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals("//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasNoCellAndSamePath() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse("//path/to/module:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasNoCellAndNoPath() {
    BuckTarget base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = assertCanParse(":qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasCellAndDifferentPath() {
    BuckTarget base = assertCanParse("//path/to/module:rule");
    BuckTarget other = assertCanParse("foo//bar/baz:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals("foo//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasCellAndSamePath() {
    BuckTarget base = assertCanParse("//path/to/module:rule");
    BuckTarget other = assertCanParse("cell//path/to/module:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals("cell//path/to/module:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasNoCellAndDifferentPath() {
    BuckTarget base = assertCanParse("//path/to/module:rule");
    BuckTarget other = assertCanParse("//bar/baz:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals("//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasNoCellAndSamePath() {
    BuckTarget base = assertCanParse("//path/to/module:rule");
    BuckTarget other = assertCanParse("//path/to/module:qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasNoCellAndNoPath() {
    BuckTarget base = assertCanParse("//path/to/module:rule");
    BuckTarget other = assertCanParse(":qux");
    BuckTarget resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }
}
