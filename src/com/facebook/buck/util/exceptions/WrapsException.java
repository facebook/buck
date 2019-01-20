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

/**
 * This interface indicates that an exception is just used to wrap another and isn't the true root
 * cause of the problem. This is used to present the exception in a more user-friendly way.
 */
public interface WrapsException {

  /**
   * Unwraps exception to the root cause.
   *
   * <p>TODO(cjhopman): This behavior is slightly different than that in ErrorLogger as it doesn't
   * unwrap the non-WrapsException exceptions that ErrorLogger does.
   */
  static Throwable getRootCause(Throwable throwable) {
    Throwable cause = throwable;
    while (cause instanceof WrapsException) {
      cause = cause.getCause();
    }
    return cause;
  }
}
