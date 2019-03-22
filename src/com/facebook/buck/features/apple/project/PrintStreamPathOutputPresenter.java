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

package com.facebook.buck.features.apple.project;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * A presenter that outputs paths to a PrintStream. Depending on the mode, the presenter displays
 * absolute/relative/no paths.
 */
class PrintStreamPathOutputPresenter implements PathOutputPresenter {

  private final PrintStream outputStream;
  private final Mode outputMode;
  private final Path repoRoot;

  public PrintStreamPathOutputPresenter(PrintStream outputStream, Mode mode, Path repoRoot) {
    this.outputStream = outputStream;
    this.outputMode = mode;
    this.repoRoot = repoRoot;
  }

  @Override
  public void present(String prefix, Path path) {
    Path prettyPath;
    if (outputMode == Mode.FULL) {
      prettyPath = repoRoot.resolve(path).normalize();
    } else if (outputMode == Mode.SIMPLE) {
      prettyPath = path;
    } else {
      return;
    }
    outputStream.printf("%s %s%n", prefix, prettyPath);
  }
}
