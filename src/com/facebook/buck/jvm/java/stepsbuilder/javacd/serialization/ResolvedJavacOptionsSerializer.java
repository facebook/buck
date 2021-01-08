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
import com.facebook.buck.javacd.model.ResolvedJavacOptions.JavacPluginJsr199Fields;
import com.facebook.buck.jvm.java.ResolvedJavacOptions;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** {@link ResolvedJavacOptions} to protobuf serializer */
public class ResolvedJavacOptionsSerializer {

  private ResolvedJavacOptionsSerializer() {}

  /**
   * Serializes {@link ResolvedJavacOptions} into javacd model's {@link
   * com.facebook.buck.javacd.model.ResolvedJavacOptions}.
   */
  public static com.facebook.buck.javacd.model.ResolvedJavacOptions serialize(
      ResolvedJavacOptions options) {
    com.facebook.buck.javacd.model.ResolvedJavacOptions.Builder builder =
        com.facebook.buck.javacd.model.ResolvedJavacOptions.newBuilder();

    Optional<String> bootclasspath = options.getBootclasspath();
    bootclasspath.ifPresent(builder::setBootclasspath);
    builder.setDebug(options.isDebug());
    builder.setVerbose(options.isVerbose());
    builder.setJavaAnnotationProcessorParamsPresent(
        options.isJavaAnnotationProcessorParamsPresent());

    Optional<List<RelPath>> bootclasspathList = options.getBootclasspathList();
    bootclasspathList.ifPresent(
        list ->
            list.stream().map(RelPathSerializer::serialize).forEach(builder::addBootclasspathList));

    builder.setLanguageLevelOptions(
        JavacLanguageLevelOptionsSerializer.serialize(options.getLanguageLevelOptions()));

    builder.setJavaAnnotationProcessorParams(
        JavacPluginParamsSerializer.serialize(options.getJavaAnnotationProcessorParams()));
    builder.setStandardJavacPluginParams(
        JavacPluginParamsSerializer.serialize(options.getStandardJavacPluginParams()));

    for (String extraArg : options.getExtraArguments()) {
      builder.addExtraArguments(extraArg);
    }

    for (JavacPluginJsr199Fields item : options.getAnnotationProcessors()) {
      builder.addAnnotationProcessors(item);
    }
    for (JavacPluginJsr199Fields item : options.getJavaPlugins()) {
      builder.addJavaPlugins(item);
    }

    return builder.build();
  }

  /**
   * Deserializes javacd model's {@link com.facebook.buck.javacd.model.ResolvedJavacOptions} into
   * {@link ResolvedJavacOptions}.
   */
  public static ResolvedJavacOptions deserialize(
      com.facebook.buck.javacd.model.ResolvedJavacOptions options) {
    List<com.facebook.buck.javacd.model.RelPath> bootclasspathListList =
        options.getBootclasspathListList();
    Optional<List<RelPath>> bootclasspathList =
        bootclasspathListList.isEmpty()
            ? Optional.empty()
            : Optional.of(
                bootclasspathListList.stream()
                    .map(RelPathSerializer::deserialize)
                    .collect(Collectors.toList()));

    return ResolvedJavacOptions.of(
        toOptionalString(options.getBootclasspath()),
        bootclasspathList,
        JavacLanguageLevelOptionsSerializer.deserialize(options.getLanguageLevelOptions()),
        options.getDebug(),
        options.getVerbose(),
        JavacPluginParamsSerializer.deserialize(options.getJavaAnnotationProcessorParams()),
        JavacPluginParamsSerializer.deserialize(options.getStandardJavacPluginParams()),
        options.getExtraArgumentsList(),
        options.getAnnotationProcessorsList(),
        options.getJavaPluginsList(),
        options.getJavaAnnotationProcessorParamsPresent());
  }

  private static Optional<String> toOptionalString(String value) {
    return value.isEmpty() ? Optional.empty() : Optional.of(value);
  }
}
