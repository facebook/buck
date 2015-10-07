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

import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.List;

public class DefaultShellStep extends ShellStep {

  private ImmutableList<String> args;

  public DefaultShellStep(Path workingDirectory, List<String> args) {
    super(workingDirectory);
    this.args = ImmutableList.copyOf(args);
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    // When java commands are invoked, this sometimes needs to be passed through
    // to ensure that the correct java env is run, rather than the system one
    String javaHome = context.getEnvironment().get("JAVA_HOME");
    if (javaHome == null) {
      javaHome = System.getProperty("java.home");
    }
    return new ImmutableMap.Builder<String, String>()
      .put("JAVA_HOME", javaHome)
      .putAll(super.getEnvironmentVariables(context))
      .build();
  }

  @Override
  public String getShortName() {
    return args.get(0);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(
      ExecutionContext context) {
    return args;
  }

}
