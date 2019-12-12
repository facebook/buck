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

package com.facebook.buck.android.resources;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A StringPool consists of a header: ResChunk_header u32 chunk_type u32 header_size u32 chunk_size
 * u32 string_count u32 style_count u32 flags - 0x1 sorted, 0x100 utf-8 encoded u32 strings_start -
 * byte offset from the beginning to the style data u32 styles_start - byte offset from the
 * beginning to the style data
 *
 * <p>The header is followed by an u32[string_count] array of strings offsets (relative to
 * strings_start) where the strings reside. This is then followed by a similar array for styles.
 *
 * <p>In a utf-8 encoded string pool, a string data consists of: utf-16 length, utf-8 length, string
 * bytes, \0.
 *
 * <p>In a utf-16 encoded string pool, a string data consists of: utf-16 length, string bytes, \0\0.
 *
 * <p>A style is an array of tuples (u32 stringref, u32 start, u32 end). A stringref of 0xFFFFFFFF
 * indicates the end of a style array (note that the next array may start at the immediately
 * following word).
 */
public class StringPool extends ResChunk {
  private static final short HEADER_SIZE = 28;
  private static final int SORTED_FLAG = 0x1;
  private static final int UTF8_FLAG = 0x100;

  private final int stringCount;
  private final int styleCount;
  private final boolean utf8;
  private final boolean sorted;
  private final ByteBuffer stringOffsets;
  private final ByteBuffer styleOffsets;
  private final ByteBuffer stringData;
  private final ByteBuffer styleData;

  private StringPool(
      int stringCount,
      int styleCount,
      boolean utf8,
      boolean sorted,
      ByteBuffer stringOffsets,
      ByteBuffer styleOffsets,
      ByteBuffer stringData,
      ByteBuffer styleData) {
    super(
        CHUNK_STRING_POOL,
        HEADER_SIZE,
        HEADER_SIZE
            + stringOffsets.limit()
            + styleOffsets.limit()
            + stringData.limit()
            + styleData.limit());
    this.stringCount = stringCount;
    this.styleCount = styleCount;
    this.utf8 = utf8;
    this.sorted = sorted;
    this.stringOffsets = stringOffsets;
    this.styleOffsets = styleOffsets;
    this.stringData = stringData;
    this.styleData = styleData;
  }

  public static StringPool create(Iterable<String> strings) {
    List<String> stringsList = ImmutableList.copyOf(strings);
    int stringCount = stringsList.size();

    ByteBuffer stringOffsets = wrap(new byte[4 * stringCount]);
    ByteArrayOutputStream stringData = new ByteArrayOutputStream();

    byte[] encodedLength = new byte[4];
    ByteBuffer lengthBuf = wrap(encodedLength);
    for (int i = 0; i < stringsList.size(); i++) {
      lengthBuf.position(0);
      String value = stringsList.get(i);
      putEncodedLength(lengthBuf, value.length());
      ByteBuffer encoded = Charsets.UTF_8.encode(value);
      putEncodedLength(lengthBuf, encoded.limit());

      stringOffsets.putInt(i * 4, stringData.size());
      stringData.write(encodedLength, 0, lengthBuf.position());
      stringData.write(encoded.array(), encoded.arrayOffset(), encoded.limit());
      stringData.write(0);
    }

    // Pad to 4-byte boundary.
    lengthBuf.putInt(0, 0);
    stringData.write(encodedLength, 0, (4 - (stringData.size() % 4)) % 4);

    return new StringPool(
        stringCount,
        0,
        true,
        false,
        stringOffsets,
        wrap(new byte[0]),
        wrap(stringData.toByteArray()),
        wrap(new byte[0]));
  }

  private static void putEncodedLength(ByteBuffer buf, int length) {
    if (length < (1 << 7)) {
      buf.put((byte) length);
    } else {
      buf.put((byte) ((1 << 7) | (length >> 8)));
      buf.put((byte) (length & 0xFF));
    }
  }

  public static StringPool get(ByteBuffer buf) {
    int type = buf.getShort();
    int headerSize = buf.getShort();
    int chunkSize = buf.getInt();
    int stringCount = buf.getInt();
    int styleCount = buf.getInt();
    int flags = buf.getInt();
    boolean utf8 = (flags & UTF8_FLAG) != 0;
    boolean sorted = (flags & SORTED_FLAG) != 0;
    int stringsStart = buf.getInt();
    int stylesStart = buf.getInt();

    Preconditions.checkState(type == CHUNK_STRING_POOL);
    Preconditions.checkState(headerSize == HEADER_SIZE);
    Preconditions.checkState(stringsStart == headerSize + 4 * (stringCount + styleCount));

    buf = slice(buf, 0, chunkSize);
    // Adjust stylesStart to actually point at the end of the string data.
    stylesStart = stylesStart == 0 ? buf.limit() : stylesStart;
    return new StringPool(
        stringCount,
        styleCount,
        utf8,
        sorted,
        slice(buf, headerSize, 4 * stringCount),
        slice(buf, headerSize + 4 * stringCount, 4 * styleCount),
        slice(buf, stringsStart, stylesStart - stringsStart),
        slice(buf, stylesStart, buf.limit() - stylesStart));
  }

  @Override
  public void put(ByteBuffer output) {
    putChunkHeader(output);
    output.putInt(stringCount);
    output.putInt(styleCount);
    output.putInt((utf8 ? UTF8_FLAG : 0) | (sorted ? SORTED_FLAG : 0));
    int stringsStart = HEADER_SIZE + 4 * (stringCount + styleCount);
    output.putInt(stringsStart);
    output.putInt(styleCount == 0 ? 0 : stringsStart + stringData.limit());
    output.put(slice(stringOffsets, 0));
    output.put(slice(styleOffsets, 0));
    output.put(slice(stringData, 0));
    output.put(slice(styleData, 0));
  }

  private int getUtf8Length(int offset) {
    int hi = stringData.get(offset);
    if (hi < 0) {
      hi = ((hi & 0x7F) << 8) + (stringData.get(offset + 1) & 0xFF);
    }
    return hi;
  }

  private int getUtf16Length(int offset) {
    int hi = stringData.getShort(offset);
    if (hi < 0) {
      hi = ((hi & 0x7FFF) << 16) + (stringData.getShort(offset + 2) & 0xFFFF);
    }
    return hi;
  }

  private int getEncodedStringOffset(int id) {
    return stringOffsets.getInt(id * 4);
  }

  public String getString(int id) {
    return getStringAtOffset(getEncodedStringOffset(id), false);
  }

  private String getStringAtOffset(int offset, boolean forDump) {
    int length;
    if (utf8) {
      // For utf8 strings, both the length in code points and the length in bytes is encoded.
      int utf16Length = getUtf8Length(offset);
      offset += (utf16Length < (1 << 7)) ? 1 : 2;
      int utf8length = getUtf8Length(offset);
      offset += (utf8length < (1 << 7)) ? 1 : 2;
      // For `aapt dump strings`, aapt has a bug where they use the decoded length rather than the
      // encoded length when extracting string data...
      length = forDump ? utf16Length : utf8length;
    } else {
      length = getUtf16Length(offset);
      offset += (length < (1 << 15)) ? 2 : 4;
      length *= 2;
    }
    return decodeString(offset, length);
  }

  private String getStringForDump(int id) {
    return getStringAtOffset(getEncodedStringOffset(id), true);
  }

  private String decodeString(int start, int utf16Length) {
    byte[] data = new byte[utf16Length];
    stringData.position(start);
    stringData.get(data);
    stringData.position(0);
    return new String(data, utf8 ? Charsets.UTF_8 : Charsets.UTF_16LE);
  }

  public void dump(PrintStream out) {
    out.format(
        "String pool of %d unique %s %s strings, %d entries and %d styles using %d bytes:\n",
        stringCount,
        utf8 ? "UTF-8" : "UTF-16",
        sorted ? "sorted" : "non-sorted",
        stringCount,
        styleCount,
        getChunkSize());
    for (int i = 0; i < stringCount; i++) {
      out.format("String #%d: %s\n", i, getStringForDump(i));
    }
  }

  public int getStringCount() {
    return stringCount;
  }

  public boolean isUtf8() {
    return utf8;
  }

  public String getOutputNormalizedString(int data) {
    return getString(data).replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
  }

  public StringPool copy() {
    return new StringPool(
        stringCount,
        styleCount,
        utf8,
        sorted,
        copy(stringOffsets),
        copy(styleOffsets),
        copy(stringData),
        copy(styleData));
  }
}
