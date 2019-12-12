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

import javax.annotation.Nullable;

/**
 * Provides methods similar to {@link com.google.common.base.Verify} but throws {@link
 * HumanReadableException} instead. Useful as a lightweight mechanism of verifying user-controlled
 * args/state/etc.
 */
public class UserVerify {

  // The no-message variants aren't provided. A HumanReadableException without a good message makes
  // no sense.

  /** Verifies that value is not null, returns the original object if it is not null. */
  public static <T> T verifyNotNull(@Nullable T value, Object message) {
    if (value != null) {
      return value;
    }
    throw new HumanReadableException(format(message));
  }

  /** Verifies that value is not null, returns the original object if it is not null */
  public static <T> T verifyNotNull(@Nullable T value, String format, Object... args) {
    if (value != null) {
      return value;
    }
    throw new HumanReadableException(format(format, args));
  }

  /** Verifies that state is true. */
  public static void verify(boolean state, Object message) {
    if (!state) {
      throw new HumanReadableException(format(message));
    }
  }

  /** Verifies that state is true. */
  public static void verify(boolean state, String format, Object... args) {
    if (!state) {
      throw new HumanReadableException(format(format, args));
    }
  }

  /** Verifies that argument is true. */
  public static void checkArgument(boolean argument, Object message) {
    // We only throw HumanReadableException. This just helps people migrate without thinking.
    // Also, as these are logically different we're keeping the option to handle them differently.
    verify(argument, message);
  }

  /** Verifies that argument is true. */
  public static void checkArgument(boolean argument, String format, Object... args) {
    // We only throw HumanReadableException. This just helps people migrate without thinking.
    // Also, as these are logically different we're keeping the option to handle them differently.
    verify(argument, format, args);
  }

  private static String format(Object message) {
    return String.valueOf(message);
  }

  private static String format(String format, Object... args) {
    return String.format(format, args);
  }
}
