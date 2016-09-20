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
import com.facebook.buck.cli.SlbBuckConfig;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.base.Function;

public class RageBuckConfig {

  private static final String SECTION_NAME = "rage";
  private static final String REPORT_UPLOAD_PATH_FIELD = "report_upload_path";
  private static final String REPORT_MAX_SIZE_FIELD = "report_max_size";
  private static final String EXTRA_INFO_COMMAND_FIELD = "extra_info_command";
  private static final String RAGE_TIMEOUT_MILLIS_FIELD = "rage_timeout_millis";
  private static final String RAGE_MAX_UPLOAD_RETRIES_FIELD = "rage_max_upload_retries";

  private RageBuckConfig() {
  }

  public static RageConfig create(BuckConfig buckConfig) {
    return RageConfig.builder()
        .setReportUploadPath(buckConfig.getValue(
            SECTION_NAME,
            REPORT_UPLOAD_PATH_FIELD).or(RageConfig.UPLOAD_PATH))
        .setReportMaxSizeBytes(
            buckConfig.getValue(SECTION_NAME, REPORT_MAX_SIZE_FIELD).transform(
                new Function<String, Long>() {
                  @Override
                  public Long apply(String input) {
                    return SizeUnit.parseBytes(input);
                  }
                }))
        .setExtraInfoCommand(
            buckConfig.getListWithoutComments(SECTION_NAME, EXTRA_INFO_COMMAND_FIELD))
        .setFrontendConfig(new SlbBuckConfig(buckConfig, SECTION_NAME))
        .setHttpTimeout(buckConfig.getLong(
            SECTION_NAME,
            RAGE_TIMEOUT_MILLIS_FIELD).or(RageConfig.HTTP_TIMEOUT_MILLIS))
        .setMaxUploadRetries(buckConfig.getInteger(
            SECTION_NAME,
            RAGE_MAX_UPLOAD_RETRIES_FIELD).or(RageConfig.HTTP_MAX_UPLOAD_RETRIES))
        .build();
  }
}
