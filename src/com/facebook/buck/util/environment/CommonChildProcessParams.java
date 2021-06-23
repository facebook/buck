/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.environment;

import com.facebook.buck.util.env.BuckClasspath;
import com.google.common.collect.ImmutableMap;
import java.util.Objects;

/** Utils used to retrieve common env variables for child processes. */
public class CommonChildProcessParams {

  private static final String PATH_ENV_VARIABLE = "PATH";
  private static final ImmutableMap<String, String> SYSTEM_ENV =
      EnvVariablesProvider.getSystemEnv();

  private CommonChildProcessParams() {}

  /**
   * Returns common env params with buck classpath that could be passed into child processes spawned
   * by buck.
   */
  public static ImmutableMap<String, String> getCommonChildProcessEnvsIncludingBuckClasspath() {
    return getCommonChildProcessEnvsIncludingBuckClasspath(false);
  }

  /**
   * Returns common env params with buck classpath that could be passed into child processes spawned
   * by buck. If {@code includeBucksEnvVariables} is set then all env variables from buck process is
   * returned.
   */
  public static ImmutableMap<String, String> getCommonChildProcessEnvsIncludingBuckClasspath(
      boolean includeBucksEnvVariables) {
    ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();
    if (includeBucksEnvVariables) {
      envBuilder.putAll(SYSTEM_ENV);
    } else {
      String buckClasspath =
          Objects.requireNonNull(
              BuckClasspath.getBuckClasspathFromEnvVarOrNull(),
              BuckClasspath.ENV_VAR_NAME + " env variable is not set");
      envBuilder.put(BuckClasspath.ENV_VAR_NAME, buckClasspath);
      envBuilder.putAll(getCommonChildProcessEnvs());
    }
    return envBuilder.build();
  }

  /** Returns common env params that has to be passed into child processes spawned by buck. */
  public static ImmutableMap<String, String> getCommonChildProcessEnvs() {
    return ImmutableMap.of(PATH_ENV_VARIABLE, SYSTEM_ENV.getOrDefault(PATH_ENV_VARIABLE, ""));
  }
}
