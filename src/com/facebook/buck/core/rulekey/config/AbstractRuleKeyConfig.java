/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rulekey.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.rulekey.RuleKeyDiagnosticsMode;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

@BuckStyleImmutable
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractRuleKeyConfig implements ConfigView<BuckConfig> {

  private static final String LOG_SECTION = "log";

  @Override
  @Value.Parameter
  public abstract BuckConfig getDelegate();

  public RuleKeyDiagnosticsMode getRuleKeyDiagnosticsMode() {
    return getDelegate()
        .getEnum(LOG_SECTION, "rule_key_diagnostics_mode", RuleKeyDiagnosticsMode.class)
        .orElse(RuleKeyDiagnosticsMode.NEVER);
  }
}
