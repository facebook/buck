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

/**
 * A class managing access to a {@link RuleKeyCache} for the duration of a build. Accessing a cache
 * via a {@link RuleKeyCacheScope} inside of a try-resource block allows both pre-build and
 * post-build operations to always be run before and after using the cache.
 */
public interface RuleKeyCacheScope<V> extends AutoCloseable {

  /** @return the scoped {@link RuleKeyCache}. */
  TrackedRuleKeyCache<V> getCache();

  @Override
  void close();
}
