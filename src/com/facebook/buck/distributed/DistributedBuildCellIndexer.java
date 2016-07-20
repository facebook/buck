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
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps track of {@link Cell}s encountered while serializing the distributed build state.
 */
public class DistributedBuildCellIndexer implements Function<Path, Integer> {

  public static final Integer ROOT_CELL_INDEX = 0;

  final Cell rootCell;
  final Map<Path, Integer> index;
  final Map<Integer, BuildJobStateCell> state;

  public DistributedBuildCellIndexer(Cell rootCell) {
    this.rootCell = rootCell;
    this.index = new HashMap<>();
    this.state = new HashMap<>();
    // Make sure root cell is at index 0.
    Preconditions.checkState(ROOT_CELL_INDEX == this.apply(rootCell.getRoot()));
  }

  public Map<Integer, BuildJobStateCell> getState() {
    return state;
  }

  @Override
  public Integer apply(Path input) {
    Integer i = index.get(input);
    if (i == null) {
      i = index.size();
      index.put(input, i);

      Cell cell = rootCell.getCellIgnoringVisibilityCheck(input);
      state.put(i, dumpCell(cell));
    }
    return i;
  }

  private static BuildJobStateCell dumpCell(Cell cell) {
    BuildJobStateCell cellState = new BuildJobStateCell();
    cellState.setConfig(dumpConfig(cell.getBuckConfig()));
    cellState.setNameHint(cell.getRoot().getFileName().toString());
    return cellState;
  }

  private static BuildJobStateBuckConfig dumpConfig(BuckConfig buckConfig) {
    BuildJobStateBuckConfig jobState = new BuildJobStateBuckConfig();

    jobState.setUserEnvironment(buckConfig.getEnvironment());
    Map<String, List<OrderedStringMapEntry>> rawConfig = Maps.transformValues(
        buckConfig.getRawConfigForDistBuild(),
        new Function<ImmutableMap<String, String>, List<OrderedStringMapEntry>>() {
          @Override
          public List<OrderedStringMapEntry> apply(ImmutableMap<String, String> input) {
            List<OrderedStringMapEntry> result = new ArrayList<>();
            for (Map.Entry<String, String> entry : input.entrySet()) {
              result.add(new OrderedStringMapEntry(entry.getKey(), entry.getValue()));
            }
            return result;
          }
        });
    jobState.setRawBuckConfig(rawConfig);
    jobState.setArchitecture(buckConfig.getArchitecture().name());
    jobState.setPlatform(buckConfig.getPlatform().name());

    return jobState;
  }
}
