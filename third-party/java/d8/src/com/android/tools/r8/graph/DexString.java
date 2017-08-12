// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.naming.NamingLens;
import java.io.UTFDataFormatException;
import java.util.Arrays;

public class DexString extends IndexedDexItem implements PresortedComparable<DexString> {

  public static final DexString[] EMPTY_ARRAY = new DexString[]{};

  public final int size;  // size of this string, in UTF-16
  public final byte[] content;

  DexString(int size, byte[] content) {
    this.size = size;
    this.content = content;
  }

  public DexString(String string) {
    this.size = string.length();
    this.content = encode(string);
  }

  @Override
  public int computeHashCode() {
    return size * 7 + Arrays.hashCode(content);
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexString) {
      DexString o = (DexString) other;
      return size == o.size && Arrays.equals(content, o.content);
    }
    return false;
  }

  @Override
  public String toString() {
    try {
      return decode();
    } catch (UTFDataFormatException e) {
      throw new RuntimeException("Bad format", e);
    }
  }

  public int numberOfLeadingSquareBrackets() {
    int result = 0;
    while (content.length > result && content[result] == ((byte) '[')) {
      result++;
    }
    return result;
  }

  // Inspired from /dex/src/main/java/com/android/dex/Mutf8.java
  private String decode() throws UTFDataFormatException {
    int s = 0;
    int p = 0;
    char[] out = new char[size];
    while (true) {
      char a = (char) (content[p++] & 0xff);
      if (a == 0) {
        return new String(out, 0, s);
      }
      out[s] = a;
      if (a < '\u0080') {
        s++;
      } else if ((a & 0xe0) == 0xc0) {
        int b = content[p++] & 0xff;
        if ((b & 0xC0) != 0x80) {
          throw new UTFDataFormatException("bad second byte");
        }
        out[s++] = (char) (((a & 0x1F) << 6) | (b & 0x3F));
      } else if ((a & 0xf0) == 0xe0) {
        int b = content[p++] & 0xff;
        int c = content[p++] & 0xff;
        if (((b & 0xC0) != 0x80) || ((c & 0xC0) != 0x80)) {
          throw new UTFDataFormatException("bad second or third byte");
        }
        out[s++] = (char) (((a & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F));
      } else {
        throw new UTFDataFormatException("bad byte");
      }
    }
  }

  // Inspired from /dex/src/main/java/com/android/dex/Mutf8.java
  private static int countBytes(String string) {
    int result = 0;
    for (int i = 0; i < string.length(); ++i) {
      char ch = string.charAt(i);
      if (ch != 0 && ch <= 127) { // U+0000 uses two bytes.
        ++result;
      } else if (ch <= 2047) {
        result += 2;
      } else {
        result += 3;
      }
      assert result > 0;
    }
    // We need an extra byte for the terminating '0'.
    return result + 1;
  }

  // Inspired from /dex/src/main/java/com/android/dex/Mutf8.java
  private static byte[] encode(String string) {
    byte[] result = new byte[countBytes(string)];
    int offset = 0;
    for (int i = 0; i < string.length(); i++) {
      char ch = string.charAt(i);
      if (ch != 0 && ch <= 127) { // U+0000 uses two bytes.
        result[offset++] = (byte) ch;
      } else if (ch <= 2047) {
        result[offset++] = (byte) (0xc0 | (0x1f & (ch >> 6)));
        result[offset++] = (byte) (0x80 | (0x3f & ch));
      } else {
        result[offset++] = (byte) (0xe0 | (0x0f & (ch >> 12)));
        result[offset++] = (byte) (0x80 | (0x3f & (ch >> 6)));
        result[offset++] = (byte) (0x80 | (0x3f & ch));
      }
    }
    result[offset] = 0;
    return result;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    indexedItems.addString(this);
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  @Override
  public int compareTo(DexString other) {
    return sortedCompareTo(other.getSortedIndex());
  }

  @Override
  public int slowCompareTo(DexString other) {
    // Compare the bytes, as comparing UTF-8 encoded strings as strings of unsigned bytes gives
    // the same result as comparing the corresponding Unicode strings lexicographically by
    // codepoint. The only complication is the MUTF-8 encoding have the two byte encoding c0 80 of
    // the null character (U+0000) to allow embedded null characters.
    // Supplementary characters (unicode code points above U+FFFF) are always represented as
    // surrogate pairs and are compared using UTF-16 code units as per Java string semantics.
    int index = 0;
    while (true) {
      char b1 = (char) (content[index] & 0xff);
      char b2 = (char) (other.content[index] & 0xff);
      int diff = b1 - b2;
      if (diff != 0) {
        // Check if either string ends here.
        if (b1 == 0 || b2 == 0) {
          return diff;
        }
        // If either of the strings have the null character starting here, the null character
        // sort lowest.
        if ((b1 == 0xc0 && (content[index + 1] & 0xff) == 0x80) ||
            (b2 == 0xc0 && (other.content[index + 1] & 0xff) == 0x80)) {
          return b1 == 0xc0 && (content[index + 1] & 0xff) == 0x80 ? -1 : 1;
        }
        return diff;
      } else if (b1 == 0) {
        // Reached the end in both strings.
        return 0;
      }
      index++;
    }
  }

  @Override
  public int slowCompareTo(DexString other, NamingLens lens) {
    // The naming lens cannot affect strings.
    return slowCompareTo(other);
  }

  @Override
  public int layeredCompareTo(DexString other, NamingLens lens) {
    // Strings have no subparts that are already sorted.
    return slowCompareTo(other);
  }

  private boolean isSimpleNameChar(char ch) {
    if (ch >= 'A' && ch <= 'Z') {
      return true;
    }
    if (ch >= 'a' && ch <= 'z') {
      return true;
    }
    if (ch >= '0' && ch <= '9') {
      return true;
    }
    if (ch == '$' || ch == '-' || ch == '_') {
      return true;
    }
    if (ch >= 0x00a1 && ch <= 0x1fff) {
      return true;
    }
    if (ch >= 0x2010 && ch <= 0x2027) {
      return true;
    }
    if (ch >= 0x2030 && ch <= 0xd7ff) {
      return true;
    }
    if (ch >= 0xe000 && ch <= 0xffef) {
      return true;
    }
    if (ch >= 0x10000 && ch <= 0x10ffff) {
      return true;
    }
    return false;
  }

  private boolean isValidClassDescriptor(String string) {
    if (string.length() < 3
        || string.charAt(0) != 'L'
        || string.charAt(string.length() - 1) != ';') {
      return false;
    }
    if (string.charAt(1) == '/' || string.charAt(string.length() - 2) == '/') {
      return false;
    }
    for (int i = 1; i < string.length() - 1; i++) {
      char ch = string.charAt(i);
      if (ch == '/') {
        continue;
      }
      if (isSimpleNameChar(ch)) {
        continue;
      }
      return false;
    }
    return true;
  }

  private boolean isValidMethodName(String string) {
    if (string.isEmpty()) {
      return false;
    }
    // According to https://source.android.com/devices/tech/dalvik/dex-format#membername
    // '<' SimpleName '>' should be valid. However, the art verifier only allows <init>
    // and <clinit> which is reasonable.
    if ((string.charAt(0) == '<') &&
        (string.equals(Constants.INSTANCE_INITIALIZER_NAME) ||
            string.equals(Constants.CLASS_INITIALIZER_NAME))) {
      return true;
    }
    for (int i = 0; i < string.length(); i++) {
      char ch = string.charAt(i);
      if (isSimpleNameChar(ch)) {
        continue;
      }
      return false;
    }
    return true;
  }

  private boolean isValidFieldName(String string) {
    if (string.isEmpty()) {
      return false;
    }
    int start = 0;
    int end = string.length();
    if (string.charAt(0) == '<') {
      if (string.charAt(string.length() - 1) == '>') {
        start = 1;
        end = string.length() - 1;
      } else {
        return false;
      }
    }
    for (int i = start; i < end; i++) {
      if (isSimpleNameChar(string.charAt(i))) {
        continue;
      }
      return false;
    }
    return true;
  }

  public boolean isValidMethodName() {
    try {
      return isValidMethodName(decode());
    } catch (UTFDataFormatException e) {
      return false;
    }
  }

  public boolean isValidFieldName() {
    try {
      return isValidFieldName(decode());
    } catch (UTFDataFormatException e) {
      return false;
    }
  }

  public boolean isValidClassDescriptor() {
    try {
      return isValidClassDescriptor(decode());
    } catch (UTFDataFormatException e) {
      return false;
    }
  }

  public String dump() {
    StringBuilder builder = new StringBuilder();
    builder.append(toString());
    builder.append(" [");
    for (int i = 0; i < content.length; i++) {
      if (i > 0) {
        builder.append(" ");
      }
      builder.append(Integer.toHexString(content[i] & 0xff));
    }
    builder.append("]");
    return builder.toString();
  }

  public boolean beginsWith(DexString prefix) {
    if (content.length < prefix.content.length) {
      return false;
    }
    for (int i = 0; i < prefix.content.length - 1; i++) {
      if (content[i] != prefix.content[i]) {
        return false;
      }
    }
    return true;
  }

  public boolean endsWith(DexString suffix) {
    if (content.length < suffix.content.length) {
      return false;
    }
    for (int i = content.length - suffix.content.length, j = 0; i < content.length; i++, j++) {
      if (content[i] != suffix.content[j]) {
        return false;
      }
    }
    return true;
  }
}
