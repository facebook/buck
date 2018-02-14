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

package com.facebook.buck.rules.keys;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableSet;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Interface for caches for rule keys. */
public interface RuleKeyCache<V> {

  /** @return the rule key value for the given {@code rule}, or null if it is not cached.. */
  @Nullable
  V get(BuildRule rule);

  /**
   * @return the rule key value for the given {@code rule}, either serving it form cache or by
   *     running the given function.
   */
  V get(BuildRule rule, Function<? super BuildRule, RuleKeyResult<V>> create);

  /**
   * @return the rule key value for the given {@code appendable}, either serving it form cache or by
   *     running the given function.
   */
  V get(AddsToRuleKey appendable, Function<? super AddsToRuleKey, RuleKeyResult<V>> create);

  /** Invalidate the given inputs and all their transitive dependents. */
  void invalidateInputs(Iterable<RuleKeyInput> inputs);

  /**
   * Invalidate all inputs *not* from the given {@link ProjectFilesystem}s and their transitive
   * dependents.
   */
  void invalidateAllExceptFilesystems(ImmutableSet<ProjectFilesystem> filesystems);

  /**
   * Invalidate all inputs from a given {@link ProjectFilesystem} and their transitive dependents.
   */
  void invalidateFilesystem(ProjectFilesystem filesystem);

  /** Invalidate everything in the cache. */
  void invalidateAll();
}
