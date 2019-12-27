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

package com.facebook.buck.counters;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

@BuckStyleValue
public abstract class CounterBuckConfig implements ConfigView<BuckConfig> {

  private static final String COUNTERS_SECTION = "counters";

  @Override
  public abstract BuckConfig getDelegate();

  public static CounterBuckConfig of(BuckConfig delegate) {
    return ImmutableCounterBuckConfig.of(delegate);
  }

  public long getCountersFirstFlushIntervalMillis() {
    return getDelegate().getLong(COUNTERS_SECTION, "first_flush_interval_millis").orElse(5000L);
  }

  public long getCountersFlushIntervalMillis() {
    return getDelegate().getLong(COUNTERS_SECTION, "flush_interval_millis").orElse(30000L);
  }
}
