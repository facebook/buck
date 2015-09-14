/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.versioncontrol;

import com.facebook.buck.cli.BuckConfig;
import com.google.common.base.Optional;

/***
 * Provides simplified access to settings from the version_control section of a .buckconfig file.
 *
 * Available keys:
 *
 * generate_statistics:
 * - Enable or disable the generation of version control statistics
 * - Default value is false.
 * - Currently only Mercurial is supported.
 * hg_cmd:
 * - Override the default Mercurial command used when generating statistics.
 * - Default value is hg
 *
 * Example config section:
 *
 * [version_control]
 *    hg_cmd = hg3
 *    generate_statistics = true
 */
public class VersionControlBuckConfig {
  public static final String VC_SECTION_KEY = "version_control";

  public static final String GENERATE_STATISTICS_KEY = "generate_statistics";
  public static final String HG_CMD_SETTING_KEY = "hg_cmd";

  public static final String HG_CMD_DEFAULT = "hg";
  public static final boolean GENERATE_STATISTICS_DEFAULT = false;

  private final BuckConfig delegate;

  public VersionControlBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public String getHgCmd() {
    return getValue(VC_SECTION_KEY, HG_CMD_SETTING_KEY, HG_CMD_DEFAULT);
  }

  public boolean shouldGenerateStatistics() {
    return delegate.getBooleanValue(
        VC_SECTION_KEY,
        GENERATE_STATISTICS_KEY,
        GENERATE_STATISTICS_DEFAULT);
  }

  private String getValue(String section, String key, String defaultValue) {
    Optional<String> optionalValue = delegate.getValue(section, key);
    return optionalValue.isPresent() ? optionalValue.get() : defaultValue;
  }
}
