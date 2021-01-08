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

import java.nio.file.Path;
import java.nio.file.Paths;

/** {@link Path} to protobuf serializer */
public class PathSerializer {

  private PathSerializer() {}

  /** Serializes {@link Path} into javacd model's {@link com.facebook.buck.javacd.model.Path}. */
  public static com.facebook.buck.javacd.model.Path serialize(Path path) {
    String value = path.toString();
    return com.facebook.buck.javacd.model.Path.newBuilder().setPath(value).build();
  }

  /** Deserializes javacd model's {@link com.facebook.buck.javacd.model.Path} into {@link Path}. */
  public static Path deserialize(com.facebook.buck.javacd.model.Path path) {
    return Paths.get(path.getPath());
  }
}
