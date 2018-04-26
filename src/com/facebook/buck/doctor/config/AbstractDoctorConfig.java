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

package com.facebook.buck.doctor.config;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.slb.SlbBuckConfig;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
abstract class AbstractDoctorConfig implements ConfigView<BuckConfig> {

  public static final String DOCTOR_SECTION = "doctor";
  public static final String PROTOCOL_VERSION_FIELD = "protocol_version";

  // Fields for the analysis endpoint.
  public static final String ENDPOINT_URL_FIELD = "endpoint_url";
  private static final String ENDPOINT_TIMEOUT_MS_FIELD = "endpoint_timeout_ms";
  static final String ENDPOINT_EXTRA_ARGS_FIELD = "endpoint_extra_request_args";

  // Fields for the report upload endpoint.
  public static final String REPORT_UPLOAD_PATH_FIELD = "report_upload_path";
  private static final String REPORT_MAX_SIZE_FIELD = "report_max_size";
  private static final String REPORT_TIMEOUT_MS_FIELD = "report_timeout_ms";
  private static final String REPORT_MAX_UPLOAD_RETRIES_FIELD = "report_max_upload_retries";

  public static final String REPORT_EXTRA_INFO_COMMAND_FIELD = "report_extra_info_command";

  // Default values.
  public static final long DEFAULT_ENDPOINT_TIMEOUT_MS = 15 * 1000;
  public static final long DEFAULT_REPORT_TIMEOUT_MS = 30 * 1000;
  public static final int DEFAULT_REPORT_MAX_UPLOAD_RETRIES = 2;
  public static final String DEFAULT_REPORT_UPLOAD_PATH = "/rage/upload";

  @Value.Parameter
  public DoctorProtocolVersion getProtocolVersion() {
    return getDelegate()
        .getEnum(DOCTOR_SECTION, PROTOCOL_VERSION_FIELD, DoctorProtocolVersion.class)
        .orElse(DoctorProtocolVersion.JSON);
  }

  @Value.Parameter
  public Optional<String> getEndpointUrl() {
    return getDelegate().getValue(DOCTOR_SECTION, ENDPOINT_URL_FIELD);
  }

  @Value.Parameter
  public long getEndpointTimeoutMs() {
    return getDelegate()
        .getLong(DOCTOR_SECTION, ENDPOINT_TIMEOUT_MS_FIELD)
        .orElse(DEFAULT_ENDPOINT_TIMEOUT_MS);
  }

  @Value.Parameter
  public ImmutableMap<String, String> getEndpointExtraRequestArgs() {
    return getDelegate().getMap(DOCTOR_SECTION, ENDPOINT_EXTRA_ARGS_FIELD);
  }

  @Value.Parameter
  public String getReportUploadPath() {
    return getDelegate()
        .getValue(DOCTOR_SECTION, REPORT_UPLOAD_PATH_FIELD)
        .orElse(DEFAULT_REPORT_UPLOAD_PATH);
  }

  @Value.Parameter
  public Optional<Long> getReportMaxSizeBytes() {
    return getDelegate().getValue(DOCTOR_SECTION, REPORT_MAX_SIZE_FIELD).map(SizeUnit::parseBytes);
  }

  @Value.Parameter
  public long getReportTimeoutMs() {
    return getDelegate()
        .getLong(DOCTOR_SECTION, REPORT_TIMEOUT_MS_FIELD)
        .orElse(DEFAULT_REPORT_TIMEOUT_MS);
  }

  @Value.Parameter
  public int getReportMaxUploadRetries() {
    return getDelegate()
        .getInteger(DOCTOR_SECTION, REPORT_MAX_UPLOAD_RETRIES_FIELD)
        .orElse(DoctorConfig.DEFAULT_REPORT_MAX_UPLOAD_RETRIES);
  }

  @Value.Parameter
  public Optional<SlbBuckConfig> getFrontendConfig() {
    return Optional.of(new SlbBuckConfig(getDelegate(), DOCTOR_SECTION));
  }

  @Value.Parameter
  public ImmutableList<String> getExtraInfoCommand() {
    return getDelegate().getListWithoutComments(DOCTOR_SECTION, REPORT_EXTRA_INFO_COMMAND_FIELD);
  }
}
