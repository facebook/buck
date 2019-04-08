/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CommandLineTargetNodeSpecParserTest {

  private static final CommandLineTargetNodeSpecParser PARSER =
      new CommandLineTargetNodeSpecParser(
          FakeBuckConfig.builder()
              .setSections(
                  "[alias]", "  foo = //some:thing", "  bar = //some:thing //some/other:thing")
              .build(),
          new BuildTargetPatternTargetNodeParser());

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void trailingDotDotDot() throws Exception {
    ProjectFilesystem root = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Path directory = root.getPathForRelativePath("hello");
    Files.createDirectories(directory);
    assertEquals(
        BuildFileSpec.fromRecursivePath(directory.toAbsolutePath(), root.getRootPath()),
        parseOne(createCell(root), "//hello/...").getBuildFileSpec());
    assertEquals(
        BuildFileSpec.fromRecursivePath(root.getRootPath(), root.getRootPath()),
        parseOne(createCell(root), "//...").getBuildFileSpec());
    assertEquals(
        BuildFileSpec.fromRecursivePath(root.getRootPath(), root.getRootPath()),
        parseOne(createCell(root), "...").getBuildFileSpec());
    assertEquals(
        BuildTargetSpec.from(
            UnconfiguredBuildTargetFactoryForTests.newInstance(root.getRootPath(), "//hello:...")),
        parseOne(createCell(root), "//hello:..."));
  }

  @Test
  public void aliasExpansion() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    filesystem.mkdirs(Paths.get("some/other"));
    assertEquals(
        ImmutableSet.of(
            BuildTargetSpec.from(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//some:thing"))),
        PARSER.parse(cell, "foo"));
    assertEquals(
        ImmutableSet.of(
            BuildTargetSpec.from(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//some:thing")),
            BuildTargetSpec.from(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//some/other:thing"))),
        PARSER.parse(cell, "bar"));
    assertEquals(
        ImmutableSet.of(
            BuildTargetSpec.from(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//some:thing#fl")),
            BuildTargetSpec.from(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//some/other:thing#fl"))),
        PARSER.parse(cell, "bar#fl"));
  }

  @Test
  public void tailingColon() throws Exception {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Path packageDirectory = filesystem.getPathForRelativePath("hello");
    Files.createDirectories(packageDirectory);
    assertEquals(
        BuildFileSpec.fromPath(packageDirectory, filesystem.getRootPath()),
        parseOne(createCell(filesystem), "//hello:").getBuildFileSpec());
  }

  private TargetNodeSpec parseOne(Cell cell, String arg) {
    return Iterables.getOnlyElement(PARSER.parse(cell, arg));
  }

  @Test
  public void normalizeBuildTargets() {
    assertEquals("//:", PARSER.normalizeBuildTargetString("//:"));
    assertEquals("//:", PARSER.normalizeBuildTargetString(":"));
    assertEquals("//...", PARSER.normalizeBuildTargetString("//..."));
    assertEquals("//...", PARSER.normalizeBuildTargetString("..."));
  }

  @Test
  public void crossCellTargets() {
    assertEquals("@other//:", PARSER.normalizeBuildTargetString("@other//:"));
    assertEquals("+other//...", PARSER.normalizeBuildTargetString("+other//..."));
    assertEquals("other//:", PARSER.normalizeBuildTargetString("other//"));
  }

  @Test
  public void cannotReferenceNonExistentDirectoryInARecursivelyWildcard() {
    Cell cell = createCell(null);
    CellPathResolver cellRoots = cell.getCellPathResolver();
    Path cellPath = cellRoots.getCellPathOrThrow(Optional.empty());
    exception.expectMessage(
        "does_not_exist/... references non-existent directory "
            + cellPath.resolve("does_not_exist"));
    exception.expect(HumanReadableException.class);
    PARSER.parse(cell, "does_not_exist/...");
  }

  @Test
  public void cannotReferenceNonExistentDirectoryWithPackageTargetNames() {
    Cell cell = createCell(null);
    CellPathResolver cellRoots = cell.getCellPathResolver();
    Path cellPath = cellRoots.getCellPathOrThrow(Optional.empty());
    exception.expectMessage(
        "does_not_exist: references non-existent directory " + cellPath.resolve("does_not_exist"));
    exception.expect(HumanReadableException.class);
    PARSER.parse(cell, "does_not_exist:");
  }

  @Test
  public void cannotReferenceNonExistentDirectoryWithImplicitTargetName() {
    exception.expectMessage("does_not_exist references non-existent directory does_not_exist");
    exception.expect(HumanReadableException.class);
    PARSER.parse(createCell(null), "does_not_exist");
  }

  private Cell createCell(@Nullable ProjectFilesystem filesystem) {
    TestCellBuilder builder = new TestCellBuilder();
    if (filesystem != null) {
      builder.setFilesystem(filesystem);
    }
    return builder.build();
  }
}
