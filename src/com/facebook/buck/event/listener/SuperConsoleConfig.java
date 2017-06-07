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

package com.facebook.buck.event.listener;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import java.util.Optional;

public class SuperConsoleConfig {

  private static final String SECTION_NAME = "ui";
  private static final int DEFAULT_THREAD_LINE_LIMIT = 10;

  private final BuckConfig delegate;

  public SuperConsoleConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public int getThreadLineLimit() {
    return getPositiveInt(SECTION_NAME, "thread_line_limit").orElse(DEFAULT_THREAD_LINE_LIMIT);
  }

  public int getThreadLineLimitOnWarning() {
    return getPositiveInt(SECTION_NAME, "thread_line_limit_on_warning")
        .orElse(getThreadLineLimit());
  }

  public int getThreadLineLimitOnError() {
    return getPositiveInt(SECTION_NAME, "thread_line_limit_on_error").orElse(getThreadLineLimit());
  }

  public boolean shouldAlwaysSortThreadsByTime() {
    return delegate.getBooleanValue(SECTION_NAME, "always_sort_threads_by_time", false);
  }

  private Optional<Integer> getPositiveInt(String sectionName, String propertyName) {
    Optional<Long> optional = delegate.getLong(sectionName, propertyName);
    if (!optional.isPresent()) {
      return Optional.empty();
    }
    long value = optional.get();
    if (value <= 0 || value > Integer.MAX_VALUE) {
      throw new HumanReadableException(
          "Configuration %s:%s contains value out of range: %s.", sectionName, propertyName, value);
    }
    return Optional.of((int) value);
  }
}
