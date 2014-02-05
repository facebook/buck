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

package com.facebook.buck.rules;

import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;


public class FakeProcessExecutor extends ProcessExecutor {

  private int exitStatus;
  private final String expectedOut;
  private final String expectedErr;

  public FakeProcessExecutor() {
    this(0, "", "");
  }

  public FakeProcessExecutor(int exitStatus, String expectedOut, String expectedErr) {
    super(new Console(Verbosity.ALL,
        System.out,
        System.err,
        Ansi.withoutTty()));

    this.exitStatus = exitStatus;
    this.expectedOut = expectedOut;
    this.expectedErr = expectedErr;
  }

  @Override
  public Result execute(
      Process process,
      boolean shouldPrintStdOut,
      boolean shouldPrintStdErr,
      boolean isSilent) {
    return new Result(exitStatus,
        expectedOut,
        expectedErr);
  }

}
