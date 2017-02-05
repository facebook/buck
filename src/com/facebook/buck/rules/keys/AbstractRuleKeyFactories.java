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

import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.immutables.BuckStyleTuple;

import org.immutables.value.Value;

/**
 * The various rule key factories used by the build engine.
 */
@Value.Immutable
@BuckStyleTuple
interface AbstractRuleKeyFactories {

  /**
   * @return a {@link RuleKeyFactory} that produces {@link RuleKey}s.
   */
  RuleKeyFactory<RuleKey> getDefaultRuleKeyFactory();

  /**
   * @return a {@link RuleKeyFactory} that produces input-based {@link RuleKey}s.
   */
  RuleKeyFactory<RuleKey> getInputBasedRuleKeyFactory();

  /**
   * @return a {@link DependencyFileRuleKeyFactory} that produces dep-file-based {@link RuleKey}s.
   */
  DependencyFileRuleKeyFactory getDepFileRuleKeyFactory();

}
