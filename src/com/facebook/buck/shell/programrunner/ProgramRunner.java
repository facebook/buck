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
import java.io.IOException;
import java.nio.file.Path;

/** Parameters that changes how a program is executed by changing its command line. */
public interface ProgramRunner {

  /** Initialization that needs to be performed before execution. */
  void prepareForRun(ProjectFilesystem projectFilesystem, Path programPath) throws IOException;

  /** Change program command line with arguments specific to these parameters. */
  ImmutableList<String> enhanceCommandLine(ImmutableList<String> commandLine);

  /**
   * Change program command line with arguments specific to these parameters. This is primarily used
   * to construct new command line before {@link #prepareForRun} was called.
   */
  ImmutableList<String> enhanceCommandLineForDescription(ImmutableList<String> commandLine);
}
