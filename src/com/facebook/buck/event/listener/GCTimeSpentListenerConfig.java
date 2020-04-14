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

/** Config for {@link GCTimeSpentListener} */
@BuckStyleValue
public abstract class GCTimeSpentListenerConfig implements ConfigView<BuckConfig> {
  public static final String SECTION_NAME = "gc_time_spent_warning";

  @Override
  public abstract BuckConfig getDelegate();

  public static GCTimeSpentListenerConfig of(BuckConfig delegate) {
    return ImmutableGCTimeSpentListenerConfig.ofImpl(delegate);
  }

  public int getThresholdPercentage() {
    return getDelegate().getInteger(SECTION_NAME, "threshold_percentage").orElse(12);
  }

  public int getThresholdInSec() {
    return getDelegate().getInteger(SECTION_NAME, "threshold_in_s").orElse(30);
  }

  public String getExcessTimeWarningAtThresholdTemplate() {
    return getDelegate()
        .getValue(SECTION_NAME, "excess_time_warning_at_threshold_template")
        .orElse("");
  }

  public String getExcessTimeWarningAtEndTemplate() {
    return getDelegate().getValue(SECTION_NAME, "excess_time_warning_at_end_template").orElse("");
  }
}
