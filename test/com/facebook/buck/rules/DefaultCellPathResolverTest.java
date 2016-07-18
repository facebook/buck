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

package com.facebook.buck.rules;

import static org.junit.Assert.assertThat;

import com.facebook.buck.config.ConfigBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

public class DefaultCellPathResolverTest {
  private static final String REPOSITORIES_SECTION =
      "[" + DefaultCellPathResolver.REPOSITORIES_SECTION + "]";

  @Test
  public void knownRulesForSimpleSetup() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root);

    DefaultCellPathResolver cellPathResolver = new DefaultCellPathResolver(
        cell1Root,
        ConfigBuilder.createFromText(
            REPOSITORIES_SECTION,
            " simple = " + cell2Root.toString()));

    assertThat(
        cellPathResolver.getKnownRoots(),
        Matchers.containsInAnyOrder(cell1Root, cell2Root));
  }

  @Test
  public void transtiveMappingForSimpleSetup() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root);

    DefaultCellPathResolver cellPathResolver = new DefaultCellPathResolver(
        cell1Root,
        ConfigBuilder.createFromText(
            REPOSITORIES_SECTION,
            " simple = " + cell2Root.toString()));

    assertThat(
        cellPathResolver.getTransitivePathMapping(),
        Matchers.equalTo(
            ImmutableMap.of(
                RelativeCellName.ROOT_CELL_NAME, cell1Root,
                RelativeCellName.of(ImmutableList.of("simple")), cell2Root
            )
        ));
  }

  @Test
  public void transitiveMappingForCycle() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root);
    Path cell3Root = root.resolve("repo3");
    Files.createDirectories(cell3Root);

    DefaultCellPathResolver cellPathResolver = new DefaultCellPathResolver(
        cell1Root,
        ConfigBuilder.createFromText(
            REPOSITORIES_SECTION,
            " simple = " + cell2Root.toString()));

    Files.write(
        cell2Root.resolve(".buckconfig"),
        ImmutableList.of(
            REPOSITORIES_SECTION,
            " three = " + cell3Root.toString()),
        StandardCharsets.UTF_8);

    Files.write(
        cell3Root.resolve(".buckconfig"),
        ImmutableList.of(
            REPOSITORIES_SECTION,
            " cycle = " + cell1Root.toString()),
        StandardCharsets.UTF_8);

    assertThat(
        cellPathResolver.getTransitivePathMapping(),
        Matchers.equalTo(
            ImmutableMap.of(
                RelativeCellName.ROOT_CELL_NAME, cell1Root,
                RelativeCellName.of(ImmutableList.of("simple")), cell2Root,
                RelativeCellName.of(ImmutableList.of("simple", "three")), cell3Root
            )
        ));
  }

  @Test
  public void transitiveMappingForDiamond() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cellLeftRoot = root.resolve("left");
    Files.createDirectories(cellLeftRoot);
    Path cellRightRoot = root.resolve("right");
    Files.createDirectories(cellRightRoot);
    Path cellCenterRoot = root.resolve("center");
    Files.createDirectories(cellCenterRoot);

    DefaultCellPathResolver cellPathResolver = new DefaultCellPathResolver(
        cell1Root,
        ConfigBuilder.createFromText(
            REPOSITORIES_SECTION,
            " left = " + cellLeftRoot.toString(),
            " right = " + cellRightRoot.toString()));

    Files.write(
        cellLeftRoot.resolve(".buckconfig"),
        ImmutableList.of(
            REPOSITORIES_SECTION,
            " center = " + cellCenterRoot.toString()),
        StandardCharsets.UTF_8);

    Files.write(
        cellRightRoot.resolve(".buckconfig"),
        ImmutableList.of(
            REPOSITORIES_SECTION,
            " center = " + cellCenterRoot.toString()),
        StandardCharsets.UTF_8);

    assertThat(
        cellPathResolver.getTransitivePathMapping(),
        Matchers.equalTo(
            ImmutableMap.<RelativeCellName, Path>builder()
                .put(RelativeCellName.ROOT_CELL_NAME, cell1Root)
                .put(RelativeCellName.of(ImmutableList.of("left")), cellLeftRoot)
                .put(RelativeCellName.of(ImmutableList.of("left", "center")), cellCenterRoot)
                .put(RelativeCellName.of(ImmutableList.of("right", "center")), cellCenterRoot)
                .put(RelativeCellName.of(ImmutableList.of("right")), cellRightRoot)
            .build())
        );
  }
}
