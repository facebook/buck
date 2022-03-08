/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.java;

/**
 * Helps quote arguments when launching a process on Windows.
 *
 * <p>Unix's process spawning syscall accepts an array of arguments, but Windows' equivalent accepts
 * a single string and splits the strings according to some different rules.
 */
public class WindowsCreateProcessEscape {
  private WindowsCreateProcessEscape() {}

  /** Same as {@link #quote(String)} except appends to a {@code StringBuilder}. */
  public static void quote(StringBuilder buf, String arg) {
    if (!mightNeedQuotes(arg)) {
      buf.append(arg);
      return;
    }
    buf.append('"');

    // The length of the current run of backslashes.
    int nPending = 0;

    for (int i = 0; i < arg.length(); i++) {
      char c = arg.charAt(i);

      if (c == '\\') {
        nPending++;
      } else {
        if (c == '"') {
          // Escape all the backslashes we've collected up till now.
          for (int j = 0; j < nPending; j++) {
            buf.append('\\');
          }
          // Escape the quote.
          buf.append('\\');
        }
        nPending = 0;
      }

      buf.append(c);
    }

    // Escape all the backslashes that appear before the final closing quote.
    for (int j = 0; j < nPending; j++) {
      buf.append('\\');
    }

    buf.append('"');
  }

  /**
   * Given a string X, this function returns a string that, when passed through the Windows
   * implementation of Java's {@link Runtime#exec(String[])} or {@link ProcessBuilder}, will appear
   * to the spawned process as X.
   *
   * @param arg The argument to quote.
   * @return The quote version of 'arg'.
   */
  public static String quote(String arg) {
    StringBuilder buf = new StringBuilder(2 + arg.length() * 2);
    quote(buf, arg);
    return buf.toString();
  }

  private static boolean mightNeedQuotes(String arg) {
    if (arg.length() == 0) {
      return true;
    }

    for (int i = 0; i < arg.length(); i++) {
      char c = arg.charAt(i);
      if (c == '"' || c == ' ' || c == '\t') {
        return true;
      }
    }

    return false;
  }
}
