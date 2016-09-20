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

import com.facebook.buck.cli.SlbBuckConfig;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractRageConfig {

  // Defaults
  public static final long HTTP_TIMEOUT_MILLIS = 15 * 1000;
  public static final String UPLOAD_PATH = "/rage/upload";
  public static final int HTTP_MAX_UPLOAD_RETRIES = 2;

  public abstract Optional<Long> getReportMaxSizeBytes();
  public abstract ImmutableList<String> getExtraInfoCommand();
  public abstract Optional<SlbBuckConfig> getFrontendConfig();

  @Value.Default
  public String getReportUploadPath() {
    return UPLOAD_PATH;
  }

  @Value.Default
  public long getHttpTimeout() {
    return HTTP_TIMEOUT_MILLIS;
  }

  @Value.Default
  public int getMaxUploadRetries() {
    return HTTP_MAX_UPLOAD_RETRIES;
  }
}
