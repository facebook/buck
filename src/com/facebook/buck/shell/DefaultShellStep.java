/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DefaultShellStep extends ShellStep {

  private ImmutableMap<String, String> environment;
  private ImmutableList<String> args;

  public DefaultShellStep(
      BuildTarget buildTarget,
      Path workingDirectory,
      List<String> args,
      Map<String, String> environment) {
    super(Optional.of(buildTarget), workingDirectory);
    this.args = ImmutableList.copyOf(args);
    this.environment = ImmutableMap.copyOf(environment);
  }

  public DefaultShellStep(BuildTarget buildTarget, Path workingDirectory, List<String> args) {
    this(buildTarget, workingDirectory, args, ImmutableMap.of());
  }

  @Override
  public String getShortName() {
    return args.get(0);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return args;
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }
}
