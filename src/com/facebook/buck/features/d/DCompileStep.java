/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.d;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

public class DCompileStep extends ShellStep {

  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> compiler;
  private final ImmutableList<String> flags;
  private final Path output;
  private final ImmutableCollection<Path> inputs;

  public DCompileStep(
      BuildTarget buildTarget,
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> compiler,
      ImmutableList<String> flags,
      Path output,
      ImmutableCollection<Path> inputs) {
    super(Optional.of(buildTarget), workingDirectory);
    this.environment = environment;
    this.compiler = compiler;
    this.flags = flags;
    this.output = output;
    this.inputs = inputs;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(compiler)
        .addAll(flags)
        .add("-c")
        .add("-of" + output)
        .addAll(inputs.stream().map(Object::toString).iterator())
        .build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "d compile";
  }

  /**
   * @param sourceName name of the source file
   * @return the object file name for the given source file name.
   */
  public static String getObjectNameForSourceName(String sourceName) {
    return sourceName.replaceFirst("(?:\\.[^.]+)$", ".o");
  }
}
