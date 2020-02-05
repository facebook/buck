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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.immutables.value.Value;

/**
 * Holds information to tell the python wrapper whether to run anything after executing the current
 * command, and how to do it
 */
@BuckStyleValue
@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public abstract class BuckRunSpec {
  /** The path to the binary to invoke */
  @JsonProperty
  @Value.Derived
  public Path getPath() {
    return Paths.get(getArgv().get(0));
  }

  /** The arguments to invoke the command. The first element must be the binary to invoke */
  @JsonProperty
  public abstract ImmutableList<String> getArgv();

  /** A mapping of environment variables that should be set when invoking the command */
  @JsonProperty
  public abstract ImmutableMap<String, String> getEnvp();

  /** The PWD where the command should be run from */
  @JsonProperty
  public abstract Path getCwd();

  /**
   * Whether the program is a 'fix' script or not
   *
   * <p>This can affect things like signal handling, exit codes and how the program is actually
   * executed in the wrapper
   */
  @JsonProperty
  public abstract boolean getIsFixScript();

  public static BuckRunSpec of(
      ImmutableList<String> argv,
      ImmutableMap<String, String> envp,
      Path cwd,
      boolean isFixScript) {
    return ImmutableBuckRunSpec.of(argv, envp, cwd, isFixScript);
  }
}
