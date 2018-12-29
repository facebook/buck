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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Verbosity;
import java.util.Optional;
import java.util.OptionalInt;

public class SuperConsoleConfig {

  private static final String SECTION_NAME = "ui";
  private static final int DEFAULT_THREAD_LINE_LIMIT = 10;
  private static final long DEFAULT_BUILD_RULE_MINIMUM_DURATION_MILLIS = 0;
  private static final int DEFAULT_NUMBER_OF_SLOW_RULES_TO_SHOW = 0;

  /** Whether the super console is forced on, off or should we auto detect it */
  private enum Mode {
    ENABLED,
    DISABLED,
    AUTO
  }

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

  // It will hide build rules from super console when the duration is below this threshold.
  // It will help with the ratio signal vs noise in super console and highlight things that
  // are slower.
  public long getBuildRuleMinimumDurationMillis() {
    return delegate
        .getLong(SECTION_NAME, "build_rule_minimum_duration_millis")
        .orElse(DEFAULT_BUILD_RULE_MINIMUM_DURATION_MILLIS);
  }

  // When true, it will hide successful built rules when using the simple console.
  public boolean getHideSucceededRulesInLogMode() {
    return delegate.getBooleanValue(SECTION_NAME, "hide_succeeded_rules_in_log_mode", false);
  }

  public int getNumberOfSlowRulesToShow() {
    return getPositiveInt(SECTION_NAME, "number_of_slow_rules_to_show")
        .orElse(DEFAULT_NUMBER_OF_SLOW_RULES_TO_SHOW);
  }

  public boolean shouldShowSlowRulesInConsole() {
    return delegate.getBooleanValue(SECTION_NAME, "show_slow_rules_in_console", false);
  }

  public OptionalInt getThreadLineOutputMaxColumns() {
    return delegate.getInteger(SECTION_NAME, "thread_line_output_max_columns");
  }

  private OptionalInt getPositiveInt(String sectionName, String propertyName) {
    Optional<Long> optional = delegate.getLong(sectionName, propertyName);
    if (!optional.isPresent()) {
      return OptionalInt.empty();
    }
    long value = optional.get();
    if (value <= 0 || value > Integer.MAX_VALUE) {
      throw new HumanReadableException(
          "Configuration %s:%s contains value out of range: %s.", sectionName, propertyName, value);
    }
    return OptionalInt.of((int) value);
  }

  /**
   * Whether the SuperConsole is enabled
   *
   * @return whether the output should be presented using the super superConsole style
   */
  public boolean isEnabled(Ansi ansi, Verbosity verbosity) {
    Mode mode = delegate.getEnum(SECTION_NAME, "superconsole", Mode.class).orElse(Mode.AUTO);
    switch (mode) {
      case ENABLED:
        return true;
      case DISABLED:
        return false;
      case AUTO:
        return ansi.isAnsiTerminal()
            && !verbosity.shouldPrintCommand()
            && verbosity.shouldPrintStandardInformation();
      default:
        throw new IllegalArgumentException("Unhandled case: " + mode);
    }
  }
}
