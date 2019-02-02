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

package com.facebook.buck.core.parser.buildtargetparser;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildTargetParserTest {

  private final BuildTargetParser parser = BuildTargetParser.INSTANCE;

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void testParseRootRule() {
    // Parse "//:fb4a" with the BuildTargetParser and test all of its observers.
    UnconfiguredBuildTarget buildTarget = parser.parse(createCellRoots(null), "//:fb4a", "", false);
    assertEquals("fb4a", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals(Paths.get(""), buildTarget.getBasePath());
    assertEquals("//:fb4a", buildTarget.getFullyQualifiedName());
  }

  @Test
  public void testParseRuleWithFlavors() {
    UnconfiguredBuildTarget buildTarget =
        parser.parse(createCellRoots(null), "//:lib#foo,bar", "", false);
    // Note the sort order.
    assertEquals("lib#bar,foo", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals(Paths.get(""), buildTarget.getBasePath());
    // Note the sort order.
    assertEquals("//:lib#bar,foo", buildTarget.getFullyQualifiedName());
    assertThat(
        buildTarget.getFlavors(), hasItems(InternalFlavor.of("foo"), InternalFlavor.of("bar")));
  }

  @Test
  public void testParseValidTargetWithDots() {
    UnconfiguredBuildTarget buildTarget =
        parser.parse(createCellRoots(null), "//..a/b../a...b:assets", "", false);
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//..a/b../a...b", buildTarget.getBaseName());
    assertEquals(Paths.get("..a", "b..", "a...b"), buildTarget.getBasePath());
    assertEquals("//..a/b../a...b:assets", buildTarget.getFullyQualifiedName());
  }

  @Test
  public void testParsePathWithDot() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage(" . ");
    exception.expectMessage("(found //.:assets)");
    parser.parse(createCellRoots(null), "//.:assets", "", false);
  }

  @Test
  public void testParsePathWithDotDot() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage(" .. ");
    exception.expectMessage("(found //../facebookorca:assets)");
    parser.parse(createCellRoots(null), "//../facebookorca:assets", "", false);
  }

  @Test
  public void testParseAbsolutePath() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("absolute");
    exception.expectMessage("(found ///facebookorca:assets)");
    parser.parse(createCellRoots(null), "///facebookorca:assets", "", false);
  }

  @Test
  public void testParseDoubleSlashPath() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("//");
    exception.expectMessage("(found //facebook//orca:assets)");
    parser.parse(createCellRoots(null), "//facebook//orca:assets", "", false);
  }

  @Test
  public void testParseTrailingColon() {
    try {
      parser.parse(createCellRoots(null), "//facebook/orca:assets:", "", false);
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook/orca:assets: cannot end with a colon", e.getMessage());
    }
  }

  @Test
  public void testParseNoColon() {
    try {
      parser.parse(createCellRoots(null), "//facebook/orca/assets", "", false);
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals(
          "//facebook/orca/assets must contain exactly one colon (found 0)", e.getMessage());
    }
  }

  @Test
  public void testParseMultipleColons() {
    try {
      parser.parse(createCellRoots(null), "//facebook:orca:assets", "", false);
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals(
          "//facebook:orca:assets must contain exactly one colon (found 2)", e.getMessage());
    }
  }

  @Test
  public void testParseFullyQualified() {
    UnconfiguredBuildTarget buildTarget =
        parser.parse(createCellRoots(null), "//facebook/orca:assets", "", false);
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
  }

  @Test
  public void testParseBuildFile() {
    UnconfiguredBuildTarget buildTarget =
        parser.parse(createCellRoots(null), ":assets", "//facebook/orca", false);
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
  }

  @Test
  public void testParseWithVisibilityContext() {
    UnconfiguredBuildTarget target =
        parser.parse(createCellRoots(null), "//java/com/example:", "", true);
    assertEquals(
        "A build target that ends with a colon should be treated as a wildcard build target "
            + "when parsed in the context of a visibility argument.",
        "//java/com/example:",
        target.getFullyQualifiedName());
  }

  @Test
  public void testParseWithRepoName() {
    Path localRepoRoot = Paths.get("/opt/local/repo");
    CellPathResolver cellRoots =
        DefaultCellPathResolver.of(
            Paths.get("/opt/local/rootcell"), ImmutableMap.of("localreponame", localRepoRoot));
    String targetStr = "localreponame//foo/bar:baz";

    UnconfiguredBuildTarget buildTarget = parser.parse(cellRoots, targetStr, "", false);
    assertEquals("localreponame//foo/bar:baz", buildTarget.getFullyQualifiedName());
    assertTrue(buildTarget.getCell().isPresent());
    assertEquals(localRepoRoot, buildTarget.getCellPath());
  }

  @Test
  public void atPrefixOfCellsIsSupportedAndIgnored() {
    Path localRepoRoot = Paths.get("/opt/local/repo");
    CellPathResolver cellRoots =
        DefaultCellPathResolver.of(
            Paths.get("/opt/local/rootcell"), ImmutableMap.of("localreponame", localRepoRoot));
    String targetStr = "@localreponame//foo/bar:baz";

    UnconfiguredBuildTarget buildTarget = parser.parse(cellRoots, targetStr, "", false);
    assertEquals("localreponame//foo/bar:baz", buildTarget.getFullyQualifiedName());
    assertTrue(buildTarget.getCell().isPresent());
    assertEquals(localRepoRoot, buildTarget.getCellPath());
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseFailsWithRepoNameAndRelativeTarget() throws NoSuchBuildTargetException {

    String invalidTargetStr = "myRepo:baz";
    parser.parse(createCellRoots(null), invalidTargetStr, "", false);
  }

  @Test
  public void testParseWithBackslash() {
    String backslashStr = "//com\\microsoft\\windows:something";
    UnconfiguredBuildTarget buildTarget =
        parser.parse(createCellRoots(null), backslashStr, "", false);
    assertEquals("//com/microsoft/windows", buildTarget.getBaseName());
  }

  @Test
  public void testIncludesTargetNameInMissingCellErrorMessage() {
    Path localRepoRoot = Paths.get("/opt/local/repo");
    CellPathResolver cellRoots =
        DefaultCellPathResolver.of(
            Paths.get("/opt/local/rootcell"), ImmutableMap.of("localreponame", localRepoRoot));

    exception.expect(BuildTargetParseException.class);
    // It contains the target
    exception.expectMessage("lclreponame//facebook/orca:assets");
    // The invalid cell
    exception.expectMessage("Unknown cell: lclreponame");
    // And the suggestion
    exception.expectMessage("localreponame");
    parser.parse(cellRoots, "lclreponame//facebook/orca:assets", "", false);
  }
}
