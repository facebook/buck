/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;

public class MakeExecutableStep extends ShellStep {
  private final String fileName;

  public MakeExecutableStep(String fileName) {
    this.fileName = fileName;
  }

  // TODO(user): Refactor to use MoreFiles.makeExecutable(File)
  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    if (Platform.detect() != Platform.WINDOWS) {
      return ImmutableList.of("chmod", "+x", fileName);
    } else {
      return ImmutableList.of();
    }
  }

  @Override
  public String getShortName() {
    return "chmod";
  }
}
