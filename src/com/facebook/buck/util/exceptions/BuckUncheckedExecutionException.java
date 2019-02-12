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

package com.facebook.buck.util.exceptions;

import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Allows wrapping a checked exception into an unchecked exception in a way that Buck understands.
 *
 * <p>Users should prefer {@code throw new BuckUncheckedExecutionException(exception);} to {@code
 * throw new RuntimeException(exception);} for throwing arbitrary checked exceptions as unchecked.
 * Even better would be to specify that the function throws BuckExecutionException.
 */
public class BuckUncheckedExecutionException extends RuntimeException
    implements ExceptionWithContext, WrapsException {
  private @Nullable final String context;

  public BuckUncheckedExecutionException(String message) {
    this(message, null);
  }

  public BuckUncheckedExecutionException(String message, @Nullable String context) {
    super(message);
    this.context = context;
  }

  public BuckUncheckedExecutionException(
      String message, @Nullable Throwable cause, @Nullable String context) {
    super(message, cause);
    this.context = context;
  }

  public BuckUncheckedExecutionException(Throwable cause) {
    this(cause, null);
  }

  public BuckUncheckedExecutionException(Throwable cause, @Nullable String context) {
    super(cause);
    this.context = context;
  }

  @Override
  public String getMessage() {
    StringBuilder builder = new StringBuilder();
    builder.append(context);
    String parentMessage = super.getMessage();
    if (parentMessage != null && !parentMessage.isEmpty()) {
      builder.append(": ");
      builder.append(parentMessage);
    }
    return builder.toString();
  }

  public BuckUncheckedExecutionException(Throwable cause, String format, Object... args) {
    this(cause, String.format(format, args));
  }

  @Override
  public Optional<String> getContext() {
    return Optional.ofNullable(context);
  }
}
