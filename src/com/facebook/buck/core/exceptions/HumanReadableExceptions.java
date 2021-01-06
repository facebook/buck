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

package com.facebook.buck.core.exceptions;

/**
 * Utilities to work with {@link HumanReadableException} and {@link
 * ExceptionWithHumanReadableMessage}.
 */
public class HumanReadableExceptions {

  private HumanReadableExceptions() {}

  /**
   * Rethrow exception if it is runtime exception and if it implements {@link
   * ExceptionWithHumanReadableMessage}.
   */
  public static void throwIfHumanReadableUnchecked(Throwable e) {
    if (e instanceof RuntimeException && e instanceof ExceptionWithHumanReadableMessage) {
      throw (RuntimeException) e;
    }
  }
}
