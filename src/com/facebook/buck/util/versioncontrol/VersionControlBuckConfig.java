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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/**
 * * Provides simplified access to settings from the version_control section of a .buckconfig file.
 *
 * <p>Available keys:
 *
 * <p>generate_statistics: - Enable or disable the generation of version control statistics -
 * Default value is false. - Currently only Mercurial is supported. hg_cmd: - Override the default
 * Mercurial command used when generating statistics. - Default value is hg
 *
 * <p>Example config section:
 *
 * <p>[version_control] hg_cmd = hg3 generate_statistics = true
 */
public class VersionControlBuckConfig {
  static final String VC_SECTION_KEY = "version_control";

  static final String GENERATE_STATISTICS_KEY = "generate_statistics";
  static final boolean GENERATE_STATISTICS_DEFAULT = false;

  static final String HG_CMD_SETTING_KEY = "hg_cmd";
  static final String HG_CMD_DEFAULT = "hg";

  static final String PREGENERATED_CURRENT_REVISION_ID = "pregenerated_current_revision_id";
  static final String PREGENERATED_BASE_BOOKMARKS = "pregenerated_base_bookmarks";
  static final String PREGENERATED_BASE_REVISION_ID = "pregenerated_base_revision_id";
  static final String PREGENERATED_BASE_REVISION_TIMESTAMP = "pregenerated_base_revision_timestamp";

  private final BuckConfig delegate;

  public VersionControlBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public String getHgCmd() {
    return getValue(VC_SECTION_KEY, HG_CMD_SETTING_KEY, HG_CMD_DEFAULT);
  }

  public boolean shouldGenerateStatistics() {
    return delegate.getBooleanValue(
        VC_SECTION_KEY, GENERATE_STATISTICS_KEY, GENERATE_STATISTICS_DEFAULT);
  }

  public Optional<FastVersionControlStats> getPregeneratedVersionControlStats() {
    ImmutableSet<Optional<?>> stats =
        ImmutableSet.of(
            getPregeneratedCurrentRevisionId(),
            getPregeneratedBaseBookmarks(),
            getPregeneratedBaseRevisionId(),
            getPregeneratedBaseRevisionTimestamp());
    if (stats.stream().anyMatch(Optional::isPresent)) {
      if (!stats.stream().allMatch(Optional::isPresent)) {
        throw new HumanReadableException(
            "Specified some of the pregenerated version control stats in the configs, "
                + "but not all: "
                + String.join(
                    ", ",
                    PREGENERATED_CURRENT_REVISION_ID,
                    PREGENERATED_BASE_BOOKMARKS,
                    PREGENERATED_BASE_REVISION_ID,
                    PREGENERATED_BASE_REVISION_TIMESTAMP));
      }
      return Optional.of(
          FastVersionControlStats.of(
              getPregeneratedCurrentRevisionId().get(),
              getPregeneratedBaseBookmarks().get(),
              getPregeneratedBaseRevisionId().get(),
              getPregeneratedBaseRevisionTimestamp().get()));
    }
    return Optional.empty();
  }

  private Optional<String> getPregeneratedCurrentRevisionId() {
    return delegate.getValue(VC_SECTION_KEY, PREGENERATED_CURRENT_REVISION_ID);
  }

  private Optional<ImmutableList<String>> getPregeneratedBaseBookmarks() {
    return delegate.getOptionalListWithoutComments(VC_SECTION_KEY, PREGENERATED_BASE_BOOKMARKS);
  }

  private Optional<String> getPregeneratedBaseRevisionId() {
    return delegate.getValue(VC_SECTION_KEY, PREGENERATED_BASE_REVISION_ID);
  }

  private Optional<Long> getPregeneratedBaseRevisionTimestamp() {
    return delegate.getLong(VC_SECTION_KEY, PREGENERATED_BASE_REVISION_TIMESTAMP);
  }

  private String getValue(String section, String key, String defaultValue) {
    Optional<String> optionalValue = delegate.getValue(section, key);
    return optionalValue.orElse(defaultValue);
  }
}
