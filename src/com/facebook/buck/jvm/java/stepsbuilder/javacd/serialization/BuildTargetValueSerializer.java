/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.jvm.core.BuildTargetValue;

/** {@link BuildTargetValue} to protobuf serializer */
public class BuildTargetValueSerializer {

  private BuildTargetValueSerializer() {}

  /**
   * Serializes {@link BuildTargetValue} into javacd model's {@link
   * com.facebook.buck.cd.model.java.BuildTargetValue}.
   */
  public static com.facebook.buck.cd.model.java.BuildTargetValue serialize(
      BuildTargetValue buildTargetValue) {
    com.facebook.buck.cd.model.java.BuildTargetValue.Builder builder =
        com.facebook.buck.cd.model.java.BuildTargetValue.newBuilder();
    builder.setFullyQualifiedName(buildTargetValue.getFullyQualifiedName());
    builder.setType(buildTargetValue.getType());
    return builder.build();
  }

  /**
   * Deserializes javacd model's {@link com.facebook.buck.cd.model.java.BuildTargetValue} into
   * {@link BuildTargetValue}.
   */
  public static BuildTargetValue deserialize(
      com.facebook.buck.cd.model.java.BuildTargetValue buildTargetValue) {
    return BuildTargetValue.of(
        buildTargetValue.getType(), buildTargetValue.getFullyQualifiedName());
  }
}
