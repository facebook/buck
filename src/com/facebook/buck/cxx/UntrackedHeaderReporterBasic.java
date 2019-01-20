/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.cxx;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Produces the error message when an untracked header is detected.
 *
 * <p>When preprocessing/compiling, we may encounter some headers that are missing from BUCK file.
 * If the file is not whitelisted this class generates the message for this error when we don't have
 * enough dependency information to know where the header is being used.
 */
class UntrackedHeaderReporterBasic implements UntrackedHeaderReporter {
  private final Path inputPath;
  private final ProjectFilesystem filesystem;

  public UntrackedHeaderReporterBasic(ProjectFilesystem filesystem, Path inputPath) {
    this.filesystem = filesystem;
    this.inputPath = inputPath;
  }

  @Override
  public boolean isDetailed() {
    return false;
  }

  @Override
  public String getErrorReport(Path header) {
    String errorMessage =
        String.format(
            "%s: included an untracked header: %n%s",
            prettyPrintFileName(inputPath, false), prettyPrintFileName(header, false));
    return errorMessage;
  }

  private String prettyPrintFileName(Path fileName, boolean quote) {
    Optional<Path> repoRelativePath = filesystem.getPathRelativeToProjectRoot(fileName);
    String prettyFilename = repoRelativePath.orElse(fileName).toString();
    if (quote) {
      prettyFilename = String.format("\"%s\"", prettyFilename);
    }
    return prettyFilename;
  }
}
