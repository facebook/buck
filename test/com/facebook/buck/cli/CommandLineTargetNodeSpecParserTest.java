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
import com.facebook.buck.testutil.FakeProjectFilesystem;

import org.junit.Test;

import java.nio.file.Paths;

public class CommandLineTargetNodeSpecParserTest {

  private static final CommandLineTargetNodeSpecParser PARSER =
      new CommandLineTargetNodeSpecParser(
          FakeBuckConfig.builder().build(),
          new BuildTargetPatternTargetNodeParser());

  @Test
  public void trailingDotDotDot() {
    ProjectFilesystem root = new FakeProjectFilesystem();
    assertEquals(
        BuildFileSpec.fromRecursivePath(
            Paths.get("hello").toAbsolutePath(),
            root.getRootPath()),
        PARSER.parse(createCellRoots(root), "//hello/...").getBuildFileSpec());
    assertEquals(
        BuildFileSpec.fromRecursivePath(
            Paths.get("").toAbsolutePath(),
            root.getRootPath()),
        PARSER.parse(createCellRoots(root), "//...").getBuildFileSpec());
    assertEquals(
        BuildFileSpec.fromRecursivePath(
            Paths.get("").toAbsolutePath(),
            root.getRootPath()),
        PARSER.parse(createCellRoots(root), "...").getBuildFileSpec());
    assertEquals(
        BuildTargetSpec.from(BuildTargetFactory.newInstance("//hello:...")),
        PARSER.parse(createCellRoots(root), "//hello:..."));
  }

  @Test
  public void tailingColon() {
    assertEquals(
        BuildFileSpec.fromPath(
            Paths.get("hello").toAbsolutePath(),
            Paths.get("").toAbsolutePath()),
        PARSER.parse(createCellRoots(null), "//hello:").getBuildFileSpec());
  }

  @Test
  public void normalizeBuildTargets() {
    assertEquals("//:", PARSER.normalizeBuildTargetString("//:"));
    assertEquals("//:", PARSER.normalizeBuildTargetString(":"));
    assertEquals("//...", PARSER.normalizeBuildTargetString("//..."));
    assertEquals("//...", PARSER.normalizeBuildTargetString("..."));
  }

  @Test
  public void crossCellTargets(){
    assertEquals("@other//:", PARSER.normalizeBuildTargetString("@other//:"));
    assertEquals("+other//...", PARSER.normalizeBuildTargetString("+other//..."));
    assertEquals("other//:", PARSER.normalizeBuildTargetString("other//"));
  }

}
