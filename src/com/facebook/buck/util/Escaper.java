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
import com.google.common.base.Preconditions;
import java.io.File;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.function.Function;

public final class Escaper {

  private static final char MAKEFILE_ESCAPE_CHAR = '\\';

  /** Utility class: do not instantiate. */
  private Escaper() {}

  /** The quoting style to use when escaping. */
  public enum Quoter {
    SINGLE {
      @Override
      public String quote(String str) {
        return '\'' + str.replace("\'", "'\\''") + '\'';
      }
    },
    DOUBLE {
      @Override
      public String quote(String str) {
        return '"' + str.replace("\"", "\\\"") + '"';
      }
    },
    DOUBLE_WINDOWS_JAVAC {
      @Override
      public String quote(String str) {
        return '"' + str.replace("\\", "\\\\") + '"';
      }
    };

    /** @return the string with this quoting style applied. */
    public abstract String quote(String str);
  }

  /**
   * Escapes the special characters identified the {@link CharMatcher}, using single quotes.
   *
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
   * @return a escaper function using the given quote style and escaping characters determined by
   *     the given matcher.
   */
  public static Function<String, String> escaper(Quoter quoter, CharMatcher matcher) {
    return input -> escape(quoter, matcher, input);
  }

  public static Function<String, String> javacEscaper() {
    if (Platform.detect() == Platform.WINDOWS) {
      return Escaper.escaper(
          Quoter.DOUBLE_WINDOWS_JAVAC, CharMatcher.anyOf("#'").or(CharMatcher.whitespace()));
    } else {
      return Escaper.escaper(
          Escaper.Quoter.DOUBLE, CharMatcher.anyOf("#\"'").or(CharMatcher.whitespace()));
    }
  }

  private static final CharMatcher BASH_SPECIAL_CHARS =
      CharMatcher.anyOf("<>|!?*[]$\\(){}\"'`&;=").or(CharMatcher.whitespace());

  /**
   * Bash quoting {@link com.google.common.base.Function Function} which can be passed to {@link
   * com.google.common.collect.Iterables#transform Iterables.transform()}.
   */
  public static final Function<String, String> BASH_ESCAPER =
      escaper(Quoter.SINGLE, BASH_SPECIAL_CHARS);

  /**
   * CreateProcess (Windows) quoting {@link com.google.common.base.Function Function} which can be
   * passed to {@link com.google.common.collect.Iterables#transform Iterables.transform()}.
   */
  public static final Function<String, String> CREATE_PROCESS_ESCAPER =
      WindowsCreateProcessEscape::quote;

  /**
   * Platform-aware shell quoting {@link com.google.common.base.Function Function} which can be
   * passed to {@link com.google.common.collect.Iterables#transform Iterables.transform()}
   * TODO(sdwilsh): get proper cmd.EXE escaping implemented on Windows
   */
  public static final Function<String, String> SHELL_ESCAPER =
      Platform.detect() == Platform.WINDOWS ? CREATE_PROCESS_ESCAPER : BASH_ESCAPER;

  /**
   * Escaper for argfiles for clang and gcc.
   *
   * <p>Based on the following docs in the gcc manual:
   *
   * <p>{@literal @file} Read command-line options from file. The options read are inserted in place
   * of the original {@literal @file} option. If file does not exist, or cannot be read, then the
   * option will be treated literally, and not removed.
   *
   * <p>Options in file are separated by whitespace. A whitespace character may be included in an
   * option by surrounding the entire option in either single or double quotes. Any character
   * (including a backslash) may be included by prefixing the character to be included with a
   * backslash. The file may itself contain additional {@literal @file} options; any such options
   * will be processed recursively.
   */
  public static final Function<String, String> ARGFILE_ESCAPER =
      escaper(Quoter.DOUBLE, CharMatcher.anyOf("\"\\").or(CharMatcher.whitespace()));

  /**
   * Quotes a string to be passed to the shell, if necessary. This works for the appropriate shell
   * regardless of the platform it is run on.
   *
   * @param str string to escape
   * @return possibly escaped string
   */
  public static String escapeAsShellString(String str) {
    return SHELL_ESCAPER.apply(str);
  }

  /**
   * Quotes a string to be passed to Bash, if necessary. Uses single quotes to prevent variable
   * expansion, `...` evaluation etc.
   *
   * @param str string to quote
   * @return possibly quoted string
   */
  public static String escapeAsBashString(String str) {
    if (Platform.detect() == Platform.WINDOWS) {
      return CREATE_PROCESS_ESCAPER.apply(str);
    } else {
      return escape(Quoter.SINGLE, BASH_SPECIAL_CHARS, str);
    }
  }

  public static String escapeAsBashString(Path path) {
    return escapeAsBashString(path.toString());
  }

  // Adapted from org.apache.commons.lang.StringEscapeUtils

  /**
   * @return a double-quoted string with metacharacters and quotes escaped with a backslash;
   *     non-ASCII characters escaped as &#92;u
   */
  public static String escapeAsPythonString(String str) {
    // assume that at most a quarter of the characters will be escaped. This number is completely
    // random and not based on any real data, so improvements are very welcome.
    StringBuilder builder = new StringBuilder(str.length() + (str.length() >> 2));
    builder.append('"');
    for (int i = 0; i < str.length(); ++i) {
      char ch = str.charAt(i);
      // Handle Unicode.
      if (ch > 0xfff) {
        builder.append("\\u").append(hex(ch));
      } else if (ch > 0xff) {
        builder.append("\\u0").append(hex(ch));
      } else if (ch > 0x7f) {
        builder.append("\\u00").append(hex(ch));
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
              builder.append("\\u00").append(hex(ch));
            } else {
              builder.append("\\u000").append(hex(ch));
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

  private static boolean shouldEscapeMakefileString(String escapees, String blob, int index) {

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

  /** @return quoted string if it contains at least one character that needs to be escaped */
  public static String escapeWithQuotesAsMakefileValueString(String str) {
    String result = escapeAsMakefileValueString(str);
    if (str.indexOf('#') != -1) {
      result = Quoter.DOUBLE.quote(result);
    }
    return result;
  }

  /**
   * Escapes forward slashes in a Path as a String that is safe to consume with other tools (such as
   * gcc). On Unix systems, this is equivalent to {@link java.nio.file.Path Path.toString()}.
   *
   * @param path the Path to escape
   * @return the escaped Path
   */
  public static String escapePathForCIncludeString(Path path) {
    if (File.separatorChar != '\\') {
      return path.toString();
    }
    StringBuilder result = new StringBuilder();
    if (path.startsWith(File.separator)) {
      result.append("\\\\");
    }
    for (Iterator<Path> iterator = path.iterator(); iterator.hasNext(); ) {
      result.append(iterator.next());
      if (iterator.hasNext()) {
        result.append("\\\\");
      }
    }
    if (path.getNameCount() > 0 && path.endsWith(File.separator)) {
      result.append("\\\\");
    }
    return result.toString();
  }

  @VisibleForTesting
  static String hex(char ch) {
    return Integer.toHexString(ch).toUpperCase();
  }

  /**
   * Unescape a path string obtained from preprocessor output, as in: <a
   * href="https://gcc.gnu.org/onlinedocs/cpp/Preprocessor-Output.html">
   * https://gcc.gnu.org/onlinedocs/cpp/Preprocessor-Output.html </a>.
   *
   * @see #decodeNumericEscape(StringBuilder, String, int, int, int, int)
   */
  public static String unescapeLineMarkerPath(String escaped) {
    StringBuilder ret = new StringBuilder();
    for (int i = 0; i < escaped.length(); /* i incremented below */ ) {
      // consume character, advance index
      char c = escaped.charAt(i);
      i++;

      if (c != '\\') {
        ret.append(c);
      } else {
        // So sayeth the GCC docs:
        // "filename will never contain any non-printing characters; they are replaced with octal
        // escape sequences."  -- https://gcc.gnu.org/onlinedocs/cpp/Preprocessor-Output.html

        if (i >= escaped.length()) {
          throw new IllegalArgumentException("malformed string: ends with single backslash");
        }

        c = escaped.charAt(i); // peek (don't consume) next char

        switch (c) {
            // standard escapes: http://en.cppreference.com/w/cpp/language/escape
          case '\\':
          case '"':
          case '\'':
          case '?':
            ret.append(c);
            i++; // consume it
            break;

          case '0':
          case '1':
          case '2':
          case '3':
          case '4':
          case '5':
          case '6':
          case '7':
            i = decodeNumericEscape(ret, escaped, i, /*maxCodeLength=*/ 3, /*base*/ 8);
            break;

          case 'x':
            i = decodeNumericEscape(ret, escaped, i, /*maxCodeLength=*/ 2, /*base*/ 16);
            break;

          case 'u':
            i =
                decodeNumericEscape(
                    ret, escaped, i, /*maxCodeLength=*/ 4, /*base*/ 16, /*maxCodes*/ 2);
            break;

          default:
            throw new IllegalArgumentException("malformed string: bad char in escape seq: " + c);
        }
      }
    }
    return ret.toString();
  }

  /**
   * Decode a numeric escape as explained in this page: <a
   * href="http://en.cppreference.com/w/cpp/language/escape">
   * http://en.cppreference.com/w/cpp/language/escape </a>. The pointed-to substring shouldn't
   * contain the leading backslash + optional 'x' or 'u'.
   *
   * @param out receives decoded characters
   * @param escaped the string containing the escape sequence
   * @param pos starting index of escape (but after the backslash)
   * @param base number base, e.g. 8 for octal, or 16 for hex or unicode.
   * @param maxCodes maximum number of sequences of escape numbers to decode. This is mainly to
   *     support unicode escape sequences which might represent one or two characters.
   * @return position to first character just after the consumed numeric code. Is number of consumed
   *     code bytes + {@code pos} argument.
   */
  public static int decodeNumericEscape(
      StringBuilder out, String escaped, int pos, int maxCodeLength, int base, int maxCodes) {
    String table = "0123456789abcdef";
    for (int code = 0; code < maxCodes; code++) {
      char c = 0;
      boolean valid = false;
      for (int i = 0; i < maxCodeLength && pos < escaped.length(); i++) {
        int digit = table.indexOf(Character.toLowerCase(escaped.charAt(pos)));
        if (digit == -1 || digit >= base) {
          break;
        }

        // "digit" is a valid value for the digit read, in the range [0, base).
        // Now we can increment the position, consuming that digit.
        pos++;

        c = (char) ((c * base) + digit);
        valid = true;
      }

      if (valid) {
        out.append(c);
      }
    }

    return pos;
  }

  /**
   * Call {@link #decodeNumericEscape(StringBuilder, String, int, int, int, int)} to parse at most
   * one escaped character; i.e. calls that method with {@code maxCodes = 1}.
   */
  public static int decodeNumericEscape(
      StringBuilder out, String escaped, int pos, int maxCodeLength, int base) {
    return decodeNumericEscape(out, escaped, pos, maxCodeLength, base, 1);
  }
}
