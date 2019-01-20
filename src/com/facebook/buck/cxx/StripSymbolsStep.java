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
package com.facebook.buck.cxx;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;

class StripSymbolsStep implements Step {

  private final Path input;
  private final ImmutableList<String> stripCommandPrefix;
  private final ImmutableMap<String, String> stripEnvironment;
  private final ImmutableList<String> stripToolArgs;
  private final ProjectFilesystem projectFilesystem;

  public StripSymbolsStep(
      Path input,
      ImmutableList<String> stripCommandPrefix,
      ImmutableMap<String, String> stripEnvironment,
      ImmutableList<String> stripToolArgs,
      ProjectFilesystem projectFilesystem) {
    this.input = input;
    this.stripCommandPrefix = stripCommandPrefix;
    this.stripEnvironment = stripEnvironment;
    this.stripToolArgs = stripToolArgs;
    this.projectFilesystem = projectFilesystem;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    return (new DefaultShellStep(
            projectFilesystem.getRootPath(),
            ImmutableList.<String>builder()
                .addAll(stripCommandPrefix)
                .addAll(stripToolArgs)
                .add(projectFilesystem.resolve(input).toString())
                .build(),
            stripEnvironment))
        .execute(context);
  }

  @Override
  public String getShortName() {
    return "strip binary";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("strip debug symbols from binary at '%s'", input);
  }
}
