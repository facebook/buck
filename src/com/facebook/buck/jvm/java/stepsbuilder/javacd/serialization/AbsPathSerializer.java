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

import com.facebook.buck.core.filesystems.AbsPath;

/** {@link AbsPath} to protobuf serializer */
public class AbsPathSerializer {

  private AbsPathSerializer() {}

  /**
   * Serializes {@link AbsPath} into javacd model's {@link com.facebook.buck.javacd.model.AbsPath}.
   */
  public static com.facebook.buck.javacd.model.AbsPath serialize(AbsPath absPath) {
    String path = absPath.toString();
    return com.facebook.buck.javacd.model.AbsPath.newBuilder().setPath(path).build();
  }

  /**
   * Deserializes javacd model's {@link com.facebook.buck.javacd.model.AbsPath} into {@link
   * AbsPath}.
   */
  public static AbsPath deserialize(com.facebook.buck.javacd.model.AbsPath absPath) {
    return AbsPath.get(absPath.getPath());
  }
}
