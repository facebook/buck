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
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.ResolvedJavacPluginProperties;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** {@link JavacPluginParams} to protobuf serializer */
class JavacPluginParamsSerializer {

  private JavacPluginParamsSerializer() {}

  /**
   * Serializes {@link JavacPluginParams} into javacd model's {@link
   * ResolvedJavacOptions.JavacPluginParams }.
   */
  public static ResolvedJavacOptions.JavacPluginParams serialize(
      JavacPluginParams javacPluginParams) {
    ResolvedJavacOptions.JavacPluginParams.Builder builder =
        ResolvedJavacOptions.JavacPluginParams.newBuilder();
    builder.setProcessOnly(javacPluginParams.getProcessOnly());

    for (String param : javacPluginParams.getParameters()) {
      builder.addParameters(param);
    }
    for (ResolvedJavacPluginProperties pluginProperties : javacPluginParams.getPluginProperties()) {
      builder.addPluginProperties(
          ResolvedJavacPluginPropertiesSerializer.serialize(pluginProperties));
    }

    return builder.build();
  }

  /**
   * Deserializes javacd model's {@link ResolvedJavacOptions.JavacPluginParams } into {@link
   * JavacPluginParams}.
   */
  public static JavacPluginParams deserialize(
      ResolvedJavacOptions.JavacPluginParams javacPluginParams) {
    JavacPluginParams.Builder builder = JavacPluginParams.builder();
    builder.setProcessOnly(javacPluginParams.getProcessOnly());
    builder.setParameters(javacPluginParams.getParametersList());
    builder.setPluginProperties(toPluginProperties(javacPluginParams.getPluginPropertiesList()));
    return builder.build();
  }

  private static Iterable<ResolvedJavacPluginProperties> toPluginProperties(
      List<ResolvedJavacOptions.ResolvedJavacPluginProperties> pluginPropertiesList) {
    ImmutableList.Builder<ResolvedJavacPluginProperties> builder =
        ImmutableList.builderWithExpectedSize(pluginPropertiesList.size());
    for (ResolvedJavacOptions.ResolvedJavacPluginProperties item : pluginPropertiesList) {
      builder.add(ResolvedJavacPluginPropertiesSerializer.deserialize(item));
    }
    return builder.build();
  }
}
