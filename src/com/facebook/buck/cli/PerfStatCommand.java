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

package com.facebook.buck.cli;

import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.ExitCode;

/** Some human readable statistics of running buck process. */
public class PerfStatCommand extends AbstractCommand {

  private static String formatMemory(long bytes) {
    return String.format("%4dM", bytes >> 20);
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    DirtyPrintStreamDecorator out = params.getConsole().getStdErr();
    out.println("WARNING: this is unstable interface, do not use it in any scripts");
    out.println("total memory: " + formatMemory(Runtime.getRuntime().totalMemory()));
    out.println("free memory:  " + formatMemory(Runtime.getRuntime().freeMemory()));
    out.println("max memory:   " + formatMemory(Runtime.getRuntime().maxMemory()));
    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Print stats about the current buck java process";
  }
}
