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

package com.facebook.buck.support.fix;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/** Information about the build that is provided to 'fix' scripts as a json file */
@BuckStyleValue
@JsonInclude(Include.ALWAYS)
@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
@JsonDeserialize(as = ImmutableBuckFixSpec.class)
public abstract class BuckFixSpec {

  private static final String MAIN_LOG_KEY = "main_log";
  private static final String MACHINE_LOG_KEY = "machine_log";
  private static final String TRACE_FILE_KEY = "trace_file";
  private static final String BUCK_CONFIG_KEY = "buckconfig";

  /** The build ID. Generally a UUID */
  @JsonProperty
  public abstract BuildId getBuildId();

  /** The name of the command that was run. e.g. 'build' or 'query' */
  @JsonProperty
  public abstract String getCommand();

  /** The exit code of the command */
  @JsonProperty
  public abstract int getExitCode();

  /**
   * The arguments that the user sent to buck. This may include unexpanded arguments such as
   * flagfiles
   */
  @JsonProperty
  public abstract ImmutableList<String> getUserArgs();

  /** The fully evaluated arguments to this buck invocation. */
  @JsonProperty
  public abstract ImmutableList<String> getExpandedArgs();

  /**
   * Whether the fix script was requested explicitly (through `buck fix`), or automatically from
   * another command
   */
  @JsonProperty
  public abstract boolean getManuallyInvoked();

  /**
   * Arbitrary additional data from the specific command. This is used to allow the script to not
   * need to know how to parse the buck machine log or the main buck log
   *
   * @return Additional data for the specific command
   */
  @JsonProperty
  public abstract Optional<Object> getCommandData();

  /**
   * Get a mapping of names to paths of fix-scripts that are built into buck (such as the original
   * JASABI fix script). These will generally be in the .buckd resources directory
   */
  @JsonProperty
  public abstract ImmutableMap<String, ImmutableList<String>> getBuckProvidedScripts();

  /** Get a mapping of log short names to paths for those logs. */
  @JsonProperty
  @JsonInclude(Include.ALWAYS)
  public abstract ImmutableMap<String, Optional<Path>> getLogs();

  /**
   * Get the standardized mapping of short names to paths to use in {@link #getLogs()}
   *
   * @param mainLog If present, the path to the buck.log file for this invocation
   * @param machineLogFile If present, the path to the buck-machine-log file for this invocation
   * @param traceFile If present, the path to the chrome trace file for this invocation
   * @param serializedBuckConfig If present, the path to the json representation of the
   *     configuration used for this invocation
   * @return A mapping of short names to paths
   */
  static ImmutableMap<String, Optional<Path>> getLogsMapping(
      Optional<Path> mainLog,
      Optional<Path> machineLogFile,
      Optional<Path> traceFile,
      Optional<Path> serializedBuckConfig) {
    return ImmutableMap.of(
        MAIN_LOG_KEY,
        mainLog,
        MACHINE_LOG_KEY,
        machineLogFile,
        TRACE_FILE_KEY,
        traceFile,
        BUCK_CONFIG_KEY,
        serializedBuckConfig);
  }
}
