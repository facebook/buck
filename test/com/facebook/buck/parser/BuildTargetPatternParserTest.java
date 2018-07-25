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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.CellPathResolverView;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.FileSystem;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildTargetPatternParserTest {

  private ProjectFilesystem filesystem;
  private FileSystem vfs;

  @Rule public ExpectedException exception = ExpectedException.none();

  @Before
  public void setUp() throws InterruptedException {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    vfs = filesystem.getRootPath().getFileSystem();
  }

  @Test
  public void testParse() throws NoSuchBuildTargetException {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    assertEquals(
        ImmediateDirectoryBuildTargetPattern.of(
            filesystem.getRootPath(), vfs.getPath("test/com/facebook/buck/parser/")),
        buildTargetPatternParser.parse(
            createCellRoots(filesystem), "//test/com/facebook/buck/parser:"));

    assertEquals(
        SingletonBuildTargetPattern.of(
            filesystem.getRootPath(), "//test/com/facebook/buck/parser:parser"),
        buildTargetPatternParser.parse(
            createCellRoots(filesystem), "//test/com/facebook/buck/parser:parser"));

    assertEquals(
        SubdirectoryBuildTargetPattern.of(
            filesystem.getRootPath(), vfs.getPath("test/com/facebook/buck/parser/")),
        buildTargetPatternParser.parse(
            createCellRoots(filesystem), "//test/com/facebook/buck/parser/..."));
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseWildcardWithInvalidContext() throws NoSuchBuildTargetException {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.fullyQualified();

    buildTargetPatternParser.parse(createCellRoots(filesystem), "//...");
  }

  @Test
  public void testParseRootPattern() throws NoSuchBuildTargetException {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    assertEquals(
        ImmediateDirectoryBuildTargetPattern.of(filesystem.getRootPath(), vfs.getPath("")),
        buildTargetPatternParser.parse(createCellRoots(filesystem), "//:"));

    assertEquals(
        SingletonBuildTargetPattern.of(filesystem.getRootPath(), "//:parser"),
        buildTargetPatternParser.parse(createCellRoots(filesystem), "//:parser"));

    assertEquals(
        SubdirectoryBuildTargetPattern.of(filesystem.getRootPath(), vfs.getPath("")),
        buildTargetPatternParser.parse(createCellRoots(filesystem), "//..."));
  }

  @Test
  public void visibilityCanContainCrossCellReference() {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    CellPathResolver cellNames =
        DefaultCellPathResolver.of(
            filesystem.getPath("foo/root"),
            ImmutableMap.of("other", filesystem.getPath("foo/other")));

    assertEquals(
        SingletonBuildTargetPattern.of(filesystem.getPath("foo/other"), "//:something"),
        buildTargetPatternParser.parse(cellNames, "other//:something"));
    assertEquals(
        SubdirectoryBuildTargetPattern.of(
            filesystem.getPath("foo/other"), filesystem.getPath("sub")),
        buildTargetPatternParser.parse(cellNames, "other//sub/..."));
  }

  @Test
  public void visibilityCanMatchCrossCellTargets() {
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    CellPathResolver rootCellPathResolver =
        DefaultCellPathResolver.of(
            filesystem.getPath("root").normalize(),
            ImmutableMap.of(
                "other", filesystem.getPath("other").normalize(),
                "root", filesystem.getPath("root").normalize()));
    CellPathResolver otherCellPathResolver =
        new CellPathResolverView(
            rootCellPathResolver, ImmutableSet.of("root"), filesystem.getPath("other").normalize());

    // Root cell visibility from non-root cell
    Stream.of("other//lib:lib", "other//lib:", "other//lib/...")
        .forEach(
            patternString -> {
              BuildTargetPattern pattern =
                  buildTargetPatternParser.parse(rootCellPathResolver, patternString);
              assertTrue(
                  "from root matching something in non-root: " + pattern,
                  pattern.matches(
                      BuildTargetParser.INSTANCE.parse(
                          "//lib:lib",
                          BuildTargetPatternParser.fullyQualified(),
                          otherCellPathResolver)));
              assertFalse(
                  "from root failing to match something in root: " + pattern,
                  pattern.matches(
                      BuildTargetParser.INSTANCE.parse(
                          "//lib:lib",
                          BuildTargetPatternParser.fullyQualified(),
                          rootCellPathResolver)));
            });

    // Non-root cell visibility from root cell.
    Stream.of("root//lib:lib", "root//lib:", "root//lib/...")
        .forEach(
            patternString -> {
              BuildTargetPattern pattern =
                  buildTargetPatternParser.parse(otherCellPathResolver, patternString);
              assertTrue(
                  "from non-root matching something in root: " + pattern,
                  pattern.matches(
                      BuildTargetParser.INSTANCE.parse(
                          "//lib:lib",
                          BuildTargetPatternParser.fullyQualified(),
                          rootCellPathResolver)));
              assertFalse(
                  "from non-root matching something in non-root: " + pattern,
                  pattern.matches(
                      BuildTargetParser.INSTANCE.parse(
                          "//lib:lib",
                          BuildTargetPatternParser.fullyQualified(),
                          otherCellPathResolver)));
            });
  }

  @Test
  public void testParseAbsolutePath() {
    // Exception should be thrown by BuildTargetParser.checkBaseName()
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();

    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("absolute");
    exception.expectMessage("(found ///facebookorca/...)");
    buildTargetPatternParser.parse(createCellRoots(filesystem), "///facebookorca/...");
  }
}
