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

import com.facebook.buck.javacd.model.OutputPathsValue;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;

/** {@link CompilerOutputPathsValue} to protobuf serializer */
public class CompilerOutputPathsValueSerializer {

  private CompilerOutputPathsValueSerializer() {}

  /** Serializes {@link CompilerOutputPathsValue} into javacd model's {@link OutputPathsValue}. */
  public static OutputPathsValue serialize(CompilerOutputPathsValue value) {
    OutputPathsValue.Builder builder = OutputPathsValue.newBuilder();
    builder.setLibraryPaths(toOutputPaths(value.getLibraryCompilerOutputPath()));
    builder.setSourceAbiPaths(toOutputPaths(value.getSourceAbiCompilerOutputPath()));
    builder.setSourceOnlyAbiPaths(toOutputPaths(value.getSourceOnlyAbiCompilerOutputPath()));
    builder.setLibraryTargetFullyQualifiedName(value.getLibraryTargetFullyQualifiedName());
    return builder.build();
  }

  private static OutputPathsValue.OutputPaths toOutputPaths(CompilerOutputPaths outputPaths) {
    return CompilerOutputPathsSerializer.serialize(outputPaths);
  }

  /** Deserializes javacd model's {@link OutputPathsValue} into {@link CompilerOutputPathsValue}. */
  public static CompilerOutputPathsValue deserialize(OutputPathsValue outputPathsValue) {
    return CompilerOutputPathsValue.of(
        outputPathsValue.getLibraryTargetFullyQualifiedName(),
        toCompilerOutputPaths(outputPathsValue.getLibraryPaths()),
        toCompilerOutputPaths(outputPathsValue.getSourceAbiPaths()),
        toCompilerOutputPaths(outputPathsValue.getSourceOnlyAbiPaths()));
  }

  private static CompilerOutputPaths toCompilerOutputPaths(
      OutputPathsValue.OutputPaths outputPaths) {
    return CompilerOutputPathsSerializer.deserialize(outputPaths);
  }
}
