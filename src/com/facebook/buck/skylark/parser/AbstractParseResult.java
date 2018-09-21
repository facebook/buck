/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

/** Parse result containing build rules defined in build file and supporting metadata. */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractParseResult {
  /**
   * Returns a list of map instances where keys represent rule parameters and values represent rule
   * arguments.
   *
   * <p>For example {"name": "my_rule", ...}
   */
  @Value.Parameter
  public abstract ImmutableList<Map<String, Object>> getRawRules();
  /**
   * Returns a set of extension paths that were loaded explicitly or transitively when parsing
   * current build file.
   */
  @Value.Parameter
  public abstract ImmutableList<String> getLoadedPaths();

  /**
   * Returns all configuration options accessed during parsing of the build file.
   *
   * <p>The schema is section->key->value
   */
  @Value.Parameter
  public abstract ImmutableMap<String, ImmutableMap<String, Optional<String>>>
      getReadConfigurationOptions();

  /** @return A list of {@link GlobSpec} with the corresponding set of expanded paths. */
  @Value.Parameter
  public abstract ImmutableList<GlobSpecWithResult> getGlobManifestWithResult();
}
