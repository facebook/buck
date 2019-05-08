/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.core.exceptions;

import java.util.Optional;
import javax.annotation.Nullable;

public class BuckExecutionException extends Exception
    implements ExceptionWithContext, WrapsException {
  private @Nullable final String context;

  public BuckExecutionException(String message) {
    this(message, null);
  }

  public BuckExecutionException(String message, @Nullable String context) {
    super(message);
    this.context = context;
  }

  public BuckExecutionException(
      String message, @Nullable Throwable cause, @Nullable String context) {
    super(message, cause);
    this.context = context;
  }

  public BuckExecutionException(Throwable cause) {
    this(cause, null);
  }

  public BuckExecutionException(Throwable cause, @Nullable String context) {
    super(cause);
    this.context = context;
  }

  @Override
  public Optional<String> getContext() {
    return Optional.ofNullable(context);
  }
}
