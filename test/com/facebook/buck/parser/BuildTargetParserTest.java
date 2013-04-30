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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;

import org.junit.Test;

import java.io.File;

public class BuildTargetParserTest {

  @Test
  public void testParseRootRule() throws NoSuchBuildTargetException {
    File mockBuildFile = createMock(File.class);
    expect(mockBuildFile.isFile()).andReturn(true);

    File mockBuildFileDirectory = createMock(File.class);
    expect(mockBuildFile.getParentFile()).andReturn(mockBuildFileDirectory).anyTimes();
    expect(mockBuildFileDirectory.getAbsolutePath()).andReturn("/home/mbolin/fbandroid");

    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists("")).andReturn(true);
    expect(mockProjectFilesystem.exists(BuckConstant.BUILD_RULES_FILE_NAME)).andReturn(true);
    expect(mockProjectFilesystem.getFileForRelativePath(BuckConstant.BUILD_RULES_FILE_NAME))
        .andReturn(mockBuildFile);
    replay(mockBuildFile, mockBuildFileDirectory, mockProjectFilesystem);

    // Parse "//:fb4a" with the BuildTargetParser and test all of its observers.
    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    BuildTarget buildTarget = parser.parse("//:fb4a", ParseContext.fullyQualified());
    assertEquals(mockBuildFile, buildTarget.getBuildFile());
    assertEquals(mockBuildFileDirectory, buildTarget.getBuildFileDirectory());
    assertEquals("fb4a", buildTarget.getShortName());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals("", buildTarget.getBasePath());
    assertEquals("", buildTarget.getBasePathWithSlash());
    assertEquals("//:fb4a", buildTarget.getFullyQualifiedName());

    verify(mockBuildFile, mockBuildFileDirectory, mockProjectFilesystem);
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
    File mockBuildFile = createMock(File.class);
    File mockBuildDirectory = createMock(File.class);
    expect(mockBuildFile.isFile()).andReturn(true);
    expect(mockBuildFile.getParentFile()).andReturn(mockBuildDirectory);
    expect(mockBuildDirectory.getAbsolutePath()).andReturn("/path/to/root/facebook/orca");
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists("facebook/orca")).andReturn(true);
    expect(mockProjectFilesystem.exists("facebook/orca/" + BuckConstant.BUILD_RULES_FILE_NAME))
        .andReturn(true);
    expect(mockProjectFilesystem.getFileForRelativePath(
        "facebook/orca/" + BuckConstant.BUILD_RULES_FILE_NAME)).andReturn(mockBuildFile);
    replay(mockBuildFile, mockBuildDirectory, mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    BuildTarget buildTarget = parser.parse("//facebook/orca:assets", ParseContext.fullyQualified());
    assertEquals(mockBuildFile, buildTarget.getBuildFile());
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortName());

    verify(mockBuildFile, mockBuildDirectory, mockProjectFilesystem);
  }

  @Test
  public void testParseBuildFile() throws NoSuchBuildTargetException {
    File mockBuildFile = createMock(File.class);
    File mockBuildDirectory = createMock(File.class);
    expect(mockBuildFile.isFile()).andReturn(true);
    expect(mockBuildFile.getParentFile()).andReturn(mockBuildDirectory);
    expect(mockBuildDirectory.getAbsolutePath()).andReturn("/path/to/root/facebook/orca");
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists("facebook/orca")).andReturn(true);
    expect(mockProjectFilesystem.exists("facebook/orca/" + BuckConstant.BUILD_RULES_FILE_NAME))
        .andReturn(true);
    expect(mockProjectFilesystem.getFileForRelativePath(
        "facebook/orca/" + BuckConstant.BUILD_RULES_FILE_NAME)).andReturn(mockBuildFile);
    replay(mockBuildFile, mockBuildDirectory, mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    BuildTarget buildTarget = parser.parse(":assets", ParseContext.forBaseName("//facebook/orca"));
    assertEquals(mockBuildFile, buildTarget.getBuildFile());
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortName());

    verify(mockBuildFile, mockBuildDirectory, mockProjectFilesystem);
  }

  @Test
  public void testParseBuildFileMissingBuildDirectoryFullyQualified() {
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists("facebook/missing")).andReturn(false);
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
    expect(mockProjectFilesystem.exists("facebook/missing")).andReturn(false);
    replay(mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    try {
      parser.parse("//facebook/missing:assets", ParseContext.forBaseName("//facebook/orca"));
      fail("parse() should throw an exception");
    } catch (NoSuchBuildTargetException e) {
      assertEquals("No directory facebook/missing when resolving target " +
          "//facebook/missing:assets in build file //facebook/orca/" +
          BuckConstant.BUILD_RULES_FILE_NAME, e.getMessage());
    }

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseBuildFileMissingBuildFile() throws NoSuchBuildTargetException {
    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists("facebook/missing")).andReturn(true);
    expect(mockProjectFilesystem.exists("facebook/missing/" + BuckConstant.BUILD_RULES_FILE_NAME))
        .andReturn(false);
    replay(mockProjectFilesystem);

    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    try {
      parser.parse("//facebook/missing:assets", ParseContext.forBaseName("//facebook/orca"));
      fail("parse() should throw an exception");
    } catch (NoSuchBuildTargetException e) {
      assertEquals(
          "No " + BuckConstant.BUILD_RULES_FILE_NAME +
              " file facebook/missing/" + BuckConstant.BUILD_RULES_FILE_NAME + " when resolving target " +
              "//facebook/missing:assets in build file //facebook/orca/" +
              BuckConstant.BUILD_RULES_FILE_NAME,
          e.getMessage());
    }

    verify(mockProjectFilesystem);
  }

  @Test
  public void testParseWithVisibilityContext() throws NoSuchBuildTargetException {
    // Mock out all of the calls to the filesystem.
    File mockBuildFileDirectory = createMock(File.class);
    expect(mockBuildFileDirectory.getAbsolutePath()).andReturn(
        "/home/mbolin/java/com/example");

    File mockBuildFile = createMock(File.class);
    expect(mockBuildFile.isFile()).andReturn(true);
    expect(mockBuildFile.getParentFile()).andReturn(mockBuildFileDirectory).anyTimes();

    ProjectFilesystem mockProjectFilesystem = createMock(ProjectFilesystem.class);
    expect(mockProjectFilesystem.exists("java/com/example")).andReturn(true);
    expect(mockProjectFilesystem.exists("java/com/example/" + BuckConstant.BUILD_RULES_FILE_NAME))
        .andReturn(true);
    expect(mockProjectFilesystem.getFileForRelativePath(
        "java/com/example/" + BuckConstant.BUILD_RULES_FILE_NAME)).andReturn(mockBuildFile);
    replay(mockProjectFilesystem, mockBuildFile, mockBuildFileDirectory);

    // Invoke the BuildTargetParser using the VISIBILITY context.
    BuildTargetParser parser = new BuildTargetParser(mockProjectFilesystem);
    ParseContext parseContext = ParseContext.forVisibilityArgument();
    BuildTarget target = parser.parse("//java/com/example:", parseContext);
    assertEquals(
        "A build target that ends with a colon should be treated as a wildcard build target " +
        "when parsed in the context of a visibility argument.",
        "//java/com/example:",
        target.getFullyQualifiedName());

    verify(mockProjectFilesystem, mockBuildFile, mockBuildFileDirectory);
  }
}
