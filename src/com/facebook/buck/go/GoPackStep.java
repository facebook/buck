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

package com.facebook.buck.go;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

public class GoPackStep extends ShellStep {
  public enum Operation {
    CREATE("c"),
    APPEND("r"),
    EXTRACT("x");

    String opCode;

    Operation(String opCode) {
      this.opCode = opCode;
    }

    public String getOpCode(boolean verbose) {
      return verbose ? opCode + "v" : opCode;
    }
  }

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> packCommandPrefix;
  private final Operation op;
  private final ImmutableList<Path> srcs;
  private final Path output;

  public GoPackStep(
      BuildTarget buildTarget,
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> packCommandPrefix,
      Operation op,
      ImmutableList<Path> srcs,
      Path output) {
    super(Optional.of(buildTarget), workingDirectory);
    this.environment = environment;
    this.packCommandPrefix = packCommandPrefix;
    this.op = op;
    this.output = output;
    this.srcs = srcs;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(packCommandPrefix)
        .add(op.getOpCode(context.getVerbosity().shouldUseVerbosityFlagIfAvailable()))
        .add(output.toString())
        .addAll(srcs.stream().map(Object::toString).iterator())
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "go pack";
  }
}
