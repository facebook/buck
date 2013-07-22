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

package com.facebook.buck.step.fs;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class SymlinkFileStep extends ShellStep {

  private final String source;
  private final String target;

  /**
   * Both {@code source} and {@code target} must refer to a file (as opposed to a directory).
   */
  public SymlinkFileStep(String source, String target) {
    this.source = Preconditions.checkNotNull(source);
    this.target = Preconditions.checkNotNull(target);
  }

  @Override
  public String getShortName() {
    return "symlink_file";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    Function<String, String> pathRelativizer = context.getProjectFilesystem().getPathRelativizer();
    // Always symlink to an absolute path so the symlink is sure to be read correctly.
    return ImmutableList.of(
        "ln",
        "-f",
        "-s",
        pathRelativizer.apply(source),
        pathRelativizer.apply(target));
  }

}
