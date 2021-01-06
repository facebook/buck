/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser.spec;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPatternParser;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class TargetNodeSpecTest {
  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  @SuppressWarnings("unused")
  private String[] getTargetPatterns() {
    return new String[] {
      "//:",
      "//:mytarget",
      "//:mytarget#myflavor",
      "//...",
      "//mypackage:",
      "//mypackage:mytarget",
      "//mypackage:mytarget#myflavor",
      "//mypackage/...",
      "mycell//mypackage:",
      "mycell//mypackage:mytarget",
      "mycell//mypackage:mytarget#myflavor",
      "mycell//mypackage/...",
      "nestedcell//mypackage:",
      "nestedcell//mypackage:mytarget",
      "nestedcell//mypackage:mytarget#myflavor",
      "nestedcell//mypackage/...",
    };
  }

  /**
   * Verify that {@link TargetNodeSpec#getBuildTargetPattern(Cell)} returns the same {@link
   * BuildTargetPattern} as {@link BuildTargetPatternParser#parse(String)}.
   */
  @Test
  @Parameters(method = "getTargetPatterns")
  public void buildTargetPatternMatchesBuildTargetPatternParser(String pattern) throws IOException {
    ProjectFilesystem rootFileSystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    Path defaultCellPath = rootFileSystem.resolve("defaultcell-dir");
    rootFileSystem.mkdirs(defaultCellPath);
    ProjectFilesystem currentCellFileSystem =
        TestProjectFilesystems.createProjectFilesystem(defaultCellPath);

    Path myCellPath = rootFileSystem.resolve("mycell-dir");
    rootFileSystem.mkdirs(myCellPath);

    Path nestedCellPath = defaultCellPath.resolve("nestedcell-dir");
    rootFileSystem.mkdirs(nestedCellPath);

    Cells defaultCell =
        getDefaultCell(
            currentCellFileSystem,
            ImmutableMap.of("mycell", myCellPath, "nestedcell", nestedCellPath));

    TargetNodeSpec spec = parseTargetNodeSpec(defaultCell.getRootCell(), pattern);
    Assert.assertEquals(
        BuildTargetPatternParser.parse(pattern, defaultCell.getRootCell().getCellNameResolver()),
        spec.getBuildTargetPattern(getCellOfTargetNodeSpec(defaultCell.getRootCell(), spec)));
  }

  @SuppressWarnings("unused")
  private String[] getCellTargetPatterns() {
    return new String[] {
      "cell-a//:", "cell-a//:mytarget", "cell-a//:mytarget#myflavor", "cell-a//...",
    };
  }

  /**
   * Verify that {@link TargetNodeSpec#getBuildTargetPattern(Cell)} rejects {@link Cell} objects
   * which don't match the cell referenced by the {@link TargetNodeSpec}.
   */
  @Test
  @Parameters(method = "getCellTargetPatterns")
  public void gettingBuildTargetPatternFailsIfGivenCellDoesNotMatch(String pattern)
      throws IOException {
    ProjectFilesystem rootFileSystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    Path cellAPath = rootFileSystem.resolve("cell-a-dir");
    rootFileSystem.mkdirs(cellAPath);

    Path cellBPath = rootFileSystem.resolve("cell-b-dir");
    rootFileSystem.mkdirs(cellBPath);

    Cells defaultCell =
        getDefaultCell(rootFileSystem, ImmutableMap.of("cell-a", cellAPath, "cell-b", cellBPath));
    Cell cellB = defaultCell.getRootCell().getCell(cellBPath);

    TargetNodeSpec spec = parseTargetNodeSpec(defaultCell.getRootCell(), pattern);
    thrown.expectMessage(Matchers.containsString("cell-a"));
    thrown.expectMessage(Matchers.containsString("cell-b"));
    if (spec instanceof BuildTargetSpec) {
      // BuildTargetSpec has enough information to construct the pattern without a cell argument.
      // Ensure it includes the original pattern as part of its message.
      thrown.expectMessage(Matchers.containsString(pattern));
    }
    thrown.expect(IllegalArgumentException.class);
    spec.getBuildTargetPattern(cellB);
  }

  private Cells getDefaultCell(
      ProjectFilesystem rootFileSystem, ImmutableMap<String, Path> otherCells) {
    ImmutableMap.Builder<String, String> repositories = ImmutableMap.builder();
    for (Map.Entry<String, Path> entry : otherCells.entrySet()) {
      repositories.put(entry.getKey(), entry.getValue().toString());
    }
    BuckConfig cellConfig =
        FakeBuckConfig.builder()
            .setFilesystem(rootFileSystem)
            .setSections(ImmutableMap.of("repositories", repositories.build()))
            .build();
    return new TestCellBuilder().setBuckConfig(cellConfig).setFilesystem(rootFileSystem).build();
  }

  private Cell getCellOfTargetNodeSpec(Cell currentCell, TargetNodeSpec spec) {
    return currentCell
        .getCellProvider()
        .getCellByCanonicalCellName(
            spec.getBuildFileSpec().getCellRelativeBaseName().getCellName());
  }

  private TargetNodeSpec parseTargetNodeSpec(Cell cell, String targetPattern) {
    return (new BuildTargetMatcherTargetNodeParser())
        .parse(targetPattern, cell.getCellNameResolver());
  }
}
