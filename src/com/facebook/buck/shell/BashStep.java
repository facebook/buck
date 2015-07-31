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

import java.util.Arrays;

/**
 * Command that makes it possible to run an arbitrary command in Bash. Whenever possible, a more
 * specific subclass of {@link ShellStep} should be preferred. BashCommand should be reserved
 * for cases where the expressiveness of Bash (often in the form of *-shell-expansion) makes the
 * command considerably easier to implement.
 */
public class BashStep extends ShellStep {

  private final String[] bashCommand;

  /**
   * @param bashCommand command to execute. For convenience, multiple arguments are supported
   *     and will be joined with space characters if more than one is present.
   */
  public BashStep(String... bashCommand) {
    this.bashCommand = bashCommand;
  }

  @Override
  public String getShortName() {
    return "bash";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(
      ExecutionContext context) {
    return ImmutableList.<String>builder()
        .add("bash")
        .add("-c")
        .addAll(Arrays.asList(bashCommand))
        .build();
  }
}
