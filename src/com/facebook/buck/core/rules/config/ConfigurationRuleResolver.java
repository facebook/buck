/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.core.rules.config;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;

/** Provides access to {@link ConfigurationRule}. */
public interface ConfigurationRuleResolver {

  /**
   * Returns the {@link ConfigurationRule} associated with the given {@link
   * UnconfiguredBuildTargetView}.
   *
   * @throws HumanReadableException if no rule is associated with the target.
   */
  ConfigurationRule getRule(UnconfiguredBuildTargetView buildTarget);
}
