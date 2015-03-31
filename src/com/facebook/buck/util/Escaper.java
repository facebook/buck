/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

public final class Escaper {

  private static final char POWER_SHELL_ESCAPE_CHAR = '`';
  private static final String POWER_SHELL_SPECIAL_CHARS = "$`;\"'&<>(){}\\/| ";
  public static final Function<String, String> POWER_SHELL_ESCAPER =
      new Function<String, String>() {
        @Override
        public String apply(String input) {
          return escapeAsPowerShellString(input);
        }
      };

  private static final char MAKEFILE_ESCAPE_CHAR = '\\';
  public static final Function<String, String> MAKEFILE_VALUE_ESCAPER =
      new Function<String, String>() {
        @Override
        public String apply(String input) {
          return escapeAsMakefileValueString(input);
        }
      };

  /** Utility class: do not instantiate. */
  private Escaper() {}

  /**
   * The quoting style to use when escaping.
   */
  public static enum Quoter {

    SINGLE('\'') {
      @Override
      public String quote(String str) {
        return '\'' + str.replace("\'", "'\\''") + '\'';
      }
    },
    DOUBLE('"') {
      @Override
      public String quote(String str) {
        return '"' + str.replace("\"", "\\\"") + '"';
      }
    }
    ;

    private final char value;

    private Quoter(char value) {
      this.value = value;
    }

    /**
     * @return the string with this quoting style applied.
     */
    public abstract String quote(String str);

    /**
     * @return the raw quote character.
     */
    public char getValue() {
      return value;
    }

  }

  /**
   * Escapes the special characters identified the {@link CharMatcher}, using single quotes.
   * @param matcher identifies characters to be escaped
   * @param str string to quote
   * @return possibly quoted string
   */
  public static String escape(Quoter quoter, CharMatcher matcher, String str) {
    if (matcher.matchesAnyOf(str) || str.isEmpty()) {
      return quoter.quote(str);
    } else {
      return str;
    }
  }

  /**
   * @return a escaper function using the given quote style and escaping characters determined
   *     by the given matcher.
   */
  public static Function<String, String> escaper(
      final Quoter quoter,
      final CharMatcher matcher) {
    return new Function<String, String>() {
      @Override
      public String apply(String input) {
        return escape(quoter, matcher, input);
      }
    };
  }

  private static final CharMatcher BASH_SPECIAL_CHARS =
      CharMatcher
          .anyOf("<>|!?*[]$\\(){}\"'`&;=")
          .or(CharMatcher.WHITESPACE);

  /**
   * Bash quoting {@link com.google.common.base.Function Function} which can be passed to
   * {@link com.google.common.collect.Iterables#transform Iterables.transform()}.
   */
  public static final Function<String, String> BASH_ESCAPER =
      escaper(Quoter.SINGLE, BASH_SPECIAL_CHARS);

  /**
   * CreateProcess (Windows) quoting {@link com.google.common.base.Function Function} which can be
   * passed to {@link com.google.common.collect.Iterables#transform Iterables.transform()}.
   */
  public static final Function<String, String> CREATE_PROCESS_ESCAPER =
      new Function<String, String>() {
        @Override
        public String apply(String input) {
          return WindowsCreateProcessEscape.quote(input);
        }
      };

  public static final Function<String, String> JAVA_ESCAPER =
      new Function<String, String>() {
        @Override
        public String apply(String input) {
          return escapeAsJavaString(input);
        }
      };

  /**
   * Platform-aware shell quoting {@link com.google.common.base.Function Function} which can be
   * passed to {@link com.google.common.collect.Iterables#transform Iterables.transform()}
   * TODO(sdwilsh): get proper cmd.EXE escaping implemented on Windows
   */
  public static final Function<String, String> SHELL_ESCAPER =
      Platform.detect() == Platform.WINDOWS ? CREATE_PROCESS_ESCAPER : BASH_ESCAPER;

  /**
   * Quotes a string to be passed to Bash, if necessary. Uses single quotes to prevent variable
   * expansion, `...` evaluation etc.
   * @param str string to quote
   * @return possibly quoted string
   */
  public static String escapeAsBashString(String str) {
    return escape(Quoter.SINGLE, BASH_SPECIAL_CHARS, str);
  }

  public static String escapeAsBashString(Path path) {
    return escapeAsBashString(path.toString());
  }

  public static String escapeAsXmlString(String str) {
    return str.replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll("\\\"", "&quot;")
        .replaceAll("\\\'", "&apos;");
  }

  // Adapted from org.apache.commons.lang.StringEscapeUtils

  /**
   * @return a double-quoted string with metacharacters and quotes escaped with
   *         a backslash; non-ASCII characters escaped as &#92;u
   */
  public static String escapeAsPythonString(String str) {
    StringBuilder builder = new StringBuilder();
    builder.append('"');
    for (Character ch : str.toCharArray()) {
      // Handle Unicode.
      if (ch > 0xfff) {
        builder.append("\\u" + hex(ch));
      } else if (ch > 0xff) {
        builder.append("\\u0" + hex(ch));
      } else if (ch > 0x7f) {
        builder.append("\\u00" + hex(ch));
      } else if (ch < 32) {
        switch (ch) {
        case '\b':
          builder.append('\\');
          builder.append('b');
          break;
        case '\n':
          builder.append('\\');
          builder.append('n');
          break;
        case '\t':
          builder.append('\\');
          builder.append('t');
          break;
        case '\f':
          builder.append('\\');
          builder.append('f');
          break;
        case '\r':
          builder.append('\\');
          builder.append('r');
          break;
        default:
          if (ch > 0xf) {
            builder.append("\\u00" + hex(ch));
          } else {
            builder.append("\\u000" + hex(ch));
          }
          break;
        }
      } else {
        switch (ch) {
        case '\'':
          builder.append('\\');
          builder.append('\'');
          break;
        case '"':
          builder.append('\\');
          builder.append('"');
          break;
        case '\\':
          builder.append('\\');
          builder.append('\\');
          break;
        default:
          builder.append(ch);
          break;
        }
      }
    }
    builder.append('"');
    return builder.toString();
  }

  public static String escapeAsJavaString(String str) {
    // Until we start to find special cases where Java and Python differ, just use the same logic.
    return escapeAsPythonString(str);
  }

  /**
   * @return an escaped string ready to be passed to powershell.
   */
  public static String escapeAsPowerShellString(String str) {
    StringBuilder builder = new StringBuilder();
    for (char c : str.toCharArray()) {
      if (POWER_SHELL_SPECIAL_CHARS.indexOf(c) != -1) {
        builder.append(POWER_SHELL_ESCAPE_CHAR);
      }
      builder.append(c);
    }
    return builder.toString();
  }

  private static boolean shouldEscapeMakefileString(
      String escapees,
      String blob,
      int index) {

    Preconditions.checkArgument(blob.length() > index);

    for (int i = index; i < blob.length(); i++) {
      if (escapees.indexOf(blob.charAt(i)) != -1) {
        return true;
      }
      if (blob.charAt(i) != MAKEFILE_ESCAPE_CHAR) {
        return false;
      }
    }

    return true;
  }

  private static String escapeAsMakefileString(String escapees, String str) {
    StringBuilder builder = new StringBuilder();

    for (int i = 0; i < str.length(); i++) {
      if (shouldEscapeMakefileString(escapees, str, i)) {
        builder.append(MAKEFILE_ESCAPE_CHAR);
      }
      builder.append(str.charAt(i));
    }

    return builder.toString().replace("$", "$$");
  }

  /**
   * @return an escaped string suitable for use in a GNU makefile on the right side of a variable
   *     assignment.
   */
  public static String escapeAsMakefileValueString(String str) {
    return escapeAsMakefileString("#", str);
  }

  @VisibleForTesting
  static String hex(char ch) {
    return Integer.toHexString(ch).toUpperCase();
  }

}
