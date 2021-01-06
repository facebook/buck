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

package com.facebook.buck.support.log;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class LogBuckConfig implements ConfigView<BuckConfig> {

  @Override
  public abstract BuckConfig getDelegate();

  public static LogBuckConfig of(BuckConfig delegate) {
    return ImmutableLogBuckConfig.of(delegate);
  }

  private static final String LOG_SECTION = "log";

  @Value.Lazy
  public boolean isPublicAnnouncementsEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "public_announcements", true);
  }

  @Value.Lazy
  public boolean isProcessTrackerEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "process_tracker_enabled", true);
  }

  @Value.Lazy
  public boolean isProcessTrackerDeepEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "process_tracker_deep_enabled", false);
  }

  @Value.Lazy
  public boolean isRuleKeyLoggerEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "rule_key_logger_enabled", false);
  }

  @Value.Lazy
  public boolean isMachineReadableLoggerEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "machine_readable_logger_enabled", true);
  }

  @Value.Lazy
  public boolean isBuckConfigLocalWarningEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "buckconfig_local_warning_enabled", false);
  }

  @Value.Lazy
  public boolean isJavaUtilsLoggingEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "jul_build_log", false);
  }

  @Value.Lazy
  public boolean isJavaGCEventLoggingEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "gc_event_logging_enabled", false);
  }

  public boolean isLogBuildIdToConsoleEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "log_build_id_to_console_enabled", false);
  }

  public Optional<String> getBuildDetailsTemplate() {
    return getDelegate().getValue(LOG_SECTION, "build_details_template");
  }

  @Value.Lazy
  public ImmutableSet<String> getBuildDetailsCommands() {
    return getDelegate()
        .getOptionalListWithoutComments(LOG_SECTION, "build_details_commands")
        .map(ImmutableSet::copyOf)
        .orElse(ImmutableSet.of("build", "test", "install"));
  }
}
