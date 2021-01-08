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
import com.facebook.buck.javacd.model.OutputPathsValue;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import java.util.Optional;

/** {@link CompilerOutputPaths} to protobuf serializer */
class CompilerOutputPathsSerializer {

  private CompilerOutputPathsSerializer() {}

  /**
   * Serializes {@link CompilerOutputPaths} into javacd model's {@link
   * OutputPathsValue.OutputPaths}.
   */
  public static OutputPathsValue.OutputPaths serialize(CompilerOutputPaths compilerOutputPaths) {
    OutputPathsValue.OutputPaths.Builder builder = OutputPathsValue.OutputPaths.newBuilder();
    builder.setClassesDir(toRelPath(compilerOutputPaths.getClassesDir()));
    builder.setOutputJarDirPath(toRelPath(compilerOutputPaths.getOutputJarDirPath()));
    compilerOutputPaths
        .getAbiJarPath()
        .map(RelPathSerializer::serialize)
        .ifPresent(builder::setAbiJarPath);
    builder.setAnnotationPath(toRelPath(compilerOutputPaths.getAnnotationPath()));
    builder.setPathToSourcesList(toRelPath(compilerOutputPaths.getPathToSourcesList()));
    builder.setWorkingDirectory(toRelPath(compilerOutputPaths.getWorkingDirectory()));
    compilerOutputPaths
        .getOutputJarPath()
        .map(RelPathSerializer::serialize)
        .ifPresent(builder::setOutputJarPath);
    return builder.build();
  }

  private static com.facebook.buck.javacd.model.RelPath toRelPath(RelPath relPath) {
    return RelPathSerializer.serialize(relPath);
  }

  /**
   * Deserializes javacd model's {@link OutputPathsValue.OutputPaths} into {@link
   * CompilerOutputPaths}.
   */
  public static CompilerOutputPaths deserialize(OutputPathsValue.OutputPaths outputPaths) {
    return CompilerOutputPaths.builder()
        .setClassesDir(toRelPath(outputPaths.getClassesDir()))
        .setOutputJarDirPath(toRelPath(outputPaths.getOutputJarDirPath()))
        .setAbiJarPath(toOptionalRelPath(outputPaths.hasAbiJarPath(), outputPaths.getAbiJarPath()))
        .setAnnotationPath(toRelPath(outputPaths.getAnnotationPath()))
        .setPathToSourcesList(toRelPath(outputPaths.getPathToSourcesList()))
        .setWorkingDirectory(toRelPath(outputPaths.getWorkingDirectory()))
        .setOutputJarPath(
            toOptionalRelPath(outputPaths.hasOutputJarPath(), outputPaths.getOutputJarPath()))
        .build();
  }

  private static RelPath toRelPath(com.facebook.buck.javacd.model.RelPath relPath) {
    return RelPathSerializer.deserialize(relPath);
  }

  private static Optional<RelPath> toOptionalRelPath(
      boolean isPresent, com.facebook.buck.javacd.model.RelPath value) {
    return isPresent ? Optional.of(toRelPath(value)) : Optional.empty();
  }
}
