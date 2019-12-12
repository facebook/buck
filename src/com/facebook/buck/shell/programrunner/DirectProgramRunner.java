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

package com.facebook.buck.shell.programrunner;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** {@code ProgramRunner} that do not change the execution of a program. */
public class DirectProgramRunner implements ProgramRunner {

  @Override
  public void prepareForRun(ProjectFilesystem projectFilesystem, Path programPath) {}

  @Override
  public ImmutableList<String> enhanceCommandLine(ImmutableList<String> commandLine) {
    return commandLine;
  }

  @Override
  public ImmutableList<String> enhanceCommandLineForDescription(ImmutableList<String> commandLine) {
    return commandLine;
  }
}
