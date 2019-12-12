/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util;

import java.util.Optional;

/**
 * Manages a Duplicate Console so that console actions can be replicated across to another console.
 *
 * <p>Example use case is to duplicate a console that writes to system stdout/stderr to a Log file.
 */
public class DuplicatingConsole extends Console {

  private Optional<Console> duplicateConsole;

  public DuplicatingConsole(Console main) {
    super(main.getVerbosity(), main.getStdOut(), main.getStdErr(), main.getAnsi());
    this.duplicateConsole = Optional.empty();
  }

  public void setDuplicatingConsole(Optional<Console> duplicateConsole) {
    this.duplicateConsole = duplicateConsole;
  }

  @Override
  public void printSuccess(String successMessage) {
    super.printSuccess(successMessage);

    duplicateConsole.ifPresent(c -> c.printSuccess(successMessage));
  }

  @Override
  public void printSuccess(String successMessage, Object... args) {
    super.printSuccess(successMessage, args);
    duplicateConsole.ifPresent(c -> c.printSuccess(successMessage, args));
  }

  @Override
  public void printErrorText(String message) {
    super.printErrorText(message);
    duplicateConsole.ifPresent(c -> c.printErrorText(message));
  }

  @Override
  public void printErrorText(String message, Object... args) {
    super.printErrorText(message, args);
    duplicateConsole.ifPresent(c -> c.printErrorText(message, args));
  }

  @Override
  public void printBuildFailure(String failureMessage) {
    super.printBuildFailure(failureMessage);
    duplicateConsole.ifPresent(c -> c.printBuildFailure(failureMessage));
  }

  @Override
  public void printFailure(String failureMessage) {
    super.printFailure(failureMessage);
    duplicateConsole.ifPresent(c -> c.printFailure(failureMessage));
  }
}
