/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android.resources;

import com.google.common.base.Preconditions;

import java.nio.ByteBuffer;

/**
 * A StringPool consists of a header:
 *   ResChunk_header
 *       u32 chunk_type
 *       u32 header_size
 *       u32 chunk_size
 *   u32 string_count
 *   u32 style_count
 *   u32 flags - 0x1 sorted, 0x100 utf-8 encoded
 *   u32 strings_start - byte offset from the beginning to the style data
 *   u32 styles_start - byte offset from the beginning to the style data
 *
 * The header is followed by an u32[string_count] array of strings offsets (relative to
 * strings_start) where the strings reside. This is then followed by a similar array for styles.
 *
 * In a utf-8 encoded string pool, a string data consists of: utf-16 length, utf-8 length, string
 * bytes, \0.
 *
 * In a utf-16 encoded string pool, a string data consists of: utf-16 length, string bytes, \0\0.
 *
 * A style is an array of tuples (u32 stringref, u32 start, u32 end). A stringref of
 * 0xFFFFFFFF indicates the end of a style array (note that the next array may start at the
 * immediately following word).
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
        HEADER_SIZE +
            stringOffsets.limit() + styleOffsets.limit() +
            stringData.limit() + styleData.limit());
    this.stringCount = stringCount;
    this.styleCount = styleCount;
    this.utf8 = utf8;
    this.sorted = sorted;
    this.stringOffsets = stringOffsets;
    this.styleOffsets = styleOffsets;
    this.stringData = stringData;
    this.styleData = styleData;
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

  public int getStringCount() {
    return stringCount;
  }

  public boolean isUtf8() {
    return utf8;
  }
}
