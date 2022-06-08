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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;

/** Kosabi build rule by enabling and configuring Kosabi kotlinc plugin. */
public class Kosabi {

  /** Helper method to add parse-time deps to target graph for Kosabi flavored targets. */
  public static void addParseTimeDeps(
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder,
      BuildTarget buildTarget,
      KosabiConfig kosabiConfig) {

    // If we're building a target A that depends on B we can't figure out if B
    // is a full build or an abi build. Thus we're adding a dependencies for every build.
    //
    // A -> B#source-only-abi -> C
    kosabiConfig
        .getStubsGenPlugin(buildTarget.getTargetConfiguration())
        .filter(BuildTargetSourcePath.class::isInstance)
        .map(sourcePath -> ((BuildTargetSourcePath) sourcePath).getTarget())
        .ifPresent(targetGraphOnlyDepsBuilder::add);
    kosabiConfig
        .getJvmAbiGenPlugin(buildTarget.getTargetConfiguration())
        .filter(BuildTargetSourcePath.class::isInstance)
        .map(sourcePath -> ((BuildTargetSourcePath) sourcePath).getTarget())
        .ifPresent(targetGraphOnlyDepsBuilder::add);
  }

  /** Helper method to get the Kosabi plugins. */
  public static ImmutableMap<String, SourcePath> getPluginOptionsMappings(
      TargetConfiguration targetConfiguration, KosabiConfig kosabiConfig) {

    ImmutableMap.Builder<String, SourcePath> builder = ImmutableMap.builder();
    kosabiConfig
        .getStubsGenPlugin(targetConfiguration)
        .ifPresent(plugin -> builder.put(KosabiConfig.PROPERTY_KOSABI_STUBS_GEN_PLUGIN, plugin));
    kosabiConfig
        .getJvmAbiGenPlugin(targetConfiguration)
        .ifPresent(plugin -> builder.put(KosabiConfig.PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN, plugin));
    ImmutableMap<String, SourcePath> sourcePathImmutableMap = builder.build();
    // Kosabi needs both plugins to work correctly
    if (sourcePathImmutableMap.size() == 2) {
      return sourcePathImmutableMap;
    } else {
      return ImmutableMap.<String, SourcePath>builder().build();
    }
  }
}
