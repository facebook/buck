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

import static com.facebook.buck.util.BuckConstant.BUILD_RULES_FILE_NAME;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Paths;

public class BuildTargetParserTest {

  @Test
  public void testParseRootRule() throws NoSuchBuildTargetException {
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists(Paths.get(""))).andReturn(true);
    expect(mockProjectFilesystem.exists(Paths.get(BUILD_RULES_FILE_NAME))).andReturn(true);
    replay(mockProjectFilesystem);

    // Parse "//:fb4a" with the BuildTargetParser and test all of its observers.
    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    BuildTarget buildTarget = parser.parse("//:fb4a", ParseContext.fullyQualified());
    assertEquals("fb4a", buildTarget.getShortName());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals(Paths.get(""), buildTarget.getBasePath());
    assertEquals("", buildTarget.getBasePathWithSlash());
    assertEquals("//:fb4a", buildTarget.getFullyQualifiedName());

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseInvalidSubstrings() throws NoSuchBuildTargetException {
    try {
      ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
      BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
      parser.parse("//facebook..orca:assets", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook..orca:assets cannot contain ..", e.getMessage());
    }

    try {
      ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
      BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
      parser.parse("//./facebookorca:assets", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//./facebookorca:assets cannot contain ./", e.getMessage());
    }
  }

  @Test
  public void testParseTrailingColon() throws NoSuchBuildTargetException {
    try {
      ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
      BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
      parser.parse("//facebook/orca:assets:", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook/orca:assets: cannot end with a colon", e.getMessage());
    }
  }

  @Test
  public void testParseNoColon() throws NoSuchBuildTargetException {
    try {
      ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
      BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
      parser.parse("//facebook/orca/assets", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook/orca/assets must contain exactly one colon (found 0)",
          e.getMessage());
    }
  }

  @Test
  public void testParseMultipleColons() throws NoSuchBuildTargetException {
    try {
      ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
      BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
      parser.parse("//facebook:orca:assets", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook:orca:assets must contain exactly one colon (found 2)",
          e.getMessage());
    }
  }

  @Test
  public void testParseFullyQualified() throws NoSuchBuildTargetException {
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists(Paths.get("facebook/orca"))).andReturn(true);
    expect(mockProjectFilesystem.exists(Paths.get("facebook/orca/" + BUILD_RULES_FILE_NAME)))
        .andReturn(true);
    replay(mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    BuildTarget buildTarget = parser.parse("//facebook/orca:assets", ParseContext.fullyQualified());
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortName());

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseBuildFile() throws NoSuchBuildTargetException {
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists(Paths.get("facebook/orca"))).andReturn(true);
    expect(mockProjectFilesystem.exists(Paths.get("facebook/orca/" + BUILD_RULES_FILE_NAME)))
        .andReturn(true);
    replay(mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    BuildTarget buildTarget = parser.parse(":assets", ParseContext.forBaseName("//facebook/orca"));
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortName());

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseBuildFileMissingBuildDirectoryFullyQualified() {
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists(Paths.get("facebook/missing"))).andReturn(false);
    replay(mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    try {
      parser.parse("//facebook/missing:assets", ParseContext.fullyQualified());
      fail("parse() should throw an exception");
    } catch (NoSuchBuildTargetException e) {
      assertEquals("No directory facebook/missing when resolving target " +
          "//facebook/missing:assets in context FULLY_QUALIFIED", e.getMessage());
    }

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseBuildFileMissingBuildDirectory() {
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists(Paths.get("facebook/missing"))).andReturn(false);
    replay(mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    try {
      parser.parse("//facebook/missing:assets", ParseContext.forBaseName("//facebook/orca"));
      fail("parse() should throw an exception");
    } catch (NoSuchBuildTargetException e) {
      assertEquals("No directory facebook/missing when resolving target " +
          "//facebook/missing:assets in build file //facebook/orca/" +
          BUILD_RULES_FILE_NAME, e.getMessage());
    }

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseBuildFileMissingBuildFile() throws NoSuchBuildTargetException {
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists(Paths.get("facebook/missing"))).andReturn(true);
    expect(mockProjectFilesystem.exists(Paths.get("facebook/missing/" + BUILD_RULES_FILE_NAME)))
        .andReturn(false);
    replay(mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    try {
      parser.parse("//facebook/missing:assets", ParseContext.forBaseName("//facebook/orca"));
      fail("parse() should throw an exception");
    } catch (NoSuchBuildTargetException e) {
      assertEquals(
          "No " + BUILD_RULES_FILE_NAME +
              " file facebook/missing/" + BUILD_RULES_FILE_NAME + " when resolving target " +
              "//facebook/missing:assets in build file //facebook/orca/" +
              BUILD_RULES_FILE_NAME,
          e.getMessage());
    }

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseWithVisibilityContext() throws NoSuchBuildTargetException {
    // Mock out all of the calls to the filesystem.
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists(Paths.get("java/com/example"))).andReturn(true);
    expect(mockProjectFilesystem.exists(Paths.get("java/com/example/" + BUILD_RULES_FILE_NAME)))
        .andReturn(true);
    replay(mockProjectFilesystem);

    // Invoke the BuildTargetParser using the VISIBILITY context.
    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    ParseContext parseContext = ParseContext.forVisibilityArgument();
    BuildTarget target = parser.parse("//java/com/example:", parseContext);
    assertEquals(
        "A build target that ends with a colon should be treated as a wildcard build target " +
        "when parsed in the context of a visibility argument.",
        "//java/com/example:",
        target.getFullyQualifiedName());

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseWithRepoName() throws NoSuchBuildTargetException {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    ImmutableMap<Optional<String>, Optional<String>> canonicalRepoNamesMap =
        ImmutableMap.of(Optional.of("localreponame"), Optional.of("canonicalname"));
    BuildTargetParser parser = new BuildTargetParser(filesystem, canonicalRepoNamesMap);
    ParseContext context = ParseContext.fullyQualified();
    String targetStr = "@localreponame//foo/bar:baz";
    String canonicalStr = "@canonicalname//foo/bar:baz";
    BuildTarget buildTarget = parser.parse(targetStr, context);
    assertEquals(canonicalStr, buildTarget.getFullyQualifiedName());
    assertEquals("canonicalname", buildTarget.getRepository().get());
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseFailsWithRepoNameAndRelativeTarget() throws NoSuchBuildTargetException {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildTargetParser parser = new BuildTargetParser(filesystem);
    ParseContext context = ParseContext.fullyQualified();

    String invalidTargetStr = "@myRepo:baz";
    parser.parse(invalidTargetStr, context);
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseFailsWithEmptyRepoName() throws NoSuchBuildTargetException {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuildTargetParser parser = new BuildTargetParser(filesystem);
    ParseContext context = ParseContext.fullyQualified();

    String zeroLengthRepoTargetStr = "@//foo/bar:baz";
    parser.parse(zeroLengthRepoTargetStr, context);
  }
}
