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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.AbstractRuleType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableRawTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.parser.DaemonicCellState.Cache;
import com.facebook.buck.parser.api.BuildFileManifestFactory;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class DaemonicCellStateTest {

  private ProjectFilesystem filesystem;
  private Cell rootCell;
  private Cell childCell;
  private DaemonicCellState state;
  private DaemonicCellState childState;

  private void populateDummyRawNode(DaemonicCellState state, BuildTarget target) {
    state.putBuildFileManifestIfNotPresent(
        target.getCellPath().resolve(target.getBasePath().resolve("BUCK")),
        BuildFileManifestFactory.create(
            ImmutableMap.of(
                target.getShortName(),
                ImmutableMap.of(
                    "name", target.getShortName(),
                    "buck.base_path", PathFormatter.pathWithUnixSeparators(target.getBasePath())))),
        ImmutableSet.of(),
        ImmutableMap.of());
  }

  @Before
  public void setUp() throws IOException {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Files.createDirectories(filesystem.resolve("../xplat"));
    Files.createFile(filesystem.resolve("../xplat/.buckconfig"));
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("repositories", ImmutableMap.of("xplat", "../xplat")))
            .build();
    rootCell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    childCell = rootCell.getCell(filesystem.resolve("../xplat").toAbsolutePath());
    state = new DaemonicCellState(rootCell, 1);
    childState = new DaemonicCellState(childCell, 1);
  }

  private RawTargetNode rawTargetNode(String name) {
    return ImmutableRawTargetNode.of(
        UnconfiguredBuildTargetParser.parse("//" + name + ":" + name),
        RuleType.of("j_l", AbstractRuleType.Kind.BUILD),
        ImmutableMap.of(),
        ImmutableSet.of(),
        ImmutableSet.of());
  }

  @Test
  public void testPutComputedNodeIfNotPresent() throws BuildTargetException {
    Cache<UnconfiguredBuildTargetView, RawTargetNode> cache =
        state.getCache(DaemonicCellState.RAW_TARGET_NODE_CACHE_TYPE);
    BuildTarget target =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//path/to:target");

    // Make sure the cache has a raw node for this target.
    populateDummyRawNode(state, target);

    RawTargetNode n1 = rawTargetNode("n1");
    RawTargetNode n2 = rawTargetNode("n2");

    cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTargetView(), n1);
    assertEquals(
        "Cached node was not found",
        Optional.of(n1),
        cache.lookupComputedNode(target.getUnconfiguredBuildTargetView()));

    assertEquals(
        n1, cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTargetView(), n2));
    assertEquals(
        "Previously cached node should not be updated",
        Optional.of(n1),
        cache.lookupComputedNode(target.getUnconfiguredBuildTargetView()));
  }

  @Test
  public void testCellNameDoesNotAffectInvalidation() throws BuildTargetException {
    Cache<UnconfiguredBuildTargetView, RawTargetNode> cache =
        childState.getCache(DaemonicCellState.RAW_TARGET_NODE_CACHE_TYPE);

    Path targetPath = childCell.getRoot().resolve("path/to/BUCK");
    BuildTarget target =
        BuildTargetFactory.newInstance(
            childCell.getFilesystem().getRootPath(), "xplat//path/to:target");

    // Make sure the cache has a raw node for this target.
    populateDummyRawNode(childState, target);

    RawTargetNode n1 = rawTargetNode("n1");

    cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTargetView(), n1);
    assertEquals(
        Optional.of(n1), cache.lookupComputedNode(target.getUnconfiguredBuildTargetView()));

    childState.putBuildFileManifestIfNotPresent(
        targetPath,
        BuildFileManifestFactory.create(
            ImmutableMap.of(
                "target",
                // Forms the target "//path/to:target"
                ImmutableMap.of(
                    "buck.base_path", "path/to",
                    "name", "target"))),
        ImmutableSet.of(),
        ImmutableMap.of());
    assertEquals("Still only one invalidated node", 1, childState.invalidatePath(targetPath));
    assertEquals(
        "Cell-named target should still be invalidated",
        Optional.empty(),
        cache.lookupComputedNode(target.getUnconfiguredBuildTargetView()));
  }
}
