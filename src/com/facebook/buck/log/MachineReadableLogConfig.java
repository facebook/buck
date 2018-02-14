/*
 * Copyright 2017-present Facebook, Inc.
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

public final class MachineReadableLogConfig {

  private MachineReadableLogConfig() {}

  public static final String PREFIX_INVOCATION_INFO = "InvocationInfo";
  public static final String PREFIX_EXIT_CODE = "ExitCode";
  public static final String PREFIX_CACHE_STATS = "Cache.Stats";
  public static final String PREFIX_PERFTIMES = "PertTimesStats";
  public static final String PREFIX_BUILD_RULE_FINISHED = "BuildRuleEvent.Finished";
}
