/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class InferLogLineTest {

  private static CellPathResolver createFakeCellPathResolver(
      final Map<String, Path> cellToRootPathMap,
      final Optional<Path> defaultRootPath) {
    return new CellPathResolver() {
      @Override
      public Path getCellPath(Optional<String> cellName) {
        if (!cellName.isPresent()) {
          return defaultRootPath.get();
        }
        Path p = cellToRootPathMap.get(cellName.get());
        if (p == null) {
          throw new RuntimeException("Cellname " + cellName + " is not part of the given map");
        }
        return p;
      }
    };
  }

  @Test
  public void testToStringHasCellNameTokenInPathWhenCellNameIsKnown() {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    BuildTarget testBuildTarget = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                Paths.get("/Users/user/src"),
                Optional.of("cellname"),
                "//target",
                "short"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .build();

    InferLogLine inferLogLine = new InferLogLine(testBuildTarget, Paths.get("buck-out/a/b/c/"));
    String expectedOutput = "cellname//target:short#infer\t[infer]\tcellname//buck-out/a/b/c";
    assertEquals(expectedOutput, inferLogLine.toString());
  }

  @Test
  public void testToContextualizedStringHasAbsolutePathWhenCellNameIsKnown() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    Path rootPath = Paths.get("/Users/user/src");
    BuildTarget testBuildTarget = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                rootPath,
                Optional.of("cellname"),
                "//target",
                "short"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .build();

    ImmutableMap<String, Path> map = ImmutableMap.<String, Path>builder()
        .put("cellname", rootPath)
        .build();

    CellPathResolver cellPathResolver = createFakeCellPathResolver(map, Optional.<Path>absent());

    InferLogLine inferLogLine = new InferLogLine(testBuildTarget, Paths.get("buck-out/a/b/c/"));
    Path expectedContextualizedPath = rootPath.resolve("buck-out/a/b/c");
    assertTrue("Contextualized path must be absolute", expectedContextualizedPath.isAbsolute());
    String expectedOutput =
        "cellname//target:short#infer\t[infer]\t" + expectedContextualizedPath.toString();
    assertEquals(expectedOutput, inferLogLine.toContextualizedString(cellPathResolver));
  }

  @Test
  public void testToStringHasNoCellNameTokenInPathWhenCellNameIsAbsent() {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    BuildTarget testBuildTarget = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                Paths.get("/Users/user/src"),
                Optional.<String>absent(),
                "//target",
                "short"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .build();

    InferLogLine inferLogLine = new InferLogLine(testBuildTarget, Paths.get("buck-out/a/b/c/"));
    String expectedOutput = "//target:short#infer\t[infer]\tbuck-out/a/b/c";
    assertEquals(expectedOutput, inferLogLine.toString());
  }

  @Test
  public void testToContextualizedStringHasAbsolutePathWhenCellNameIsAbsent() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    Path rootPath = Paths.get("/Users/user/src");
    BuildTarget testBuildTarget = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                rootPath,
                Optional.<String>absent(),
                "//target",
                "short"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .build();

    CellPathResolver cellPathResolver =
        createFakeCellPathResolver(ImmutableMap.<String, Path>of(), Optional.of(rootPath));

    InferLogLine inferLogLine = new InferLogLine(testBuildTarget, Paths.get("buck-out/a/b/c/"));
    Path expectedContextualizedPath = rootPath.resolve("buck-out/a/b/c");
    assertTrue("Contextualized path must be absolute", expectedContextualizedPath.isAbsolute());
    String expectedOutput =
        "//target:short#infer\t[infer]\t" + expectedContextualizedPath.toString();
    assertEquals(expectedOutput, inferLogLine.toContextualizedString(cellPathResolver));
  }

  @Test
  public void testFromLineWithoutCellTokensToStringShouldReturnSameLine() {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    String line = "//target:short#infer\t[infer]\tbuck-out/a/b/c/";
    assertEquals(line, InferLogLine.fromLine(line).toString());
  }

  @Test
  public void testFromLineWithCellTokensToStringShouldReturnSameLine() {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    String line = "//target:short#infer\t[infer]\tcellname//buck-out/a/b/c/";
    assertEquals(line, InferLogLine.fromLine(line).toString());
  }

  @Test
  public void testFromLineWithCellTokensToContextualizedString() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    String cellName = "cellname";
    Path rootPath = Paths.get("/Users/user/src");
    String line = "//target:short#infer\t[infer]\t" + cellName + "//buck-out/a/b/c/";
    Path expectedOutputPath = rootPath.resolve("buck-out/a/b/c");
    assertTrue("Path must be absolute", expectedOutputPath.isAbsolute());
    String expectedOutput =
        "//target:short#infer\t[infer]\t" + expectedOutputPath.toString();
    ImmutableMap<String, Path> map = ImmutableMap.<String, Path>builder()
        .put("cellname", rootPath)
        .build();
    CellPathResolver cellPathResolver =
        createFakeCellPathResolver(map, Optional.<Path>absent());
    assertEquals(
        expectedOutput, InferLogLine.fromLine(line).toContextualizedString(cellPathResolver));
  }

  @Test
  public void testFromLineWithoutCellTokensToContextualizedString() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    Path rootPath = Paths.get("/Users/user/src");
    String line = "//target:short#infer\t[infer]\tbuck-out/a/b/c/";
    Path expectedOutputPath = rootPath.resolve("buck-out/a/b/c");
    assertTrue("Path must be absolute", expectedOutputPath.isAbsolute());
    String expectedOutput =
        "//target:short#infer\t[infer]\t" + expectedOutputPath.toString();
    CellPathResolver cellPathResolver =
        createFakeCellPathResolver(ImmutableMap.<String, Path>of(), Optional.of(rootPath));
    assertEquals(
        expectedOutput, InferLogLine.fromLine(line).toContextualizedString(cellPathResolver));
  }

  @Test
  public void testFromLineWithContextualizedPathToStringReturnSameLine() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    String line = "//target:short#infer\t[infer]\t/Users/user/src/buck-out/a/b/c";
    assertEquals(
        line, InferLogLine.fromLine(line).toString());
  }

  @Test
  public void testFromLineWithContextualizedPathToContextualizedStringShouldReturnSameLine()
      throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    Path rootPath = Paths.get("/Users/user/src");
    String line = "//target:short#infer\t[infer]\t/Users/user/src/buck-out/a/b/c";
    CellPathResolver cellPathResolver =
        createFakeCellPathResolver(ImmutableMap.<String, Path>of(), Optional.of(rootPath));
    assertEquals(
        line, InferLogLine.fromLine(line).toContextualizedString(cellPathResolver));
  }
}
