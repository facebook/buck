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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobStateBuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobStateCell;
import com.facebook.buck.distributed.thrift.OrderedStringMapEntry;
import com.facebook.buck.rules.Cell;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps track of {@link Cell}s encountered while serializing the distributed build state. */
public class DistBuildCellIndexer {

  public static final Integer ROOT_CELL_INDEX = 0;

  final Cell rootCell;
  final Map<Path, Integer> index;
  final Map<Integer, BuildJobStateCell> state;

  public DistBuildCellIndexer(Cell rootCell) {
    this.rootCell = rootCell;
    this.index = new HashMap<>();
    this.state = new HashMap<>();
    // Make sure root cell is at index 0.
    Preconditions.checkState(ROOT_CELL_INDEX == this.getCellIndex(rootCell.getRoot()));
  }

  public Map<Integer, BuildJobStateCell> getState() {
    return state;
  }

  public Integer getCellIndex(Path input) {
    // Non-cell Paths are just stored in the root cell data marked as absolute paths.
    Integer i = rootCell.getKnownRoots().contains(input) ? index.get(input) : ROOT_CELL_INDEX;
    if (i == null) {
      i = index.size();
      index.put(input, i);

      Cell cell = rootCell.getCellIgnoringVisibilityCheck(input);
      state.put(i, dumpCell(cell));
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

    jobState.setUserEnvironment(buckConfig.getEnvironment());
    Map<String, List<OrderedStringMapEntry>> rawConfig =
        Maps.transformValues(
            buckConfig.getRawConfigForDistBuild(),
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
    jobState.setCellAliasToIndex(
        Maps.transformValues(buckConfig.getCellPathResolver().getCellPaths(), this::getCellIndex));

    return jobState;
  }
}
