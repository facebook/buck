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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

public final class Escaper {

  /** Utility class: do not instantiate. */
  private Escaper() {}

  private static final CharMatcher BASH_SPECIAL_CHARS = CharMatcher.anyOf("<>|!?*[]$\\(){}\"'`&;=")
      .or(CharMatcher.WHITESPACE);

  /**
   * Bash quoting {@link com.google.common.base.Function Function} which can be passed to
   * {@link com.google.common.collect.Iterables#transform Iterables.transform()}.
   */
  public static final Function<String, String> BASH_ESCAPER = new Function<String, String>() {
    @Override
    public String apply(String s) {
      return Escaper.escapeAsBashString(s);
    }
  };

  /**
   * Quotes a string to be passed to Bash, if necessary. Uses single quotes to prevent variable
   * expansion, `...` evaluation etc.
   * @param str string to quote
   * @return possibly quoted string
   */
  public static String escapeAsBashString(String str) {
    if (BASH_SPECIAL_CHARS.matchesNoneOf(str)) {
      return str;
    } else {
      return new StringBuilder("'").append(str.replace("'", "'\\''")).append("'").toString();
    }
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
    Preconditions.checkNotNull(str);
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

  @VisibleForTesting
  static String hex(char ch) {
    return Integer.toHexString(ch).toUpperCase();
  }
}
