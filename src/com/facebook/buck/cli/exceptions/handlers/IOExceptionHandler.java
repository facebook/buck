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

package com.facebook.buck.cli.exceptions.handlers;

import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;

/** Exception handler for IOExceptions */
public class IOExceptionHandler extends ExceptionHandlerWithConsole<IOException> {
  public IOExceptionHandler(Console console) {
    super(IOException.class, console);
  }

  @Override
  public ExitCode handleException(IOException e) {
    if (e instanceof FileSystemLoopException) {
      console.printFailureWithStacktrace(
          e,
          "Loop detected in your directory, which may be caused by circular symlink. "
              + "You may consider running the command in a smaller directory.");
      return ExitCode.FATAL_GENERIC;
    } else if (e.getMessage().startsWith("No space left on device")) {
      console.printBuildFailure(e.getMessage());
      return ExitCode.FATAL_DISK_FULL;
    } else {
      console.printFailureWithStacktrace(e, e.getMessage());
      return ExitCode.FATAL_IO;
    }
  }
}
