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

package com.facebook.buck.rules.keys.config;

import com.facebook.buck.core.module.BuckModuleHashStrategy;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Provides rule key configuration options. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractRuleKeyConfiguration {

  /** The seed of all rule keys. */
  @Value.Parameter
  public abstract int getSeed();

  @Value.Parameter
  public abstract String getCoreKey();

  @Value.Parameter
  public abstract long getBuildInputRuleKeyFileSizeLimit();

  @Value.Parameter
  public abstract BuckModuleHashStrategy getBuckModuleHashStrategy();
}
