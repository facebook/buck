/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.zip;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class UnzipStep extends ShellStep {

  private final Path zipFile;
  private final Path destinationDirectory;

  public UnzipStep(Path zipFile, Path destinationDirectory) {
    this.zipFile = Preconditions.checkNotNull(zipFile);
    this.destinationDirectory = Preconditions.checkNotNull(destinationDirectory);
  }

  @Override
  public String getShortName() {
    return "unzip";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    return ImmutableList.of(
        "unzip",
        projectFilesystem.resolve(zipFile).toString(),
        "-d",
        projectFilesystem.resolve(destinationDirectory).toString());
  }
}
