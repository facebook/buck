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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;

/**
 * Tracks parse context.
 *
 * <p>This class provides API to record information retrieved while parsing a build file like parsed
 * rules.
 */
class ParseContext {
  private final ImmutableList.Builder<Map<String, Object>> rawRuleBuilder;
  private final ImmutableSortedSet.Builder<com.google.devtools.build.lib.vfs.Path>
      loadedPathsBuilder;

  ParseContext() {
    rawRuleBuilder = ImmutableList.builder();
    loadedPathsBuilder = ImmutableSortedSet.naturalOrder();
  }

  /** Records the parsed {@code rawRule}. */
  void recordRule(Map<String, Object> rawRule) {
    rawRuleBuilder.add(rawRule);
  }

  /** Records usage of {@code path}. */
  void recordLoadedPath(com.google.devtools.build.lib.vfs.Path path) {
    loadedPathsBuilder.add(path);
  }

  /**
   * @return The list of raw build rules discovered in parsed build file. Raw rule is presented as a
   *     map with attributes as keys and parameters as values.
   */
  ImmutableList<Map<String, Object>> getRecordedRules() {
    return rawRuleBuilder.build();
  }

  /** @return The set of build files and extensions loaded while parsing requested build file. */
  ImmutableSortedSet<com.google.devtools.build.lib.vfs.Path> getLoadedPaths() {
    return loadedPathsBuilder.build();
  }
}
