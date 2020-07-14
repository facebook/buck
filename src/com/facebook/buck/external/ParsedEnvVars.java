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

package com.facebook.buck.external;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.downwardapi.utils.DownwardApiConstants;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Data class for variables passed through the environment. */
@BuckStyleValue
abstract class ParsedEnvVars {

  abstract Verbosity getVerbosity();

  abstract boolean isAnsiTerminal();

  abstract BuildId getBuildUuid();

  abstract String getActionId();

  abstract Path getEventPipe();

  abstract AbsPath getRuleCellRoot();

  public static ParsedEnvVars parse(ImmutableMap<String, String> envs) {
    return ImmutableParsedEnvVars.ofImpl(
        Verbosity.valueOf(checkNotNull(envs, DownwardApiConstants.ENV_VERBOSITY)),
        Boolean.valueOf(checkNotNull(envs, DownwardApiConstants.ENV_ANSI_ENABLED)),
        new BuildId(checkNotNull(envs, DownwardApiConstants.ENV_BUILD_UUID)),
        checkNotNull(envs, DownwardApiConstants.ENV_ACTION_ID),
        Paths.get(checkNotNull(envs, DownwardApiConstants.ENV_EVENT_PIPE)),
        AbsPath.get(checkNotNull(envs, ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT)));
  }

  private static String checkNotNull(ImmutableMap<String, String> envs, String key) {
    return Preconditions.checkNotNull(envs.get(key), "Missing env var: %s", key);
  }
}
