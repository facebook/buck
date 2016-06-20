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

import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractInvocationInfo {

  public static final SimpleDateFormat DIR_DATE_FORMAT;

  static {
    DIR_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH'h'mm'm'ss's'");
    DIR_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
  }

  @Value.Parameter
  public abstract BuildId getBuildId();

  @Value.Parameter
  public abstract String getSubCommand();

  @Value.Default
  public long getTimestampMillis() {
    return System.currentTimeMillis();
  }

  // Just a convenient explicit alias.
  public String getCommandId() {
    return getBuildId().toString();
  }

  public String getLogDirectoryName() {
    return String.format(
        "%s_%s_%s",
        DIR_DATE_FORMAT.format(getTimestampMillis()),
        getSubCommand(),
        getBuildId());
  }

  public Path getLogDirectoryPath() {
    return BuckConstant.getLogPath().resolve(getLogDirectoryName() + "/");
  }
}
