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
import com.google.common.testing.EqualsTester;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

public class BuckTargetPatternTest {

  // Helpers here...

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static <T> void assertOptionalEquals(@Nullable T expected, Optional<T> optionalActual) {
    assertEquals(expected, optionalActual.orElse(null));
  }

  private static BuckTargetPattern assertCanParse(String src, String canonicalForm) {
    Optional<BuckTargetPattern> optionalPattern = BuckTargetPattern.parse(src);
    assertTrue("Should be able to parse as target: " + src, optionalPattern.isPresent());
    BuckTargetPattern pattern = optionalPattern.get();
    assertEquals(canonicalForm, pattern.toString());
    return pattern;
  }

  private static BuckTargetPattern assertCanParse(String src) {
    String canonical = src.startsWith("@") ? src.substring(1) : src;
    return assertCanParse(src, canonical);
  }

  private void checkAsBuckTarget(BuckTargetPattern pattern) {
    String src = pattern.toString();
    assertEquals(BuckTarget.parse(src), pattern.asBuckTarget());
  }

  // Actual tests start here

  // Tests for patterns that begin with a cellname

  @Test
  public void parseFullyQualifiedPatternThatIsABuckTarget() {
    BuckTargetPattern pattern = assertCanParse("cell//path/to/module:rule");
    assertOptionalEquals("cell", pattern.getCellName());
    assertOptionalEquals("path/to/module", pattern.getCellPath());
    assertOptionalEquals(":rule", pattern.getSuffix());
    assertOptionalEquals("rule", pattern.getRuleName());
    checkAsBuckTarget(pattern);

    assertTrue(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseFullyQualifiedPatternWithImpliedRuleName() {
    BuckTargetPattern pattern = assertCanParse("cell//path/to/module");
    assertOptionalEquals("cell", pattern.getCellName());
    assertOptionalEquals("path/to/module", pattern.getCellPath());
    assertOptionalEquals(null, pattern.getSuffix());
    assertOptionalEquals("module", pattern.getRuleName());
    assertEquals(BuckTarget.parse("cell//path/to/module:module"), pattern.asBuckTarget());

    assertTrue(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseFullyQualifiedPatternWithAllTargetsInPackageSuffix() {
    BuckTargetPattern pattern = assertCanParse("cell//path/to/module:");
    assertOptionalEquals("cell", pattern.getCellName());
    assertOptionalEquals("path/to/module", pattern.getCellPath());
    assertOptionalEquals(":", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertTrue(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertTrue(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseFullyQualifiedPatternWithEmptyPathAndAllTargetsInPackageSuffix() {
    BuckTargetPattern pattern = assertCanParse("cell//:");
    assertOptionalEquals("cell", pattern.getCellName());
    assertOptionalEquals("", pattern.getCellPath());
    assertOptionalEquals(":", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertTrue(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertTrue(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseFullyQualifiedPatternWithRecursiveTargetsSuffix() {
    BuckTargetPattern pattern = assertCanParse("cell//path/to/module/...");
    assertOptionalEquals("cell", pattern.getCellName());
    assertOptionalEquals("path/to/module", pattern.getCellPath());
    assertOptionalEquals("/...", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertTrue(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertTrue(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseFullyQualifiedPatternWithEmptyPathAndRecursiveTargetsSuffix() {
    BuckTargetPattern pattern = assertCanParse("cell//...");
    assertOptionalEquals("cell", pattern.getCellName());
    assertOptionalEquals("", pattern.getCellPath());
    assertOptionalEquals("/...", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertTrue(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertTrue(pattern.isRecursivePackageMatching());
  }

  // Tests for patterns that begin with a path

  @Test
  public void parseNoCellThatIsABuckTarget() {
    BuckTargetPattern pattern = assertCanParse("//path/to/module:rule");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals("path/to/module", pattern.getCellPath());
    assertOptionalEquals(":rule", pattern.getSuffix());
    assertOptionalEquals("rule", pattern.getRuleName());
    checkAsBuckTarget(pattern);

    assertFalse(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseNoCellPatternWithImpliedRuleName() {
    BuckTargetPattern pattern = assertCanParse("//path/to/module");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals("path/to/module", pattern.getCellPath());
    assertOptionalEquals(null, pattern.getSuffix());
    assertOptionalEquals("module", pattern.getRuleName());
    assertEquals(BuckTarget.parse("//path/to/module:module"), pattern.asBuckTarget());

    assertFalse(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseNoCellPatternWithAllTargetsInPackageSuffix() {
    BuckTargetPattern pattern = assertCanParse("//path/to/module:");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals("path/to/module", pattern.getCellPath());
    assertOptionalEquals(":", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertFalse(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertTrue(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseNoCellEmptyPathPatternWithAllTargetsInPackageSuffix() {
    BuckTargetPattern pattern = assertCanParse("//:");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals("", pattern.getCellPath());
    assertOptionalEquals(":", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertFalse(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertTrue(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseNoCellEmptyPathNoTargetPattern() {
    BuckTargetPattern pattern = assertCanParse("//");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals("", pattern.getCellPath());
    assertOptionalEquals(null, pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertFalse(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseNoCellPatternWithRecursiveTargetsSuffix() {
    BuckTargetPattern pattern = assertCanParse("//path/to/module/...");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals("path/to/module", pattern.getCellPath());
    assertOptionalEquals("/...", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertFalse(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertTrue(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseNoCellEmptyPathPatternWithRecursiveTargetsSuffix() {
    BuckTargetPattern pattern = assertCanParse("//...");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals("", pattern.getCellPath());
    assertOptionalEquals("/...", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertFalse(pattern.isAbsolute());
    assertFalse(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertTrue(pattern.isRecursivePackageMatching());
  }

  // Tests for patterns that begin with a target

  @Test
  public void parseNoCellNoPathPatternWithTarget() {
    BuckTargetPattern pattern = assertCanParse(":foo");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals(null, pattern.getCellPath());
    assertOptionalEquals(":foo", pattern.getSuffix());
    assertOptionalEquals("foo", pattern.getRuleName());
    checkAsBuckTarget(pattern);

    assertFalse(pattern.isAbsolute());
    assertTrue(pattern.isPackageRelative());
    assertFalse(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  @Test
  public void parseNoCellNoPathPatternWithAllTargetsInPackageSuffix() {
    BuckTargetPattern pattern = assertCanParse(":");
    assertOptionalEquals(null, pattern.getCellName());
    assertOptionalEquals(null, pattern.getCellPath());
    assertOptionalEquals(":", pattern.getSuffix());
    assertOptionalEquals(null, pattern.getRuleName());
    assertOptionalEquals(null, pattern.asBuckTarget());

    assertFalse(pattern.isAbsolute());
    assertTrue(pattern.isPackageRelative());
    assertTrue(pattern.isPackageMatching());
    assertFalse(pattern.isRecursivePackageMatching());
  }

  // Tests for non-patterns that should not be recognized

  @Test
  public void parseSingleSlashPathsShouldNotBePatterns() {
    assertOptionalEquals(null, BuckTargetPattern.parse("cell/foo/..."));
    assertOptionalEquals(null, BuckTargetPattern.parse("cell/foo:bar"));
    assertOptionalEquals(null, BuckTargetPattern.parse("cell/foo:"));
    assertOptionalEquals(null, BuckTargetPattern.parse("cell/..."));
    assertOptionalEquals(null, BuckTargetPattern.parse("cell/:foo"));
    assertOptionalEquals(null, BuckTargetPattern.parse("cell/:"));

    assertOptionalEquals(null, BuckTargetPattern.parse("/foo/..."));
    assertOptionalEquals(null, BuckTargetPattern.parse("/foo:bar"));
    assertOptionalEquals(null, BuckTargetPattern.parse("/foo:"));
    assertOptionalEquals(null, BuckTargetPattern.parse("/..."));
    assertOptionalEquals(null, BuckTargetPattern.parse("/:foo"));
    assertOptionalEquals(null, BuckTargetPattern.parse("/:"));
  }

  private void checkPathSegments(String source, String... expectedPathSegments) {
    BuckTargetPattern pattern = assertCanParse(source);
    List<String> expected = Arrays.asList(expectedPathSegments);
    List<String> actual = pattern.getCellPathSegments().map(Lists::newArrayList).orElse(null);
    assertEquals("Path segments for pattern: " + source, expected, actual);
  }

  @Test
  public void testCellPathSegmentsWhenSeveralLevelsDeep() {
    checkPathSegments("cell//path/to/package:target", "path", "to", "package");
    checkPathSegments("cell//path/to/package", "path", "to", "package");
    checkPathSegments("cell//path/to/package:", "path", "to", "package");
    checkPathSegments("cell//path/to/package/...", "path", "to", "package");
    checkPathSegments("//path/to/package:target", "path", "to", "package");
    checkPathSegments("//path/to/package", "path", "to", "package");
    checkPathSegments("//path/to/package:", "path", "to", "package");
    checkPathSegments("//path/to/package/...", "path", "to", "package");
  }

  @Test
  public void testCellPathSegmentsWhenOneLevelDeep() {
    checkPathSegments("cell//package:target", "package");
    checkPathSegments("//package:target", "package");
    checkPathSegments("//package", "package");
    checkPathSegments("//package:", "package");
    checkPathSegments("//package/...", "package");
  }

  @Test
  public void testCellPathSegmentsWhenAtTopLevel() {
    checkPathSegments("cell//:target");
    checkPathSegments("//:target");
    checkPathSegments("//:");
    checkPathSegments("//...");
  }

  @Test
  public void testCellPathSegmentsWhenNoPath() {
    assertOptionalEquals(null, assertCanParse(":target").getCellPathSegments());
    assertOptionalEquals(null, assertCanParse(":").getCellPathSegments());
  }

  // Tests for #resolve(Pattern)

  @Test
  public void resolvePatternWhenOtherIsFullyQualified() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("foo//bar/baz:qux");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("foo//bar/baz:qux", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherIsFullyQualifiedWithImpliedTarget() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("foo//bar/baz");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("foo//bar/baz", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherIsFullyQualifiedWithAllTargetsInPackageSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("foo//bar/baz:");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("foo//bar/baz:", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherIsFullyQualifiedWithRecursiveTargetsSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("foo//bar/baz/...");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("foo//bar/baz/...", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherHasNoCell() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("//bar/baz:qux");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("cell//bar/baz:qux", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherHasNoCellWithImpliedTarget() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("//bar/baz");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("cell//bar/baz", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherHasNoCellWithAllTargetsInPackageSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("//bar/baz:");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("cell//bar/baz:", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherHasNoCellWithRecursiveTargetsSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("//bar/baz/...");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("cell//bar/baz/...", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherHasNoCellAndNoPath() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse(":qux");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("cell//path/to/module:qux", resolved.toString());
  }

  @Test
  public void resolvePatternWhenOtherHasNoCellAndNoPathWithAllTargetsInPackageSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse(":");
    BuckTargetPattern resolved = base.resolve(other);
    assertEquals("cell//path/to/module:", resolved.toString());
  }

  @Test
  public void resolvePatternReturnsNullWhenOtherIsNotATarget() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    assertOptionalEquals(null, base.resolve("not-a-target"));
  }

  // Tests for #resolve(Target)

  @Test
  public void resolveTargetWhenTargetIsFullyQualified() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = BuckTarget.parse("foo//bar:baz").get();
    assertEquals(other, base.resolve(other));
  }

  @Test
  public void resolveTargetWhenTargetHasNoCellNme() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = BuckTarget.parse("//bar:baz").get();
    BuckTarget expected = BuckTarget.parse("cell//bar:baz").get();
    assertEquals(expected, base.resolve(other));
  }

  @Test
  public void resolveTargetWhenTargetHasNoPath() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTarget other = BuckTarget.parse(":baz").get();
    BuckTarget expected = BuckTarget.parse("cell//path/to/module:baz").get();
    assertEquals(expected, base.resolve(other));
  }

  // Tests for #relativize(Pattern)

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasDifferentCell() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("foo//bar/baz:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("foo//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasSameCellAndDifferentPath() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//bar/baz:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasSameCellAndDifferentPathWithImpliedTargets() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//bar/baz");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("//bar/baz", resolved.toString());
  }

  @Test
  public void
      relativizeWhenFullyQualifiedAndOtherHasSameCellAndDifferentPathWithAllTargetsInPackageSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//bar/baz:");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("//bar/baz:", resolved.toString());
  }

  @Test
  public void
      relativizeWhenFullyQualifiedAndOtherHasSameCellAndDifferentPathWithRecursiveTargetsSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//bar/baz/...");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("//bar/baz/...", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasSameCellAndSamePath() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//path/to/module:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasSameCellAndSamePathWithImpliedTarget() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//path/to/module");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals(":module", resolved.toString());
  }

  @Test
  public void
      relativizeWhenFullyQualifiedAndOtherHasSameCellAndSamePathWithAllTargetsInPackageSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//path/to/module:");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals(":", resolved.toString());
  }

  @Test
  public void
      relativizeWhenFullyQualifiedAndOtherHasSameCellAndSamePathWithRecursiveTargetsSuffix() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//path/to/module/...");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("//path/to/module/...", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasNoCellAndDifferentPath() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("//bar/baz:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasNoCellAndSamePath() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("//path/to/module:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  @Test
  public void relativizeWhenFullyQualifiedAndOtherHasNoCellAndNoPath() {
    BuckTargetPattern base = assertCanParse("cell//path/to/module:rule");
    BuckTargetPattern other = assertCanParse(":qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasCellAndDifferentPath() {
    BuckTargetPattern base = assertCanParse("//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("foo//bar/baz:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("foo//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasCellAndSamePath() {
    BuckTargetPattern base = assertCanParse("//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("cell//path/to/module:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("cell//path/to/module:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasNoCellAndDifferentPath() {
    BuckTargetPattern base = assertCanParse("//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("//bar/baz:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals("//bar/baz:qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasNoCellAndSamePath() {
    BuckTargetPattern base = assertCanParse("//path/to/module:rule");
    BuckTargetPattern other = assertCanParse("//path/to/module:qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  @Test
  public void relativizeWhenNoCellAndOtherHasNoCellAndNoPath() {
    BuckTargetPattern base = assertCanParse("//path/to/module:rule");
    BuckTargetPattern other = assertCanParse(":qux");
    BuckTargetPattern resolved = base.relativize(other);
    assertEquals(":qux", resolved.toString());
  }

  // Tests for #matches(Pattern) and #matches(Target)

  private void checkMatches(String patternString, String otherString, boolean expected) {
    BuckTargetPattern pattern = assertCanParse(patternString);
    BuckTargetPattern other = assertCanParse(otherString);
    assertEquals(expected, pattern.matches(other));
    other.asBuckTarget().ifPresent(t -> assertEquals(expected, pattern.matches(t)));
  }

  @Test
  public void matchesRequiresPatternsToBeInTheSameCell() {
    // None of the below match because they aren't from the cell named "cell"
    checkMatches("cell//...", "//foo/bar:baz", false);
    checkMatches("cell//...", "//:baz", false);
    checkMatches("cell//...", "//:", false);
    checkMatches("cell//...", "//...", false);
    checkMatches("cell//...", "other//any:target", false);
    checkMatches("cell//...", "other//any:", false);
    checkMatches("cell//...", "other//:", false);
    checkMatches("cell//...", "other//...", false);
  }

  @Test
  public void matchesForRecursiveTargetsSuffix() {
    checkMatches("cell//foo/...", "cell//foo/bar:baz", true);
    checkMatches("cell//foo/...", "cell//foo:baz", true);
    checkMatches("cell//foo/...", "cell//foo:", true);
    checkMatches("cell//foo/...", "cell//foo", true);
    checkMatches("cell//foo/...", "cell//foo/...", true);
    checkMatches("cell//foo/...", "cell//:foo", false);
    checkMatches("cell//foo/...", "cell//:", false);

    checkMatches("cell//foo/...", "other//any", false);
  }

  @Test
  public void matchesForRecursiveTargetsSuffixAtTopLevel() {
    checkMatches("cell//...", "cell//foo:", true);
    checkMatches("cell//...", "cell//foo", true);
    checkMatches("cell//...", "cell//:foo", true);
    checkMatches("cell//...", "cell//:", true);
    checkMatches("cell//...", "cell//...", true);
    checkMatches("cell//...", "cell//", true);
  }

  @Test
  public void matchesForAllInTargetSuffix() {
    checkMatches("cell//foo:", "cell//foo:bar", true);
    checkMatches("cell//foo:", "cell//foo:", true);
    checkMatches("cell//foo:", "cell//foo", true);
    checkMatches("cell//foo:", "cell//foo/...", false);
    checkMatches("cell//foo:", "cell//foo/bar:baz", false);
  }

  @Test
  public void equalsAndHashCode() {
    EqualsTester equalsTester = new EqualsTester();
    for (String s :
        new String[] {
          "foo//bar",
          "foo//bar:",
          "foo//bar:baz",
          "foo//bar/...",
          "//bar",
          "//bar:",
          "//bar:baz",
          "//bar/...",
          ":baz",
          "//:",
          ":",
        }) {
      BuckTargetPattern one = assertCanParse(s);
      BuckTargetPattern two = assertCanParse(s);
      equalsTester.addEqualityGroup(one, two);
    }
    equalsTester.testEquals();
  }

  @Test
  public void forCellName() {
    assertEquals(assertCanParse("//..."), BuckTargetPattern.forCellName(null));
    assertEquals(assertCanParse("foo//..."), BuckTargetPattern.forCellName("foo"));
  }

  @Test
  public void asPackageMatchingPatternForFullyQualified() {
    BuckTargetPattern base = assertCanParse("cell//foo:bar");
    assertEquals(assertCanParse("cell//foo:"), base.asPackageMatchingPattern());
  }

  @Test
  public void asPackageMatchingPatternInDefaultCell() {
    BuckTargetPattern base = assertCanParse("//foo:bar");
    assertEquals(assertCanParse("//foo:"), base.asPackageMatchingPattern());
  }

  @Test
  public void asRecursivePackageMatchingPatternForFullyQualified() {
    BuckTargetPattern base = assertCanParse("cell//foo:bar");
    assertEquals(assertCanParse("cell//foo/..."), base.asRecursivePackageMatchingPattern());
  }

  @Test
  public void asRecursivePackageMatchingPatternInDefaultCell() {
    BuckTargetPattern base = assertCanParse("//foo:bar");
    assertEquals(assertCanParse("//foo/..."), base.asRecursivePackageMatchingPattern());
  }
}
