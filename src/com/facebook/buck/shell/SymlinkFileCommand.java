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

import java.io.File;

public class SymlinkFileCommand extends ShellCommand {

  private final File source;
  private final String target;

  /**
   * Both {@code source} and {@code target} must refer to a file (as opposed to a directory).
   */
  SymlinkFileCommand(String source, String target) {
    this(new File(Preconditions.checkNotNull(source)), target);
  }

  SymlinkFileCommand(File source, String target) {
    this.source = Preconditions.checkNotNull(source);
    this.target = Preconditions.checkNotNull(target);
  }

  public SymlinkFileCommand(File source, File target) {
    this.source = Preconditions.checkNotNull(source);
    this.target = Preconditions.checkNotNull(target).getAbsolutePath();
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "ln -s";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    // Always symlink to an absolute path so the symlink is sure to be read correctly.
    return ImmutableList.of("ln", "-f", "-s", source.getAbsolutePath(), target);
  }

}
