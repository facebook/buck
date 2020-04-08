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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Config for {@link RuleKeyCheckListener} */
@BuckStyleValue
public abstract class RuleKeyCheckListenerConfig implements ConfigView<BuckConfig> {
  public static final String SECTION_NAME = "rulekey_check";

  @Override
  public abstract BuckConfig getDelegate();

  public static RuleKeyCheckListenerConfig of(BuckConfig delegate) {
    return ImmutableRuleKeyCheckListenerConfig.ofImpl(delegate);
  }

  public Optional<String> getEndpoint() {
    return getDelegate().getValue(SECTION_NAME, "endpoint_url");
  }

  /**
   * Get the regex to match targets that {@link RuleKeyCheckListener} should query the backend for.
   *
   * @return a map with keys as the repo and values as the compiled regex {@link Pattern}
   */
  public ImmutableList<Pattern> getTargetsEnabledFor() {
    ImmutableList<String> raw =
        getDelegate().getListWithoutComments(SECTION_NAME, "targets_enabled_for");
    return ImmutableList.copyOf(raw.stream().map(Pattern::compile).collect(Collectors.toList()));
  }

  public ImmutableMap<String, String> getDivergenceWarningMessageMap() {
    return getDelegate().getMap(SECTION_NAME, "divergence_warning_message");
  }

  public int getDivergenceWarningThresholdInSec() {
    return getDelegate().getInteger(SECTION_NAME, "divergence_warning_threshold_in_sec").orElse(60);
  }
}
