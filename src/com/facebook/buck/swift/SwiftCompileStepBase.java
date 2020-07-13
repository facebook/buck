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

package com.facebook.buck.swift;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.util.stream.Stream;

/** A base step for Swift compilation jobs. */
abstract class SwiftCompileStepBase implements Step {
  protected final AbsPath compilerCwd;
  protected final ImmutableList<String> compilerCommandPrefix;
  protected final ImmutableList<String> compilerCommandArguments;
  protected final ProjectFilesystem filesystem;
  protected final boolean withDownwardApi;

  SwiftCompileStepBase(
      AbsPath compilerCwd,
      ImmutableList<String> compilerCommandPrefix,
      ImmutableList<String> compilerCommandArguments,
      ProjectFilesystem filesystem,
      boolean withDownwardApi) {
    this.compilerCwd = compilerCwd;
    this.compilerCommandPrefix = compilerCommandPrefix;
    this.compilerCommandArguments = compilerCommandArguments;
    this.filesystem = filesystem;
    this.withDownwardApi = withDownwardApi;
  }

  protected ImmutableList<String> getRawCommand() {
    return Stream.concat(compilerCommandPrefix.stream(), compilerCommandArguments.stream())
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return Joiner.on(" ").join(getRawCommand());
  }
}
