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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class UnflavoredBuildTargetFactoryTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private Cells cell;

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
  }

  @Test
  public void createSucceeds() {
    Path buildFilePath = cell.getRootCell().getFilesystem().resolve("BUCK");
    RelPath relativeBuildFilePath = cell.getRootCell().getFilesystem().relativize(buildFilePath);
    String base_path = MorePaths.getParentOrEmpty(relativeBuildFilePath).toString();

    Map<String, Object> malformedMap = ImmutableMap.of("buck.base_path", base_path, "name", "bar");

    UnflavoredBuildTargetFactory.createFromRawNode(
        cell.getRootCell().getRoot().getPath(),
        cell.getRootCell().getCanonicalName(),
        malformedMap,
        buildFilePath);
  }

  @Test
  public void exceptionOnMalformedRawNode() {
    Path buildFilePath = cell.getRootCell().getFilesystem().resolve("BUCK");

    // Missing base_path
    Map<String, Object> malformedMap = ImmutableMap.of("bar", ImmutableMap.of("name", "bar"));

    expectedException.expectMessage("malformed raw data");

    UnflavoredBuildTargetFactory.createFromRawNode(
        cell.getRootCell().getRoot().getPath(),
        cell.getRootCell().getCanonicalName(),
        malformedMap,
        buildFilePath);
  }

  @Test
  public void exceptionOnSwappedRawNode() {
    Path buildFilePath = cell.getRootCell().getFilesystem().resolve("BUCK");
    RelPath relativeBuildFilePath = cell.getRootCell().getFilesystem().relativize(buildFilePath);
    String base_path = MorePaths.getParentOrEmpty(relativeBuildFilePath).toString();

    Map<String, Object> malformedMap = ImmutableMap.of("buck.base_path", base_path, "name", "bar");

    expectedException.expectMessage(
        "Raw data claims to come from [], but we tried rooting it at [a].");

    buildFilePath = cell.getRootCell().getFilesystem().resolve("a/BUCK");
    UnflavoredBuildTargetFactory.createFromRawNode(
        cell.getRootCell().getRoot().getPath(),
        cell.getRootCell().getCanonicalName(),
        malformedMap,
        buildFilePath);
  }
}
