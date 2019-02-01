/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.support.log;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import org.immutables.value.Value;

@BuckStyleTuple
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractLogBuckConfig implements ConfigView<BuckConfig> {

  @Override
  public abstract BuckConfig getDelegate();

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
  public boolean isCriticalPathAnalysisEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "critical_path_analysis_enabled", false);
  }

  @Value.Lazy
  public int getCriticalPathCount() {
    return getDelegate().getInteger(LOG_SECTION, "critical_path_count").orElse(1);
  }

  @Value.Lazy
  public boolean isBuckConfigLocalWarningEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "buckconfig_local_warning_enabled", false);
  }

  @Value.Lazy
  public boolean isJavaUtilsLoggingEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "jul_build_log", false);
  }
}
