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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Paths;

public class BuildTargetParserTest {

  @Test
  public void testParseRootRule() {
    // Parse "//:fb4a" with the BuildTargetParser and test all of its observers.
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget buildTarget = parser.parse("//:fb4a", ParseContext.fullyQualified());
    assertEquals("fb4a", buildTarget.getShortName());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals(Paths.get(""), buildTarget.getBasePath());
    assertEquals("", buildTarget.getBasePathWithSlash());
    assertEquals("//:fb4a", buildTarget.getFullyQualifiedName());
  }

  @Test
  public void testParseInvalidSubstrings() {
    try {
      BuildTargetParser parser = new BuildTargetParser();
      parser.parse("//facebook..orca:assets", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook..orca:assets cannot contain ..", e.getMessage());
    }

    try {
      BuildTargetParser parser = new BuildTargetParser();
      parser.parse("//./facebookorca:assets", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//./facebookorca:assets cannot contain ./", e.getMessage());
    }
  }

  @Test
  public void testParseTrailingColon() {
    try {
      BuildTargetParser parser = new BuildTargetParser();
      parser.parse("//facebook/orca:assets:", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook/orca:assets: cannot end with a colon", e.getMessage());
    }
  }

  @Test
  public void testParseNoColon() {
    try {
      BuildTargetParser parser = new BuildTargetParser();
      parser.parse("//facebook/orca/assets", ParseContext.fullyQualified());
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
      parser.parse("//facebook:orca:assets", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook:orca:assets must contain exactly one colon (found 2)",
          e.getMessage());
    }
  }

  @Test
  public void testParseFullyQualified() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget buildTarget = parser.parse("//facebook/orca:assets", ParseContext.fullyQualified());
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortName());
  }

  @Test
  public void testParseBuildFile() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget buildTarget = parser.parse(":assets", ParseContext.forBaseName("//facebook/orca"));
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortName());
  }

  @Test
  public void testParseWithVisibilityContext() {
    // Invoke the BuildTargetParser using the VISIBILITY context.
    BuildTargetParser parser = new BuildTargetParser();
    ParseContext parseContext = ParseContext.forVisibilityArgument();
    BuildTarget target = parser.parse("//java/com/example:", parseContext);
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
    ParseContext context = ParseContext.fullyQualified();
    String targetStr = "@localreponame//foo/bar:baz";
    String canonicalStr = "@canonicalname//foo/bar:baz";
    BuildTarget buildTarget = parser.parse(targetStr, context);
    assertEquals(canonicalStr, buildTarget.getFullyQualifiedName());
    assertEquals("canonicalname", buildTarget.getRepository().get());
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseFailsWithRepoNameAndRelativeTarget() throws NoSuchBuildTargetException {
    BuildTargetParser parser = new BuildTargetParser();
    ParseContext context = ParseContext.fullyQualified();

    String invalidTargetStr = "@myRepo:baz";
    parser.parse(invalidTargetStr, context);
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseFailsWithEmptyRepoName() throws NoSuchBuildTargetException {
    BuildTargetParser parser = new BuildTargetParser();
    ParseContext context = ParseContext.fullyQualified();

    String zeroLengthRepoTargetStr = "@//foo/bar:baz";
    parser.parse(zeroLengthRepoTargetStr, context);
  }
}
