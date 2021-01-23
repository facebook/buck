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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.downwardapi.utils.DownwardApiConstants;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.workertool.utils.WorkerToolConstants;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/** Data class for variables passed through the environment. */
@BuckStyleValue
public abstract class WorkerToolParsedEnvs {

  public abstract Verbosity getVerbosity();

  public abstract boolean isAnsiTerminal();

  public abstract BuildId getBuildUuid();

  public abstract String getActionId();

  public abstract Path getEventPipe();

  public abstract Path getCommandPipe();

  /** Parses env variables to {@link WorkerToolParsedEnvs} */
  public static WorkerToolParsedEnvs parse(ImmutableMap<String, String> envs) {
    return ImmutableWorkerToolParsedEnvs.ofImpl(
        Verbosity.valueOf(getNonNullValue(envs, DownwardApiConstants.ENV_VERBOSITY)),
        Boolean.parseBoolean(getNonNullValue(envs, DownwardApiConstants.ENV_ANSI_ENABLED)),
        new BuildId(getNonNullValue(envs, DownwardApiConstants.ENV_BUILD_UUID)),
        getNonNullValue(envs, DownwardApiConstants.ENV_ACTION_ID),
        Paths.get(getNonNullValue(envs, DownwardApiConstants.ENV_EVENT_PIPE)),
        Paths.get(getNonNullValue(envs, WorkerToolConstants.ENV_COMMAND_PIPE)));
  }

  private static String getNonNullValue(ImmutableMap<String, String> envs, String key) {
    return Objects.requireNonNull(envs.get(key), () -> "Missing env var: " + key);
  }
}
