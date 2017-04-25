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

package com.facebook.buck.log;

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractLogConfigSetup {
  // Total maximum amount of logs stored.
  public static final int DEFAULT_MAX_COUNT = 25;

  // Total minimum amount of logs stored.
  public static final int DEFAULT_MIN_COUNT = 3;

  // Total maximum disk space used for all logs.
  public static final long DEFAULT_MAX_LOG_SIZE_BYTES = 1024 * 1024 * 1024;

  private static final String DEFAULT_LOG_FILE_PREFIX = "buck-";

  // At this point we can't guarantee that CWD is writeable so our logs go into the system's
  // temporary directory.
  public static final LogConfigSetup DEFAULT_SETUP =
      LogConfigSetup.builder().setLogDir(BuckConstant.getBuckOutputPath().resolve("log")).build();

  @Value.Parameter
  public abstract Path getLogDir();

  @Value.Default
  public boolean getRotateLog() {
    return false;
  }

  @Value.Default
  public int getCount() {
    return DEFAULT_MAX_COUNT;
  }

  @Value.Default
  public long getMaxLogSizeBytes() {
    return DEFAULT_MAX_LOG_SIZE_BYTES;
  }

  @Value.Default
  public String getLogFilePrefix() {
    return DEFAULT_LOG_FILE_PREFIX;
  }

  public Path getLogFilePath() {
    return getLogDir().resolve(getLogFilePrefix() + "%g.log");
  }
}
