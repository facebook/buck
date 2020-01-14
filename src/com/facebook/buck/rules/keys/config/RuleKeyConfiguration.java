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

package com.facebook.buck.rules.keys.config;

import com.facebook.buck.core.module.BuckModuleHashStrategy;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Provides rule key configuration options. */
@BuckStyleValue
public abstract class RuleKeyConfiguration {

  /** The seed of all rule keys. */
  public abstract int getSeed();

  public abstract String getCoreKey();

  public abstract long getBuildInputRuleKeyFileSizeLimit();

  public abstract BuckModuleHashStrategy getBuckModuleHashStrategy();

  public static RuleKeyConfiguration of(
      int seed,
      String coreKey,
      long buildInputRuleKeyFileSizeLimit,
      BuckModuleHashStrategy buckModuleHashStrategy) {
    return ImmutableRuleKeyConfiguration.of(
        seed, coreKey, buildInputRuleKeyFileSizeLimit, buckModuleHashStrategy);
  }
}
