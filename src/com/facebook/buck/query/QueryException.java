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

package com.facebook.buck.query;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.ExceptionWithHumanReadableMessage;

public class QueryException extends Exception implements ExceptionWithHumanReadableMessage {

  private final DependencyStack dependencyStack;

  public QueryException(String message) {
    super(message);
    dependencyStack = DependencyStack.root();
  }

  public QueryException(String humanReadableFormatString, Object... args) {
    this(String.format(humanReadableFormatString, args));
  }

  public QueryException(Throwable cause, String message) {
    super(message, cause);
    dependencyStack = ExceptionWithHumanReadableMessage.getDependencyStack(cause);
  }

  public QueryException(Throwable cause, String humanReadableFormatString, Object... args) {
    super(String.format(humanReadableFormatString, args), cause);
    dependencyStack = ExceptionWithHumanReadableMessage.getDependencyStack(cause);
  }

  @Override
  public String getHumanReadableErrorMessage() {
    return getLocalizedMessage();
  }

  @Override
  public DependencyStack getDependencyStack() {
    return dependencyStack;
  }
}
