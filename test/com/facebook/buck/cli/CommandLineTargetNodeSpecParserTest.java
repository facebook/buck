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

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import org.junit.Test;

public class CommandLineTargetNodeSpecParserTest {

  private static final CommandLineTargetNodeSpecParser PARSER =
      new CommandLineTargetNodeSpecParser(
          FakeBuckConfig.builder()
              .setSections(
                  "[alias]", "  foo = //some:thing", "  bar = //some:thing //some/other:thing")
              .build(),
          new BuildTargetPatternTargetNodeParser());

  @Test
  public void trailingDotDotDot() {
    ProjectFilesystem root = new FakeProjectFilesystem();
    assertEquals(
        BuildFileSpec.fromRecursivePath(Paths.get("hello").toAbsolutePath(), root.getRootPath()),
        parseOne(createCellRoots(root), "//hello/...").getBuildFileSpec());
    assertEquals(
        BuildFileSpec.fromRecursivePath(Paths.get("").toAbsolutePath(), root.getRootPath()),
        parseOne(createCellRoots(root), "//...").getBuildFileSpec());
    assertEquals(
        BuildFileSpec.fromRecursivePath(Paths.get("").toAbsolutePath(), root.getRootPath()),
        parseOne(createCellRoots(root), "...").getBuildFileSpec());
    assertEquals(
        BuildTargetSpec.from(BuildTargetFactory.newInstance("//hello:...")),
        parseOne(createCellRoots(root), "//hello:..."));
  }

  @Test
  public void aliasExpansion() {
    assertEquals(
        ImmutableSet.of(BuildTargetSpec.from(BuildTargetFactory.newInstance("//some:thing"))),
        PARSER.parse(createCellRoots(null), "foo"));
    assertEquals(
        ImmutableSet.of(
            BuildTargetSpec.from(BuildTargetFactory.newInstance("//some:thing")),
            BuildTargetSpec.from(BuildTargetFactory.newInstance("//some/other:thing"))),
        PARSER.parse(createCellRoots(null), "bar"));
    assertEquals(
        ImmutableSet.of(
            BuildTargetSpec.from(BuildTargetFactory.newInstance("//some:thing#fl")),
            BuildTargetSpec.from(BuildTargetFactory.newInstance("//some/other:thing#fl"))),
        PARSER.parse(createCellRoots(null), "bar#fl"));
  }

  @Test
  public void tailingColon() {
    assertEquals(
        BuildFileSpec.fromPath(Paths.get("hello").toAbsolutePath(), Paths.get("").toAbsolutePath()),
        parseOne(createCellRoots(null), "//hello:").getBuildFileSpec());
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
}
