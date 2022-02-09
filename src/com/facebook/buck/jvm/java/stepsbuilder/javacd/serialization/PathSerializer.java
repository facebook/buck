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

import java.nio.file.Path;
import java.nio.file.Paths;

/** {@link Path} to protobuf serializer */
public class PathSerializer {

  private PathSerializer() {}

  /** Serializes {@link Path} into javacd model's {@link com.facebook.buck.cd.model.java.Path}. */
  public static com.facebook.buck.cd.model.java.Path serialize(Path path) {
    String value = path.toString();
    return com.facebook.buck.cd.model.java.Path.newBuilder().setPath(value).build();
  }

  /** Deserializes javacd model's {@link com.facebook.buck.cd.model.java.Path} into {@link Path}. */
  public static Path deserialize(com.facebook.buck.cd.model.java.Path path) {
    return Paths.get(path.getPath());
  }
}
