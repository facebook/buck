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
import static org.junit.Assert.assertFalse;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.integration.TemporaryPaths;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class DaemonicCellStateTest {

  @Rule
  public TemporaryPaths tempDir = new TemporaryPaths();

  private ProjectFilesystem filesystem;
  private Cell cell;
  private DaemonicCellState state;

  @Before
  public void setUp() throws IOException, InterruptedException {
    Path root = tempDir.getRoot().toRealPath();
    filesystem = new ProjectFilesystem(root);
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .build();
    cell = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(config)
        .build();
    state = new DaemonicCellState(cell, 1);
  }

  @Test
  public void testPutComputedNodeIfNotPresent()
      throws BuildTargetException, IOException, InterruptedException {
    Cache<BuildTarget, Boolean> cache = state.getOrCreateCache(Boolean.class);
    BuildTarget target = BuildTargetFactory.newInstance(filesystem, "//path/to:target");

    cache.putComputedNodeIfNotPresent(cell, target, false);
    assertEquals(
        "Cached node was not found",
        Optional.of(false),
        cache.lookupComputedNode(cell, target));

    assertFalse(cache.putComputedNodeIfNotPresent(cell, target, true));
    assertEquals(
        "Previously cached node should not be updated",
        Optional.of(false),
        cache.lookupComputedNode(cell, target));
  }

}
