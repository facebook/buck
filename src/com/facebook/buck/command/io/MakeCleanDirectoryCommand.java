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

package com.facebook.buck.command.io;

import com.facebook.buck.shell.CompositeCommand;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

/**
 * Deletes the directory, if it exists, before creating it.
 * {@link MakeCleanDirectoryCommand} is preferable to {@link MkdirCommand} if the directory may
 * contain many generated files and we want to avoid the case where it could accidentally include
 * generated files from a previous run in Buck.
 * <p>
 * For example, for a directory of {@code .class} files, if the user deletes a {@code .java} file
 * that generated one of the {@code .class} files, the {@code .class} file corresponding to the
 * deleted {@code .java} file should no longer be there when {@code javac} is run again.
 */
public final class MakeCleanDirectoryCommand extends CompositeCommand {

  private final String path;

  public MakeCleanDirectoryCommand(String path) {
    super(ImmutableList.of(
        new RmCommand(path, true /* shouldForceDeletion */, true /* shouldRecurse */),
        new MkdirCommand(path)));
    this.path = path;
  }

  @VisibleForTesting
  public String getPath() {
    return path;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof MakeCleanDirectoryCommand)) {
      return false;
    }
    MakeCleanDirectoryCommand that = (MakeCleanDirectoryCommand)obj;
    return Objects.equal(this.path, that.path);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(path);
  }
}
