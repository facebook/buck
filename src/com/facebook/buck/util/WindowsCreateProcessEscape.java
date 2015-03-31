// This is free and unencumbered software released into the public domain.
//
// Anyone is free to copy, modify, publish, use, compile, sell, or
// distribute this software, either in source code form or as a compiled
// binary, for any purpose, commercial or non-commercial, and by any
// means.
//
// In jurisdictions that recognize copyright laws, the author or authors
// of this software dedicate any and all copyright interest in the
// software to the public domain. We make this dedication for the benefit
// of the public at large and to the detriment of our heirs and
// successors. We intend this dedication to be an overt act of
// relinquishment in perpetuity of all present and future rights to this
// software under copyright law.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
// IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
// OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
// ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
// OTHER DEALINGS IN THE SOFTWARE.
//
// For more information, please refer to <http://unlicense.org/>

package com.facebook.buck.util;

/**
 * Helps quote arguments when launching a process on Windows.
 *
 * Unix's process spawning syscall accepts an array of arguments, but Windows'
 * equivalent accepts a single string and splits the strings according to some
 * different rules.
 */
class WindowsCreateProcessEscape {
  private WindowsCreateProcessEscape() {}

  /**
   * Same as {@link #quote(String)} except appends to a {@code StringBuilder}.
   */
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
   * Given a string X, this function returns a string that, when passed through
   * the Windows implementation of Java's {@link Runtime#exec(String[])} or
   * {@link java.lang.ProcessBuilder}, will appear to the spawned process as X.
   *
   * @param arg
   *    The argument to quote.
   *
   * @return
   *    The quote version of 'arg'.
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
