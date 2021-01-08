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
import com.facebook.buck.javacd.model.ResolvedJavacOptions;
import com.facebook.buck.jvm.java.ResolvedJavacPluginProperties;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;

/** {@link ResolvedJavacPluginProperties} to protobuf serializer */
class ResolvedJavacPluginPropertiesSerializer {

  private ResolvedJavacPluginPropertiesSerializer() {}

  /**
   * Serializes {@link ResolvedJavacPluginProperties} into javacd model's {@link
   * ResolvedJavacOptions.ResolvedJavacPluginProperties}.
   */
  public static ResolvedJavacOptions.ResolvedJavacPluginProperties serialize(
      ResolvedJavacPluginProperties pluginProperties) {
    ResolvedJavacOptions.ResolvedJavacPluginProperties.Builder builder =
        ResolvedJavacOptions.ResolvedJavacPluginProperties.newBuilder();

    builder.setCanReuseClassLoader(pluginProperties.getCanReuseClassLoader());
    builder.setDoesNotAffectAbi(pluginProperties.getDoesNotAffectAbi());
    builder.setSupportsAbiGenerationFromSource(
        pluginProperties.getSupportAbiGenerationFromSource());
    for (String processorName : pluginProperties.getProcessorNames()) {
      builder.addProcessorNames(processorName);
    }
    for (RelPath classpath : pluginProperties.getClasspath()) {
      builder.addClasspath(RelPathSerializer.serialize(classpath));
    }

    pluginProperties
        .getPathParams()
        .forEach((key, value) -> builder.putPathParams(key, RelPathSerializer.serialize(value)));

    return builder.build();
  }

  /**
   * Deserializes javacd model's {@link ResolvedJavacOptions.ResolvedJavacPluginProperties} into
   * {@link ResolvedJavacPluginProperties}.
   */
  public static ResolvedJavacPluginProperties deserialize(
      ResolvedJavacOptions.ResolvedJavacPluginProperties pluginProperties) {
    return new ResolvedJavacPluginProperties(
        null,
        pluginProperties.getCanReuseClassLoader(),
        pluginProperties.getDoesNotAffectAbi(),
        pluginProperties.getSupportsAbiGenerationFromSource(),
        ImmutableSortedSet.copyOf(pluginProperties.getProcessorNamesList()),
        pluginProperties.getClasspathList().stream()
            .map(RelPathSerializer::deserialize)
            .collect(ImmutableList.toImmutableList()),
        toPathParams(pluginProperties.getPathParamsMap()));
  }

  private static ImmutableMap<String, RelPath> toPathParams(
      Map<String, com.facebook.buck.javacd.model.RelPath> pathParamsMap) {
    ImmutableMap.Builder<String, RelPath> pathParamsBuilder =
        ImmutableMap.builderWithExpectedSize(pathParamsMap.size());
    for (Map.Entry<String, com.facebook.buck.javacd.model.RelPath> entry :
        pathParamsMap.entrySet()) {
      pathParamsBuilder.put(entry.getKey(), RelPathSerializer.deserialize(entry.getValue()));
    }
    return pathParamsBuilder.build();
  }
}
