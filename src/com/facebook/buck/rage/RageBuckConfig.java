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

public class RageBuckConfig {

  private static final String SECTION_NAME = "rage";
  private static final String REPORT_UPLOAD_URL_FIELD = "report_upload_url";
  private static final String EXTRA_INFO_COMMAND_FIELD = "extra_info_command";

  private RageBuckConfig() {
  }

  public static RageConfig create(BuckConfig buckConfig) {
    return RageConfig.builder()
        .setReportUploadUri(buckConfig.getUrl(SECTION_NAME, REPORT_UPLOAD_URL_FIELD))
        .setExtraInfoCommand(
            buckConfig.getListWithoutComments(SECTION_NAME, EXTRA_INFO_COMMAND_FIELD))
        .build();
  }
}
