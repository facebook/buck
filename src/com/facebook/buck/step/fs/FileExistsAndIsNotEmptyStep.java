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

import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ConditionalStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.TriState;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Step that verifies that a file of non-zero length exists at the specified path. The side effect
 * of this step is that its {@link #get()} method will return a boolean indicating whether the
 * file exists. This is designed to be used with a {@link ConditionalStep}.
 */
public class FileExistsAndIsNotEmptyStep extends AbstractExecutionStep
    implements Supplier<Boolean> {

  private final Path pathToFile;
  private TriState fileExistsAndIsNotEmpty = TriState.UNSPECIFIED;

  public FileExistsAndIsNotEmptyStep(Path pathToFile) {
    super("test -s " + pathToFile);
    this.pathToFile = Preconditions.checkNotNull(pathToFile);
  }

  @Override
  public Boolean get() {
    Preconditions.checkState(fileExistsAndIsNotEmpty.isSet());
    return fileExistsAndIsNotEmpty.asBoolean();
  }

  @Override
  public int execute(ExecutionContext context) {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    boolean exists = filesystem.exists(pathToFile.toString());

    try {
      fileExistsAndIsNotEmpty = TriState.forBooleanValue(exists
          && filesystem.getFileSize(pathToFile) > 0);
    } catch (IOException e) {
      context.logError(e, "Failed to get size for file: %s.", pathToFile);
      return 1;
    }

    return 0;
  }

}
