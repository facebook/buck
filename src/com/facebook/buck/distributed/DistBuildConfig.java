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

package com.facebook.buck.distributed;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.SlbBuckConfig;

public class DistBuildConfig {

  private static final String CACHE_SECTION_NAME = "distributed_build";

  private static final String FRONTEND_REQUEST_TIMEOUT_MILLIS = "thrift_over_http_timeout_millis";
  private static final long DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS = 3000;

  private final SlbBuckConfig frontendConfig;
  private final BuckConfig buckConfig;

  public DistBuildConfig(BuckConfig config) {
    this.buckConfig = config;
    this.frontendConfig = new SlbBuckConfig(config, CACHE_SECTION_NAME);
  }

  public SlbBuckConfig getFrontendConfig() {
    return frontendConfig;
  }

  public long getFrontendRequestTimeoutMillis() {
    return buckConfig.getLong(CACHE_SECTION_NAME, FRONTEND_REQUEST_TIMEOUT_MILLIS)
        .or(DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS);
  }
}
