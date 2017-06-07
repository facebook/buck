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

package com.facebook.buck.rage;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.slb.SlbBuckConfig;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractRageConfig implements ConfigView<BuckConfig> {

  @VisibleForTesting static final String RAGE_SECTION = "rage";
  @VisibleForTesting static final String REPORT_UPLOAD_PATH_FIELD = "report_upload_path";
  private static final String REPORT_MAX_SIZE_FIELD = "report_max_size";
  @VisibleForTesting static final String EXTRA_INFO_COMMAND_FIELD = "extra_info_command";
  private static final String RAGE_TIMEOUT_MILLIS_FIELD = "rage_timeout_millis";
  private static final String RAGE_MAX_UPLOAD_RETRIES_FIELD = "rage_max_upload_retries";
  @VisibleForTesting static final String PROTOCOL_VERSION_FIELD = "protocol_version";

  // Default values
  public static final long HTTP_TIMEOUT_MILLIS = 60 * 1000;
  public static final String UPLOAD_PATH = "/rage/upload";
  public static final int HTTP_MAX_UPLOAD_RETRIES = 2;
  public static final RageProtocolVersion DEFAULT_RAGE_PROTOCOL_VERSION =
      RageProtocolVersion.SIMPLE;

  @Value.Parameter
  public String getReportUploadPath() {
    return getDelegate().getValue(RAGE_SECTION, REPORT_UPLOAD_PATH_FIELD).orElse(UPLOAD_PATH);
  }

  @Value.Parameter
  public Optional<Long> getReportMaxSizeBytes() {
    return getDelegate().getValue(RAGE_SECTION, REPORT_MAX_SIZE_FIELD).map(SizeUnit::parseBytes);
  }

  @Value.Parameter
  public long getHttpTimeout() {
    return getDelegate()
        .getLong(RAGE_SECTION, RAGE_TIMEOUT_MILLIS_FIELD)
        .orElse(HTTP_TIMEOUT_MILLIS);
  }

  @Value.Parameter
  public int getMaxUploadRetries() {
    return getDelegate()
        .getInteger(RAGE_SECTION, RAGE_MAX_UPLOAD_RETRIES_FIELD)
        .orElse(RageConfig.HTTP_MAX_UPLOAD_RETRIES);
  }

  @Value.Parameter
  public Optional<SlbBuckConfig> getFrontendConfig() {
    return Optional.of(new SlbBuckConfig(getDelegate(), RAGE_SECTION));
  }

  @Value.Parameter
  public ImmutableList<String> getExtraInfoCommand() {
    return getDelegate().getListWithoutComments(RAGE_SECTION, EXTRA_INFO_COMMAND_FIELD);
  }

  @Value.Parameter
  public RageProtocolVersion getProtocolVersion() {
    return getDelegate()
        .getEnum(RAGE_SECTION, PROTOCOL_VERSION_FIELD, RageProtocolVersion.class)
        .orElse(DEFAULT_RAGE_PROTOCOL_VERSION);
  }

  public enum RageProtocolVersion {
    SIMPLE,
    JSON,
  }
}
