/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij.aggregation;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.google.common.base.Ascii;
import java.util.OptionalInt;

/** Indicates how to aggregate {@link TargetNode}s into {@link IjModule}s. */
public class AggregationMode {
  private static final int MIN_SHALLOW_GRAPH_SIZE = 500;
  private static final int SHALLOW_MAX_PATH_LENGTH = 3;

  public static final AggregationMode AUTO = new AggregationMode();
  public static final AggregationMode NONE = new AggregationMode(Integer.MAX_VALUE);
  public static final AggregationMode SHALLOW = new AggregationMode(SHALLOW_MAX_PATH_LENGTH);

  private OptionalInt minimumDepth;

  AggregationMode() {
    minimumDepth = OptionalInt.empty();
  }

  AggregationMode(int minimumDepth) {
    if (minimumDepth <= 0) {
      throw new HumanReadableException(
          "Aggregation level must be a positive integer (got " + minimumDepth + ")");
    }

    this.minimumDepth = OptionalInt.of(minimumDepth);
  }

  public int getGraphMinimumDepth(int graphSize) {
    return minimumDepth.orElse(
        graphSize < MIN_SHALLOW_GRAPH_SIZE ? Integer.MAX_VALUE : SHALLOW_MAX_PATH_LENGTH);
  }

  public static AggregationMode fromString(String aggregationModeString) {
    switch (Ascii.toLowerCase(aggregationModeString)) {
      case "shallow":
        return SHALLOW;
      case "none":
        return NONE;
      case "auto":
        return AUTO;
      default:
        try {
          // See if a number was passed.
          return new AggregationMode(Integer.parseInt(aggregationModeString));
        } catch (NumberFormatException e) {
          throw new HumanReadableException(
              "Invalid aggregation mode value %s.", aggregationModeString);
        }
    }
  }
}
