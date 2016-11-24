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

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.ImmediateDirectoryBuildTargetPattern;
import com.facebook.buck.model.SingletonBuildTargetPattern;
import com.facebook.buck.model.SubdirectoryBuildTargetPattern;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.FakeCellPathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.FileSystem;

public class BuildTargetPatternParserTest {

  private final ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  private final FileSystem vfs = filesystem.getRootPath().getFileSystem();

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Test
  public void testParse() throws NoSuchBuildTargetException {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    assertEquals(
        new ImmediateDirectoryBuildTargetPattern(
            filesystem.getRootPath(),
            vfs.getPath("test/com/facebook/buck/parser/")),
        buildTargetPatternParser.parse(
            createCellRoots(filesystem)::getCellPath,
            "//test/com/facebook/buck/parser:"));

    assertEquals(
        new SingletonBuildTargetPattern(
            filesystem.getRootPath(),
            "//test/com/facebook/buck/parser:parser"),
        buildTargetPatternParser.parse(
            createCellRoots(filesystem)::getCellPath,
            "//test/com/facebook/buck/parser:parser"));

    assertEquals(
        new SubdirectoryBuildTargetPattern(
            filesystem.getRootPath(),
            vfs.getPath("test/com/facebook/buck/parser/")),
        buildTargetPatternParser.parse(
            createCellRoots(filesystem)::getCellPath,
            "//test/com/facebook/buck/parser/..."));
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseWildcardWithInvalidContext() throws NoSuchBuildTargetException {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.fullyQualified();

    buildTargetPatternParser.parse(createCellRoots(filesystem)::getCellPath, "//...");
  }

  @Test
  public void testParseRootPattern() throws NoSuchBuildTargetException {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    assertEquals(
        new ImmediateDirectoryBuildTargetPattern(
            filesystem.getRootPath(),
            vfs.getPath("")),
        buildTargetPatternParser.parse(createCellRoots(filesystem)::getCellPath, "//:"));

    assertEquals(
        new SingletonBuildTargetPattern(filesystem.getRootPath(), "//:parser"),
        buildTargetPatternParser.parse(createCellRoots(filesystem)::getCellPath, "//:parser"));

    assertEquals(
        new SubdirectoryBuildTargetPattern(
            filesystem.getRootPath(),
            vfs.getPath("")),
        buildTargetPatternParser.parse(createCellRoots(filesystem)::getCellPath, "//..."));
  }

  @Test
  public void visibilityCanContainCrossCellReference() {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    final ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    CellPathResolver cellNames = new FakeCellPathResolver(
        ImmutableMap.of("other", filesystem.getRootPath()));

    assertEquals(
        new SingletonBuildTargetPattern(filesystem.getRootPath(), "//:something"),
        buildTargetPatternParser.parse(cellNames::getCellPath, "other//:something"));
    assertEquals(
        new SubdirectoryBuildTargetPattern(
            filesystem.getRootPath(),
            filesystem.getRootPath().getFileSystem().getPath("sub")),
        buildTargetPatternParser.parse(cellNames::getCellPath, "other//sub/..."));
  }

  @Test
  public void testParseAbsolutePath() {
    // Exception should be thrown by BuildTargetParser.checkBaseName()
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("Build target path cannot be absolute or contain . or .. " +
        "(found ///facebookorca/...)");
    buildTargetPatternParser.parse(createCellRoots(filesystem)::getCellPath, "///facebookorca/...");
  }
}
