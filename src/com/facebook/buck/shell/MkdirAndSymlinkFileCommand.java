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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import java.io.File;

/**
 * Ensures the directory of the target path is created before a file is symlinked to it.
 */
public final class MkdirAndSymlinkFileCommand extends CompositeCommand {

  private final File source;
  private final File target;

  public MkdirAndSymlinkFileCommand(String source, String target) {
    this(new File(source), target);
  }

  public MkdirAndSymlinkFileCommand(File source, String target) {
    this(source, new File(target));
  }

  public MkdirAndSymlinkFileCommand(File source, File target) {
    super(ImmutableList.of(
        new MkdirCommand(target.getParent()),
        new SymlinkFileCommand(source, target.getAbsolutePath())
        ));
    this.source = source;
    this.target = target;
  }

  @VisibleForTesting
  public File getSource() {
    return source;
  }

  @VisibleForTesting
  public File getTarget() {
    return target;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof MkdirAndSymlinkFileCommand)) {
      return false;
    }
    MkdirAndSymlinkFileCommand that = (MkdirAndSymlinkFileCommand)obj;
    return Objects.equal(this.source, that.source)
        && Objects.equal(this.target, that.target);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.source, this.target);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(MkdirAndSymlinkFileCommand.class)
        .add("source", source)
        .add("target", target)
        .toString();
  }
}
