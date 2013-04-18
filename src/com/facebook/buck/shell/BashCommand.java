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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * Command that makes it possible to run an arbitrary command in Bash. Whenever possible, a more
 * specific subclass of {@link ShellCommand} should be preferred. BashCommand should be reserved
 * for cases where the expressiveness of Bash (often in the form of *-shell-expansion) makes the
 * command considerably easier to implement.
 */
public class BashCommand extends ShellCommand {

  private final String bashCommand;

  public BashCommand(String bashCommand) {
    this.bashCommand = Preconditions.checkNotNull(bashCommand);
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "bash";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(
      ExecutionContext context) {
    return ImmutableList.of("bash", "-c", bashCommand);
  }

}
