// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.errors.Unreachable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;

public class StringUtils {

  public final static String LINE_SEPARATOR = System.getProperty("line.separator");

  private final static char[] IDENTIFIER_LETTERS
      = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_".toCharArray();
  private final static int NUMBER_OF_LETTERS = IDENTIFIER_LETTERS.length;

  public enum BraceType {
    PARENS,
    SQUARE,
    TUBORG,
    NONE;

    public String left() {
      switch (this) {
        case PARENS: return "(";
        case SQUARE: return "[";
        case TUBORG: return "{";
        case NONE: return "";
        default: throw new Unreachable("Invalid brace type: " + this);
      }
    }

    public String right() {
      switch (this) {
        case PARENS: return ")";
        case SQUARE: return "]";
        case TUBORG: return "}";
        case NONE: return "";
        default: throw new Unreachable("Invalid brace type: " + this);
      }
    }
  }

  public static void appendNonEmpty(StringBuilder builder, String pre, Object item, String post) {
    if (item == null) {
      return;
    }
    String text = item.toString();
    if (!text.isEmpty()) {
      if (pre != null) {
        builder.append(pre);
      }
      builder.append(text);
      if (post != null) {
        builder.append(post);
      }
    }
  }

  public static StringBuilder appendIndent(StringBuilder builder, String subject, int indent) {
    for (int i = 0; i < indent; i++) {
      builder.append(" ");
    }
    builder.append(subject);
    return builder;
  }

  public static StringBuilder appendLeftPadded(StringBuilder builder, String subject, int width) {
    for (int i = subject.length(); i < width; i++) {
      builder.append(" ");
    }
    builder.append(subject);
    return builder;
  }

  public static StringBuilder appendRightPadded(StringBuilder builder, String subject, int width) {
    builder.append(subject);
    for (int i = subject.length(); i < width; i++) {
      builder.append(" ");
    }
    return builder;
  }

  public static <T> StringBuilder append(StringBuilder builder, Collection<T> collection) {
    return append(builder, collection, ", ", BraceType.PARENS);
  }

  public static <T> StringBuilder append(StringBuilder builder, Collection<T> collection,
      String seperator, BraceType brace) {
    builder.append(brace.left());
    boolean first = true;
    for (T element : collection) {
      if (first) {
        first = false;
      } else {
        builder.append(seperator);
      }
      builder.append(element);
    }
    builder.append(brace.right());
    return builder;
  }

  public static <T> String join(Collection<T> collection, String separator) {
    return join(collection, separator, BraceType.NONE);
  }

  public static String join(String separator, String... strings) {
    return join(Arrays.asList(strings), separator, BraceType.NONE);
  }

  public static <T> String join(Collection<T> collection, String separator, BraceType brace) {
    return join(collection, separator, brace, Object::toString);
  }

  public static <T> String join(Collection<T> collection, String separator, BraceType brace,
      Function<T, String> fn) {
    StringBuilder builder = new StringBuilder();
    append(builder, ListUtils.map(collection, fn), separator, brace);
    return builder.toString();
  }

  public static String lines(String... lines) {
    StringBuilder builder = new StringBuilder();
    for (String line : lines) {
      builder.append(line).append(LINE_SEPARATOR);
    }
    return builder.toString();
  }

  public static String joinLines(String... lines) {
    return join(LINE_SEPARATOR, lines);
  }

  public static String zeroPrefix(int i, int width) {
    return zeroPrefixString(Integer.toString(i), width);
  }

  private static String zeroPrefixString(String s, int width) {
    String prefix = "0000000000000000";
    assert(width <= prefix.length());
    int prefixLength = width - s.length();
    if (prefixLength > 0) {
      StringBuilder builder = new StringBuilder();
      builder.append(prefix, 0, prefixLength);
      builder.append(s);
      return builder.toString();
    } else {
      return s;
    }
  }

  public static String hexString(int value, int width) {
    assert(0 <= width && width <= 8);
    String hex = Integer.toHexString(value);
    if (value >= 0) {
      return "0x" + zeroPrefixString(hex, width);
    } else {
      // Negative ints are always formatted as 8 characters.
      assert(hex.length() == 8);
      return "0x" + hex;
    }
  }

  public static String hexString(long value, int width) {
    assert(0 <= width && width <= 16);
    String hex = Long.toHexString(value);
    if (value >= 0) {
      return "0x" + zeroPrefixString(hex, width);
    } else {
      // Negative longs are always formatted as 16 characters.
      assert(hex.length() == 16);
      return "0x" + hex;
    }
  }

  public static String computeMD5Hash(String name) {
    byte[] digest = null;
    try {
      MessageDigest m = MessageDigest.getInstance("MD5");
      m.reset();
      m.update(name.getBytes());
      digest = m.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
    return Arrays.toString(digest);
  }

  public static String numberToIdentifier(char[] prefix, int nameCount, boolean addSemicolon) {
    // TODO(herhut): Add support for using numbers.
    int size = addSemicolon ? 1 : 0;
    int number = nameCount;
    while (number >= NUMBER_OF_LETTERS) {
      number /= NUMBER_OF_LETTERS;
      size++;
    }
    size++;
    char characters[] = Arrays.copyOfRange(prefix, 0, prefix.length + size);
    number = nameCount;

    int i = prefix.length;
    while (number >= NUMBER_OF_LETTERS) {
      characters[i++] = IDENTIFIER_LETTERS[number % NUMBER_OF_LETTERS];
      number /= NUMBER_OF_LETTERS;
    }
    characters[i++] = IDENTIFIER_LETTERS[number - 1];
    if (addSemicolon) {
      characters[i++] = ';';
    }
    assert i == characters.length;

    return new String(characters);
  }
}
