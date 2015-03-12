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

package com.facebook.buck.parser;

import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Paths;

public class BuildTargetParserTest {

  private BuildTargetPatternParser fullyQualifiedParser;

  @Before
  public void setUpFullyQualifiedBuildTargetPatternParser() {
    BuildTargetParser targetParser = new BuildTargetParser();
    fullyQualifiedParser = BuildTargetPatternParser.fullyQualified(targetParser);
  }

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Test
  public void testParseRootRule() {
    // Parse "//:fb4a" with the BuildTargetParser and test all of its observers.
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget buildTarget = parser.parse("//:fb4a", fullyQualifiedParser);
    assertEquals("fb4a", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals(Paths.get(""), buildTarget.getBasePath());
    assertEquals("", buildTarget.getBasePathWithSlash());
    assertEquals("//:fb4a", buildTarget.getFullyQualifiedName());
  }

  @Test
  public void testParseRuleWithFlavors() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget buildTarget = parser.parse("//:lib#foo,bar", fullyQualifiedParser);
    // Note the sort order.
    assertEquals("lib#bar,foo", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals(Paths.get(""), buildTarget.getBasePath());
    assertEquals("", buildTarget.getBasePathWithSlash());
    // Note the sort order.
    assertEquals("//:lib#bar,foo", buildTarget.getFullyQualifiedName());
    assertThat(
        buildTarget.getFlavors(),
        hasItems((Flavor) ImmutableFlavor.of("foo"), ImmutableFlavor.of("bar")));
  }

  @Test
  public void testParseValidTargetWithDots() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget buildTarget = parser.parse("//..a/b../a...b:assets", fullyQualifiedParser);
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//..a/b../a...b", buildTarget.getBaseName());
    assertEquals(Paths.get("..a", "b..", "a...b"), buildTarget.getBasePath());
    assertEquals("..a/b../a...b/", buildTarget.getBasePathWithSlash());
    assertEquals("//..a/b../a...b:assets", buildTarget.getFullyQualifiedName());
  }

  @Test
  public void testParsePathWithDot() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("Build target path cannot be absolute or contain . or .. " +
            "(found //.:assets)");
    BuildTargetParser parser = new BuildTargetParser();
    parser.parse("//.:assets", fullyQualifiedParser);
  }

  @Test
  public void testParsePathWithDotDot() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("Build target path cannot be absolute or contain . or .. " +
            "(found //../facebookorca:assets)");
    BuildTargetParser parser = new BuildTargetParser();
    parser.parse("//../facebookorca:assets", fullyQualifiedParser);
  }

  @Test
  public void testParseAbsolutePath() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("Build target path cannot be absolute or contain . or .. " +
            "(found ///facebookorca:assets)");
    BuildTargetParser parser = new BuildTargetParser();
    parser.parse("///facebookorca:assets", fullyQualifiedParser);
  }

  @Test
  public void testParseTrailingColon() {
    try {
      BuildTargetParser parser = new BuildTargetParser();
      parser.parse("//facebook/orca:assets:", fullyQualifiedParser);
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook/orca:assets: cannot end with a colon", e.getMessage());
    }
  }

  @Test
  public void testParseNoColon() {
    try {
      BuildTargetParser parser = new BuildTargetParser();
      parser.parse("//facebook/orca/assets", fullyQualifiedParser);
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook/orca/assets must contain exactly one colon (found 0)",
          e.getMessage());
    }
  }

  @Test
  public void testParseMultipleColons() {
    try {
      BuildTargetParser parser = new BuildTargetParser();
      parser.parse("//facebook:orca:assets", fullyQualifiedParser);
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook:orca:assets must contain exactly one colon (found 2)",
          e.getMessage());
    }
  }

  @Test
  public void testParseFullyQualified() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget buildTarget = parser.parse("//facebook/orca:assets", fullyQualifiedParser);
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
  }

  @Test
  public void testParseBuildFile() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget buildTarget = parser.parse(
        ":assets",
        BuildTargetPatternParser.forBaseName(new BuildTargetParser(), "//facebook/orca"));
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
  }

  @Test
  public void testParseWithVisibilityContext() {
    // Invoke the BuildTargetParser using the VISIBILITY context.
    BuildTargetParser parser = new BuildTargetParser();
    BuildTargetPatternParser buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument(new BuildTargetParser());
    BuildTarget target = parser.parse("//java/com/example:", buildTargetPatternParser);
    assertEquals(
        "A build target that ends with a colon should be treated as a wildcard build target " +
        "when parsed in the context of a visibility argument.",
        "//java/com/example:",
        target.getFullyQualifiedName());
  }

  @Test
  public void testParseWithRepoName() {
    ImmutableMap<Optional<String>, Optional<String>> canonicalRepoNamesMap =
        ImmutableMap.of(Optional.of("localreponame"), Optional.of("canonicalname"));
    BuildTargetParser parser = new BuildTargetParser(canonicalRepoNamesMap);
    String targetStr = "@localreponame//foo/bar:baz";
    String canonicalStr = "@canonicalname//foo/bar:baz";
    BuildTarget buildTarget = parser.parse(targetStr, fullyQualifiedParser);
    assertEquals(canonicalStr, buildTarget.getFullyQualifiedName());
    assertEquals("canonicalname", buildTarget.getRepository().get());
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseFailsWithRepoNameAndRelativeTarget() throws NoSuchBuildTargetException {
    BuildTargetParser parser = new BuildTargetParser();

    String invalidTargetStr = "@myRepo:baz";
    parser.parse(invalidTargetStr, fullyQualifiedParser);
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseFailsWithEmptyRepoName() throws NoSuchBuildTargetException {
    BuildTargetParser parser = new BuildTargetParser();

    String zeroLengthRepoTargetStr = "@//foo/bar:baz";
    parser.parse(zeroLengthRepoTargetStr, fullyQualifiedParser);
  }

  @Test
  public void testParseWithBackslash() {
    BuildTargetParser parser = new BuildTargetParser();
    String backslashStr = "//com\\microsoft\\windows:something";
    BuildTarget buildTarget = parser.parse(backslashStr, fullyQualifiedParser);
    assertEquals("//com/microsoft/windows", buildTarget.getBaseName());
  }

}
