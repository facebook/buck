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

package com.facebook.buck.features.go;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.nio.file.Path;

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
  private final Iterable<Path> filteredAsmSrcs;

  public GoPackStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> packCommandPrefix,
      Operation op,
      ImmutableList<Path> srcs,
      Iterable<Path> filteredAsmSrcs,
      Path output) {
    super(workingDirectory);
    this.environment = environment;
    this.packCommandPrefix = packCommandPrefix;
    this.op = op;
    this.output = output;
    this.srcs = srcs;
    this.filteredAsmSrcs = filteredAsmSrcs;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    if (shouldSkipPacking()) {
      return ImmutableList.of();
    }
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

  private boolean shouldSkipPacking() {
    // We need to verify that we don't have any cgo compiled
    // sources coming in before skipping. Those need to be packed.
    boolean cgoSourcesExist = false;
    for (int i = 0; i < srcs.size(); i++) {
      Path path = srcs.get(i);
      if (path.toString().contains("cgo-second-step")) {
        cgoSourcesExist = true;
        break;
      }
    }
    return Iterables.isEmpty(filteredAsmSrcs) && !cgoSourcesExist;
  }
}
