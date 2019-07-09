/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.config.registry;

import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Provides centralized access to various configuration rules. */
@BuckStyleValue
public interface ConfigurationRuleRegistry {

  /** Provides generic access to configuration rules. */
  ConfigurationRuleResolver getConfigurationRuleResolver();

  /**
   * Allows to get access to configuration rules that represent {@code constraint_setting} and
   * {@code constraint_value} rules.
   */
  ConstraintResolver getConstraintResolver();

  /** Allows to get access to configuration rules that represent {@code platform} rules. */
  PlatformResolver getPlatformResolver();

  /** Resolves target configuration to a platform. */
  TargetPlatformResolver getTargetPlatformResolver();
}
