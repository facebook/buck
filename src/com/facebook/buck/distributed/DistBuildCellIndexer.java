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

package com.facebook.buck.distributed;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobStateBuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobStateCell;
import com.facebook.buck.distributed.thrift.OrderedStringMapEntry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.config.Config;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps track of {@link Cell}s encountered while serializing the distributed build state. This
 * class is thread safe.
 */
public class DistBuildCellIndexer {

  public static final Integer ROOT_CELL_INDEX = 0;

  final Cell rootCell;
  final Map<Path, Integer> index;
  final Map<Integer, BuildJobStateCell> state;
  final Map<Integer, ProjectFilesystem> localFilesystemsByCellIndex;

  public DistBuildCellIndexer(Cell rootCell) {
    this.rootCell = withCanonicalNameIfExists(rootCell);
    this.index = new HashMap<>();
    this.state = new HashMap<>();
    this.localFilesystemsByCellIndex = new HashMap<>();
    // Make sure root cell is at index 0.
    Preconditions.checkState(ROOT_CELL_INDEX == this.getCellIndex(rootCell.getRoot()));
  }

  private Cell withCanonicalNameIfExists(Cell rootCell) {
    if (rootCell.getCanonicalName().isPresent()) {
      return rootCell;
    }

    // CellPathResolver.getCanonicalName(..) exists however that tries to hide the fact that the
    // main cell has a canonical name, by return always empty string so in order to get the actual
    // canonical name we need to look at the buckconfig. By using
    // DefaultCellPathResolver.getCanonicalNames() we avoid duplicating the parsing code.
    DefaultCellPathResolver resolver =
        DefaultCellPathResolver.of(rootCell.getRoot(), rootCell.getBuckConfig().getConfig());
    if (resolver.getCanonicalNames().containsKey(rootCell.getRoot())) {
      return rootCell.withCanonicalName(resolver.getCanonicalNames().get(rootCell.getRoot()));
    }

    return rootCell;
  }

  /** @return ProjectFilesystems indexed by cell */
  public synchronized Map<Integer, ProjectFilesystem> getLocalFilesystemsByCellIndex() {
    return localFilesystemsByCellIndex;
  }

  /** @return Cells by index */
  public synchronized Map<Integer, BuildJobStateCell> getState() {
    return state;
  }

  /** @return Cell index for given path */
  public synchronized Integer getCellIndex(Path input) {
    // Non-cell Paths are just stored in the root cell data marked as absolute paths.
    Integer i = rootCell.getKnownRoots().contains(input) ? index.get(input) : ROOT_CELL_INDEX;
    if (i == null) {
      i = index.size();
      index.put(input, i);

      Cell cell = withCanonicalNameIfExists(rootCell.getCellIgnoringVisibilityCheck(input));
      state.put(i, dumpCell(cell));
      localFilesystemsByCellIndex.put(i, cell.getFilesystem());
    }
    return i;
  }

  private BuildJobStateCell dumpCell(Cell cell) {
    BuildJobStateCell cellState = new BuildJobStateCell();
    cellState.setConfig(dumpConfig(cell.getBuckConfig()));
    cellState.setNameHint(cell.getRoot().getFileName().toString());
    cellState.setCanonicalName(cell.getCanonicalName().orElse(""));
    return cellState;
  }

  private BuildJobStateBuckConfig dumpConfig(BuckConfig buckConfig) {
    BuildJobStateBuckConfig jobState = new BuildJobStateBuckConfig();
    Config serverSideConfigWithOptionalOverride =
        new DistBuildConfig(buckConfig).getRemoteConfigWithOverride();

    jobState.setUserEnvironment(buckConfig.getEnvironment());
    Map<String, List<OrderedStringMapEntry>> rawConfig =
        Maps.transformValues(
            serverSideConfigWithOptionalOverride.getRawConfigForDistBuild(),
            input -> {
              List<OrderedStringMapEntry> result = new ArrayList<>();
              for (Map.Entry<String, String> entry : input.entrySet()) {
                result.add(new OrderedStringMapEntry(entry.getKey(), entry.getValue()));
              }
              return result;
            });
    jobState.setRawBuckConfig(rawConfig);
    jobState.setArchitecture(buckConfig.getArchitecture().name());
    jobState.setPlatform(buckConfig.getPlatform().name());

    return jobState;
  }
}
