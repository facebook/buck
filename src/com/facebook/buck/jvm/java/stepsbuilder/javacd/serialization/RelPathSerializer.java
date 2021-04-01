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

import com.facebook.buck.core.filesystems.RelPath;
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;

/** {@link RelPath} to protobuf serializer */
public class RelPathSerializer {

  private RelPathSerializer() {}

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
