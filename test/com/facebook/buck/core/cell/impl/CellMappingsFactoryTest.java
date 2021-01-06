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

package com.facebook.buck.core.cell.impl;

import static com.facebook.buck.core.cell.impl.DefaultCellPathResolver.REPOSITORIES_SECTION;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CellMappingsFactoryTest {
  @Rule public TemporaryPaths paths = new TemporaryPaths();
  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(paths.getRoot());
  }

  /**
   * Just a simple helper to make these tests less verbose. Only allows configuring the
   * [repositories] section.
   */
  static class SimpleConfigBuilder {
    ImmutableMap.Builder<String, String> repositories = ImmutableMap.builder();

    SimpleConfigBuilder put(String cellName, String relativePath) {
      repositories.put(cellName, relativePath);
      return this;
    }

    BuckConfig build() {
      return FakeBuckConfig.builder()
          .setSections(ImmutableMap.of(REPOSITORIES_SECTION, repositories.build()))
          .build();
    }
  }

  private static SimpleConfigBuilder configBuilder() {
    return new SimpleConfigBuilder();
  }

  @Test
  public void testPathResolver() {
    AbsPath root = filesystem.getRootPath();
    BuckConfig rootConfig =
        configBuilder()
            .put("cell1", "repo1")
            .put("cell2", "repo2")
            .put("self", ".")
            .put("cell2_alias", "repo2")
            .build();

    NewCellPathResolver newCellPathResolver =
        CellMappingsFactory.create(root, rootConfig.getConfig());

    assertEquals(root.getPath(), newCellPathResolver.getCellPath(CanonicalCellName.rootCell()));
    assertEquals(
        root.resolve("repo1").getPath(),
        newCellPathResolver.getCellPath(CanonicalCellName.of(Optional.of("cell1"))));
    assertEquals(
        root.resolve("repo2").getPath(),
        newCellPathResolver.getCellPath(CanonicalCellName.of(Optional.of("cell2"))));
  }

  @Test
  public void testRootNameResolver() {
    AbsPath root = filesystem.getRootPath();
    BuckConfig rootConfig =
        configBuilder()
            .put("cell1", "repo1")
            .put("cell2", "repo2")
            .put("self", ".")
            .put("cell2_alias", "repo2")
            .build();

    NewCellPathResolver newCellPathResolver =
        CellMappingsFactory.create(root, rootConfig.getConfig());

    CellNameResolver rootNameResolver =
        CellMappingsFactory.createCellNameResolver(
            root, rootConfig.getConfig(), newCellPathResolver);

    assertEquals(CanonicalCellName.rootCell(), rootNameResolver.getName(Optional.empty()));
    assertEquals(
        CanonicalCellName.of(Optional.of("cell1")), rootNameResolver.getName(Optional.of("cell1")));
    assertEquals(
        CanonicalCellName.of(Optional.of("cell2")), rootNameResolver.getName(Optional.of("cell2")));
    assertEquals(CanonicalCellName.rootCell(), rootNameResolver.getName(Optional.of("self")));
    assertEquals(
        CanonicalCellName.of(Optional.of("cell2")),
        rootNameResolver.getName(Optional.of("cell2_alias")));
  }

  @Test
  public void testOtherNameResolver() {
    AbsPath root = filesystem.getRootPath();
    BuckConfig rootConfig =
        configBuilder()
            .put("cell1", "repo1")
            .put("cell2", "repo2")
            .put("self", ".")
            .put("cell2_alias", "repo2")
            .build();

    BuckConfig otherConfig =
        configBuilder()
            .put("cell2", "../repo2")
            .put("root", "..")
            .put("self", ".")
            .put("other_self", ".")
            .build();

    NewCellPathResolver newCellPathResolver =
        CellMappingsFactory.create(root, rootConfig.getConfig());

    CellNameResolver otherNameResolver =
        CellMappingsFactory.createCellNameResolver(
            root.resolve("repo1"), otherConfig.getConfig(), newCellPathResolver);

    assertEquals(
        CanonicalCellName.of(Optional.of("cell1")), otherNameResolver.getName(Optional.empty()));
    assertEquals(CanonicalCellName.rootCell(), otherNameResolver.getName(Optional.of("root")));
    assertEquals(
        CanonicalCellName.of(Optional.of("cell2")),
        otherNameResolver.getName(Optional.of("cell2")));
    assertEquals(
        CanonicalCellName.of(Optional.of("cell1")), otherNameResolver.getName(Optional.of("self")));
    assertEquals(
        CanonicalCellName.of(Optional.of("cell1")),
        otherNameResolver.getName(Optional.of("other_self")));
  }
}
