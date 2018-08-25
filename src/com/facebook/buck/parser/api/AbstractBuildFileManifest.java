/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.parser.api;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.skylark.io.GlobSpec;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/** Describes the content of a build file, which includes defined targets and their metadata. */
@BuckStyleImmutable
@Value.Immutable(builder = false)
abstract class AbstractBuildFileManifest {
  /** @return a list of targets defined in the build file. */
  @Value.Parameter
  public abstract ImmutableList<Map<String, Object>> getTargets();

  /** @return a set of extension files read during parsing. */
  @Value.Parameter
  public abstract ImmutableList<String> getIncludes();

  /**
   * @return a map from configuration section to configuration key to the value returned during
   *     parsing.
   */
  @Value.Parameter
  public abstract ImmutableMap<String, Object> getConfigs();

  /** @return an optional map from environment variable to a value read during parsing (if any). */
  @Value.Parameter
  public abstract Optional<ImmutableMap<String, Optional<String>>> getEnv();

  /** @return A mapping from a {@link GlobSpec} to the corresponding set of expanded paths. */
  @Value.Parameter
  public abstract ImmutableMap<GlobSpec, Set<String>> getGlobManifest();

  /**
   * Converts targets and their metadata into a single set of raw nodes.
   *
   * <p>This is for a temporary solution until all clients switch to using build file manifest.
   */
  public ImmutableSet<Map<String, Object>> toRawNodes() {
    Builder<Map<String, Object>> builder =
        ImmutableSet.<Map<String, Object>>builder()
            .addAll(getTargets())
            .add(ImmutableMap.of("__includes", getIncludes()))
            .add(ImmutableMap.of("__configs", getConfigs()));
    if (getEnv().isPresent()) {
      builder.add(ImmutableMap.of("__env", getEnv()));
    }
    return builder.build();
  }
}
