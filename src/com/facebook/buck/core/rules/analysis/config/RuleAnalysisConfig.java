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

package com.facebook.buck.core.rules.analysis.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

@BuckStyleValue
public abstract class RuleAnalysisConfig implements ConfigView<BuckConfig> {

  private static final String SECTION = "rule_analysis";

  private static final String MODE_PROPERTY = "mode";

  @Override
  public abstract BuckConfig getDelegate();

  public static RuleAnalysisConfig of(BuckConfig delegate) {
    return ImmutableRuleAnalysisConfig.of(delegate);
  }

  public RuleAnalysisComputationMode getComputationMode() {
    return getDelegate()
        .getEnum(SECTION, MODE_PROPERTY, RuleAnalysisComputationMode.class)
        .orElse(RuleAnalysisComputationMode.DEFAULT);
  }
}
