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

package com.facebook.buck.core.description.arg;

import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/** Common arguments for build rules (but not configuration rules) */
public interface BuildRuleArg extends ConstructorArg {
  ImmutableSet<SourcePath> getLicenses();

  @Value.NaturalOrder
  ImmutableSortedSet<String> getLabels();

  String DEFAULT_TARGET_PLATFORM_PARAM_NAME = "defaultTargetPlatform";

  /**
   * The name of build target default "new" platform: it is used when a platform is not specified
   * either globally or in a target which used this target as a dependency.
   *
   * <p>The value is a build target, but we specify it as string, because this function is not
   * actually called, but the attr is fetched by name from the raw (unconfigured) target node.
   */
  @Hint(isDep = false, isConfigurable = false)
  Optional<UnconfiguredBuildTarget> getDefaultTargetPlatform();

  /** A list of {@code config_setting} a target is compatible with. */
  @Hint(isDep = false, isConfigurable = false)
  ImmutableList<UnconfiguredBuildTarget> getCompatibleWith();

  @Value.Derived
  default boolean labelsContainsAnyOf(Set<String> labels) {
    return !Sets.intersection(this.getLabels(), labels).isEmpty();
  }
}
