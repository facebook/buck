/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android.resources;

import java.nio.IntBuffer;
import java.util.Map;
import java.util.SortedSet;
import java.util.stream.IntStream;

/**
 * BringToFrontMapper supports "bring-to-front" reassignment of reference ids. Given a set of ids,
 * it will construct an assignment that ensures that those ids are the first ones for each type.
 */
public class BringToFrontMapper implements ReferenceMapper {
  private final int packageId;
  private final int[][] mapping;
  private final int[][] rewriters;

  public BringToFrontMapper(int packageId, int[][] mapping, int[][] rewriters) {
    this.packageId = packageId;
    this.mapping = mapping;
    this.rewriters = rewriters;
  }

  /**
   * Constructs a ReferenceMapper that will reassign ids and adjust offsets such that for each key
   * in idsByType, the ids in idsByType will be reassigned to the first n ids of that type. The ids
   * provided should not include package or type ids.
   */
  public static BringToFrontMapper construct(
      int packageId, Map<Integer, SortedSet<Integer>> idsByType) {
    int maxType = 0;
    for (int k : idsByType.keySet()) {
      maxType = Math.max(k, maxType);
    }
    int[][] mapping = new int[maxType + 1][];
    int[][] rewriters = new int[maxType + 1][];

    idsByType.forEach(
        (type, idsSet) -> {
          int maxRef = 0;
          int[] ids = idsSet.stream().mapToInt(i -> i).toArray();
          for (int id : ids) {
            maxRef = Math.max(maxRef, id);
          }
          int[] newMapping = IntStream.range(0, maxRef + 1).toArray();
          // rewrite() does swaps iterating forward through ids. Doing those swaps in reverse will
          // give us a mapping of the new ids.
          for (int i = ids.length - 1; i >= 0; i--) {
            // Since ids is sorted, it's guaranteed that newMapping[i] == i;
            int id = ids[i];
            newMapping[i] = newMapping[id];
            newMapping[id] = i;
          }
          mapping[type] = newMapping;
          rewriters[type] = ids;
        });
    return new BringToFrontMapper(packageId, mapping, rewriters);
  }

  @Override
  public int map(int id) {
    if ((id >> 24) != packageId) {
      return id;
    }
    int type = (id >> 16) & 0xFF;
    if (type < mapping.length) {
      int[] typeMapping = mapping[type];
      if (typeMapping == null) {
        return id;
      }
      int entry = id & 0xFFFF;
      if (entry < typeMapping.length) {
        return (id & 0xFFFF0000) | typeMapping[entry];
      }
    }
    return id;
  }

  @Override
  public void rewrite(int type, IntBuffer buf) {
    if (type < rewriters.length) {
      int[] rewriter = rewriters[type];
      if (rewriter != null) {
        for (int i = 0; i < rewriter.length; i++) {
          int id = rewriter[i];
          // Swap the values at the old and new positions.
          int temp = buf.get(id);
          buf.put(id, buf.get(i));
          buf.put(i, temp);
        }
      }
    }
  }
}
