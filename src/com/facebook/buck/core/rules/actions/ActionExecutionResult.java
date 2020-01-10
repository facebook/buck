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

package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/** Represents the result of executing the {@link Action} */
public interface ActionExecutionResult {

  /** @return the stdout of the actions ran if any */
  Optional<String> getStdOut();

  /** @return the stderr of the actions ran if any */
  Optional<String> getStdErr();

  ImmutableList<String> getCommand();

  static ActionExecutionSuccess success(
      Optional<String> stdOut, Optional<String> stdErr, ImmutableList<String> command) {
    return ImmutableActionExecutionSuccess.of(stdOut, stdErr, command);
  }

  static ActionExecutionFailure failure(
      Optional<String> stdOut,
      Optional<String> stdErr,
      ImmutableList<String> command,
      Optional<Exception> exception) {
    return ImmutableActionExecutionFailure.of(stdOut, stdErr, command, exception);
  }

  /** A successful action execution */
  @BuckStyleValue
  interface ActionExecutionSuccess extends ActionExecutionResult {
    @Override
    Optional<String> getStdOut();

    @Override
    Optional<String> getStdErr();

    @Override
    ImmutableList<String> getCommand();
  }

  /** execution that failed */
  @BuckStyleValue
  interface ActionExecutionFailure extends ActionExecutionResult {
    @Override
    Optional<String> getStdOut();

    @Override
    Optional<String> getStdErr();

    @Override
    ImmutableList<String> getCommand();

    Optional<Exception> getException();
  }
}
