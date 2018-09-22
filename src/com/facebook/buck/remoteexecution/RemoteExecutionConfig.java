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

package com.facebook.buck.remoteexecution;

import com.facebook.buck.core.config.BuckConfig;

/** Config object for the [remoteexecution] section of .buckconfig. */
public class RemoteExecutionConfig {
  public static final String SECTION = "remoteexecution";
  public static final String CONSOLE_CAS_ENABLED_CONFIG = "show_cas_stats_on_console";
  public static final String CONSOLE_ACTIONS_ENABLED_CONFIG = "show_action_stats_on_console";

  private final BuckConfig buckconfig;

  public RemoteExecutionConfig(BuckConfig buckConfig) {
    this.buckconfig = buckConfig;
  }

  /** Whether SuperConsole output of Remote Execution information is enabled. */
  public boolean isSuperConsoleEnabled() {
    return isSuperConsoleEnabledForActionStats() || isSuperConsoleEnabledForCasStats();
  }

  /** Print CAS statistics to the Super Console. */
  public boolean isSuperConsoleEnabledForCasStats() {
    return buckconfig.getBooleanValue(SECTION, CONSOLE_CAS_ENABLED_CONFIG, true);
  }

  /** Print Action statistics to the Super Console. */
  public boolean isSuperConsoleEnabledForActionStats() {
    return buckconfig.getBooleanValue(SECTION, CONSOLE_ACTIONS_ENABLED_CONFIG, true);
  }
}
