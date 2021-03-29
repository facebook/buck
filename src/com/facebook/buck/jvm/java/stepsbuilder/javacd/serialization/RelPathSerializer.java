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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.javacd.model.RelPathMapEntry;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link RelPath} to protobuf serializer */
public class RelPathSerializer {

  private RelPathSerializer() {}

  /** Deserializes list of {@link RelPathMapEntry} into a map of resources */
  public static ImmutableMap<RelPath, RelPath> toResourceMap(
      List<RelPathMapEntry> resourcesMapList) {
    ImmutableMap.Builder<RelPath, RelPath> builder =
        ImmutableMap.builderWithExpectedSize(resourcesMapList.size());
    for (RelPathMapEntry entry : resourcesMapList) {
      builder.put(
          RelPathSerializer.deserialize(entry.getKey()),
          RelPathSerializer.deserialize(entry.getValue()));
    }
    return builder.build();
  }

  /**
   * Deserializes map of cell to path mappings into a map of map where key is deserialized into
   * {@link CanonicalCellName}
   */
  public static ImmutableMap<CanonicalCellName, RelPath> toCellToPathMapping(
      Map<String, com.facebook.buck.javacd.model.RelPath> cellToPathMappings) {
    ImmutableMap.Builder<CanonicalCellName, RelPath> builder =
        ImmutableMap.builderWithExpectedSize(cellToPathMappings.size());
    cellToPathMappings.forEach(
        (key, value) ->
            builder.put(toCanonicalCellName(key), RelPathSerializer.deserialize(value)));
    return builder.build();
  }

  private static CanonicalCellName toCanonicalCellName(String cellName) {
    if (cellName.isEmpty()) {
      return CanonicalCellName.rootCell();
    }
    return CanonicalCellName.of(Optional.of(cellName));
  }

  /**
   * Deserializes list of {@link com.facebook.buck.javacd.model.RelPath} into a sorted set of {@link
   * RelPath}
   */
  public static ImmutableSortedSet<RelPath> toSortedSetOfRelPath(
      List<com.facebook.buck.javacd.model.RelPath> list) {
    ImmutableSortedSet.Builder<RelPath> builder =
        ImmutableSortedSet.orderedBy(RelPath.comparator());
    for (com.facebook.buck.javacd.model.RelPath item : list) {
      builder.add(RelPathSerializer.deserialize(item));
    }
    return builder.build();
  }

  /**
   * Serializes {@link RelPath} into javacd model's {@link com.facebook.buck.javacd.model.RelPath}.
   */
  public static com.facebook.buck.javacd.model.RelPath serialize(RelPath relPath) {
    String path = relPath.toString();
    return com.facebook.buck.javacd.model.RelPath.newBuilder().setPath(path).build();
  }

  /**
   * Deserializes javacd model's {@link com.facebook.buck.javacd.model.RelPath} into {@link
   * RelPath}.
   */
  public static RelPath deserialize(com.facebook.buck.javacd.model.RelPath relPath) {
    return RelPath.get(relPath.getPath());
  }
}
