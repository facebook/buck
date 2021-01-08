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

import com.facebook.buck.javacd.model.ResolvedJavacOptions;
import com.facebook.buck.jvm.java.JavacLanguageLevelOptions;

/** {@link JavacLanguageLevelOptions} to protobuf serializer */
class JavacLanguageLevelOptionsSerializer {

  private JavacLanguageLevelOptionsSerializer() {}

  /**
   * Serializes {@link JavacLanguageLevelOptions} into javacd model's {@link
   * ResolvedJavacOptions.JavacLanguageLevelOptions}.
   */
  public static ResolvedJavacOptions.JavacLanguageLevelOptions serialize(
      JavacLanguageLevelOptions javacLanguageLevelOptions) {
    return ResolvedJavacOptions.JavacLanguageLevelOptions.newBuilder()
        .setSourceLevel(javacLanguageLevelOptions.getSourceLevel())
        .setTargetLevel(javacLanguageLevelOptions.getTargetLevel())
        .build();
  }

  /**
   * Deserializes javacd model's {@link ResolvedJavacOptions.JavacLanguageLevelOptions} into {@link
   * JavacLanguageLevelOptions}.
   */
  public static JavacLanguageLevelOptions deserialize(
      ResolvedJavacOptions.JavacLanguageLevelOptions javacLanguageLevelOptions) {
    return JavacLanguageLevelOptions.builder()
        .setSourceLevel(javacLanguageLevelOptions.getSourceLevel())
        .setTargetLevel(javacLanguageLevelOptions.getTargetLevel())
        .build();
  }
}
