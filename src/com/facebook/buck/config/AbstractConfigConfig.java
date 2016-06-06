/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.config;

import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Optional;

import org.immutables.value.Value;

import java.nio.file.Path;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractConfigConfig {
  /**
   * Whether to look into system and homedir for config files.
   */
  public abstract boolean isUsingGlobalConfig();

  /**
   * Project directory to look into for config files.
   */
  public abstract Optional<Path> getProjectRoot();

  /**
   * Additional overrides derived elsewhere.
   */
  public abstract RawConfig getOverrides();

  public static ConfigConfig of() {
    return ConfigConfig.of(false, Optional.<Path>absent(), RawConfig.of());
  }
}
