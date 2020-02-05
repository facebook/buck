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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Optional;

/**
 * Parse result containing build/package rules and package defined in build or package files and
 * supporting metadata.
 */
@BuckStyleValue
public abstract class ParseResult {

  /** Contains the package defined in a package file. */
  public abstract PackageMetadata getPackage();

  /**
   * Returns rules organized in a map where a key is a rule name and the value is a map with keys
   * representing rule parameters and values representing rule arguments.
   *
   * <p>For example {"name": "my_rule", ...}
   */
  public abstract ImmutableMap<String, Map<String, Object>> getRawRules();

  /**
   * Returns a set of extension paths that were loaded explicitly or transitively when parsing
   * current build or package file.
   */
  public abstract ImmutableSet<String> getLoadedPaths();

  /**
   * Returns all configuration options accessed during parsing of the build or package file.
   *
   * <p>The schema is section->key->value
   */
  public abstract ImmutableMap<String, ImmutableMap<String, Optional<String>>>
      getReadConfigurationOptions();

  /** @return A list of {@link GlobSpec} with the corresponding set of expanded paths. */
  public abstract ImmutableList<GlobSpecWithResult> getGlobManifestWithResult();

  static ParseResult of(
      PackageMetadata getPackage,
      Map<String, ? extends Map<String, Object>> rawRules,
      Iterable<String> loadedPaths,
      Map<String, ? extends ImmutableMap<String, Optional<String>>> readConfigurationOptions,
      Iterable<? extends GlobSpecWithResult> globManifestWithResult) {
    return ImmutableParseResult.of(
        getPackage, rawRules, loadedPaths, readConfigurationOptions, globManifestWithResult);
  }
}
