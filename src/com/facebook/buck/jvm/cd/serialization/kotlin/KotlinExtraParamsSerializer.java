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

package com.facebook.buck.jvm.cd.serialization.kotlin;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.jvm.cd.serialization.AbsPathSerializer;
import com.facebook.buck.jvm.cd.serialization.java.ResolvedJavacOptionsSerializer;
import com.facebook.buck.jvm.kotlin.KotlinExtraParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Marshalling between:
 *
 * <ul>
 *   <li>{@link com.facebook.buck.jvm.kotlin.KotlinExtraParams} (the parameters needed to create the
 *       steps for compiling Kotlin libraries), and
 *   <li>{@link com.facebook.buck.cd.model.kotlin.KotlinExtraParams} (part of the protocol buffer
 *       model).
 * </ul>
 */
public class KotlinExtraParamsSerializer {

  private KotlinExtraParamsSerializer() {}

  /** Protocol buffer model to internal buck representation. */
  public static KotlinExtraParams deserialize(
      com.facebook.buck.cd.model.java.ResolvedJavacOptions resolvedJavacOptions,
      com.facebook.buck.cd.model.kotlin.KotlinExtraParams kotlinExtraParams) {
    return KotlinExtraParams.of(
        kotlinExtraParams.hasPathToKotlinc()
            ? Optional.of(AbsPathSerializer.deserialize(kotlinExtraParams.getPathToKotlinc()))
            : Optional.empty(),
        kotlinExtraParams.getExtraClassPathsList().stream()
            .map(AbsPathSerializer::deserialize)
            .collect(ImmutableList.toImmutableList()),
        AbsPathSerializer.deserialize(kotlinExtraParams.getStandardLibraryClassPath()),
        AbsPathSerializer.deserialize(kotlinExtraParams.getAnnotationProcessingClassPath()),
        AnnotationProcessingToolSerializer.deserialize(
            kotlinExtraParams.getAnnotationProcessingTool()),
        kotlinExtraParams.getExtraKotlincArgumentsList().stream()
            .collect(ImmutableList.toImmutableList()),
        kotlinExtraParams.getKotlinCompilerPluginsMap().entrySet().stream()
            .collect(
                Collectors.toMap(
                    e -> AbsPath.get(e.getKey()),
                    e -> ImmutableMap.copyOf(e.getValue().getParamsMap()))),
        kotlinExtraParams.getKosabiPluginOptionsMap().entrySet().stream()
            .collect(
                Collectors.toMap(
                    e -> e.getKey(), e -> AbsPathSerializer.deserialize(e.getValue()))),
        kotlinExtraParams.getFriendPathsList().stream()
            .map(AbsPathSerializer::deserialize)
            .collect(ImmutableSortedSet.toImmutableSortedSet(AbsPath.comparator())),
        kotlinExtraParams.getKotlinHomeLibrariesList().stream()
            .map(AbsPathSerializer::deserialize)
            .collect(ImmutableSortedSet.toImmutableSortedSet(AbsPath.comparator())),
        ResolvedJavacOptionsSerializer.deserialize(resolvedJavacOptions),
        kotlinExtraParams.getJvmTarget().isEmpty()
            ? Optional.empty()
            : Optional.of(kotlinExtraParams.getJvmTarget()),
        kotlinExtraParams.getShouldGenerateAnnotationProcessingStats(),
        kotlinExtraParams.getShouldVerifySourceOnlyAbiConstraints());
  }
}
