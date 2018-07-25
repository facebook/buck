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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
        parseOne(createCellRoots(root), "//hello/...").getBuildFileSpec());
    assertEquals(
        BuildFileSpec.fromRecursivePath(root.getRootPath(), root.getRootPath()),
        parseOne(createCellRoots(root), "//...").getBuildFileSpec());
    assertEquals(
        BuildFileSpec.fromRecursivePath(root.getRootPath(), root.getRootPath()),
        parseOne(createCellRoots(root), "...").getBuildFileSpec());
    assertEquals(
        BuildTargetSpec.from(BuildTargetFactory.newInstance(root.getRootPath(), "//hello:...")),
        parseOne(createCellRoots(root), "//hello:..."));
  }

  @Test
  public void aliasExpansion() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CellPathResolver cellRoots = createCellRoots(filesystem);
    Files.createDirectories(
        cellRoots.getCellPathOrThrow(Optional.empty()).resolve("some").resolve("other"));
    assertEquals(
        ImmutableSet.of(BuildTargetSpec.from(BuildTargetFactory.newInstance("//some:thing"))),
        PARSER.parse(cellRoots, "foo"));
    assertEquals(
        ImmutableSet.of(
            BuildTargetSpec.from(BuildTargetFactory.newInstance("//some:thing")),
            BuildTargetSpec.from(BuildTargetFactory.newInstance("//some/other:thing"))),
        PARSER.parse(cellRoots, "bar"));
    assertEquals(
        ImmutableSet.of(
            BuildTargetSpec.from(BuildTargetFactory.newInstance("//some:thing#fl")),
            BuildTargetSpec.from(BuildTargetFactory.newInstance("//some/other:thing#fl"))),
        PARSER.parse(cellRoots, "bar#fl"));
  }

  @Test
  public void tailingColon() throws Exception {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Path packageDirectory = filesystem.getPathForRelativePath("hello");
    Files.createDirectories(packageDirectory);
    assertEquals(
        BuildFileSpec.fromPath(packageDirectory, filesystem.getRootPath()),
        parseOne(createCellRoots(filesystem), "//hello:").getBuildFileSpec());
  }

  private TargetNodeSpec parseOne(CellPathResolver cellRoots, String arg) {
    return Iterables.getOnlyElement(PARSER.parse(cellRoots, arg));
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
    CellPathResolver cellRoots = createCellRoots(null);
    Path cellPath = cellRoots.getCellPathOrThrow(Optional.empty());
    exception.expectMessage(
        "does_not_exist/... references non-existent directory "
            + cellPath.resolve("does_not_exist"));
    exception.expect(HumanReadableException.class);
    PARSER.parse(cellRoots, "does_not_exist/...");
  }

  @Test
  public void cannotReferenceNonExistentDirectoryWithPackageTargetNames() {
    CellPathResolver cellRoots = createCellRoots(null);
    Path cellPath = cellRoots.getCellPathOrThrow(Optional.empty());
    exception.expectMessage(
        "does_not_exist: references non-existent directory " + cellPath.resolve("does_not_exist"));
    exception.expect(HumanReadableException.class);
    PARSER.parse(cellRoots, "does_not_exist:");
  }

  @Test
  public void cannotReferenceNonExistentDirectoryWithImplicitTargetName() {
    CellPathResolver cellRoots = createCellRoots(null);
    exception.expectMessage("does_not_exist references non-existent directory does_not_exist");
    exception.expect(HumanReadableException.class);
    PARSER.parse(cellRoots, "does_not_exist");
  }
}
