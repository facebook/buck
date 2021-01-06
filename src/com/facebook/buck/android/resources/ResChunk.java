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

import com.google.common.base.Preconditions;
import com.google.common.primitives.Shorts;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * ResChunk is the base of most structures in Android's .arsc and compiled .xml files. It consists
 * of: u16 chunkType u16 headerSize u32 chunkSize
 *
 * <p>Some common types used by different chunks:
 *
 * <p>StringRef is a u32 string id ResRef is a u32 resource id ResValue is a chunk of: u16 size u8
 * 0x00 u8 dataType (one of ResChunk.RES_XXXXXXX) u32 data
 *
 * <p>See
 * https://android.googlesource.com/platform/frameworks/base/+/kitkat-release/include/androidfw/ResourceTypes.h
 * for full specification.
 */
public abstract class ResChunk {
  public static final short CHUNK_STRING_POOL = 0x001;
  public static final short CHUNK_RESOURCE_TABLE = 0x002;
  public static final short CHUNK_XML_TREE = 0x003;

  public static final short CHUNK_XML_REF_MAP = 0x180;

  public static final short CHUNK_RES_TABLE_PACKAGE = 0x0200;
  public static final short CHUNK_RES_TABLE_TYPE = 0x201;
  public static final short CHUNK_RES_TABLE_TYPE_SPEC = 0x202;

  static final int RES_REFERENCE = 0x1;
  static final int RES_ATTRIBUTE = 0x2;
  static final int RES_STRING = 0x3;

  static final int RES_FLOAT = 0x4;
  static final int RES_DIMENSION = 0x5;
  static final int RES_FRACTION = 0x6;

  static final int RES_DYNAMIC_REFERENCE = 0x07;
  static final int RES_DYNAMIC_ATTRIBUTE = 0x08;

  static final int RES_DECIMAL = 0x10;
  static final int RES_HEX = 0x11;
  static final int RES_BOOL = 0x12;

  static final int RES_COLOR_ARGB8 = 0x1c;
  static final int RES_COLOR_RGB8 = 0x1d;
  static final int RES_COLOR_ARGB4 = 0x1e;
  static final int RES_COLOR_RGB4 = 0x1f;

  private final short type;
  private final short headerSize;
  private final int chunkSize;

  ResChunk(int chunkType, int headerSize, int chunkSize) {
    this.type = Shorts.checkedCast(chunkType);
    this.headerSize = Shorts.checkedCast(headerSize);
    this.chunkSize = chunkSize;
    Preconditions.checkState((chunkSize % 4) == 0);
  }

  public final int getType() {
    return type;
  }

  public final int getHeaderSize() {
    return headerSize;
  }

  public final int getChunkSize() {
    return chunkSize;
  }

  /**
   * For most chunk's totalSize == chunkSize. For some types, the type logically consists of
   * multiple chunks. In these cases, totalSize is the sum of all the chunks. This is the full size
   * written/read from a buffer for get/put.
   */
  public int getTotalSize() {
    return chunkSize;
  }

  public final byte[] serialize() {
    byte[] data = new byte[getTotalSize()];
    ByteBuffer output = wrap(data);
    put(output);
    Preconditions.checkState(output.position() == data.length);
    return data;
  }

  public abstract void put(ByteBuffer output);

  void putChunkHeader(ByteBuffer output) {
    output.putShort(type);
    output.putShort(headerSize);
    output.putInt(chunkSize);
  }

  public interface RefTransformer {
    int transform(int ref);
  }

  public interface RefVisitor {
    void visit(int ref);
  }

  static void transformEntryDataOffset(ByteBuffer buf, int offset, RefTransformer visitor) {
    int oldValue = buf.getInt(offset);
    int newValue = visitor.transform(oldValue);
    if (oldValue != newValue) {
      buf.putInt(offset, newValue);
    }
  }

  // These are some utilities used widely by subclasses for dealing with ByteBuffers.
  static ByteBuffer copy(ByteBuffer buf) {
    return wrap(
        Arrays.copyOfRange(buf.array(), buf.arrayOffset(), buf.arrayOffset() + buf.limit()));
  }

  public static ByteBuffer wrap(byte[] data) {
    ByteBuffer buf = ByteBuffer.wrap(data);
    buf.order(ByteOrder.LITTLE_ENDIAN);
    return buf;
  }

  public static ByteBuffer slice(ByteBuffer map, int offset) {
    ByteBuffer result = map.duplicate();
    result.position(offset);
    result = result.slice();
    result.order(ByteOrder.LITTLE_ENDIAN);
    return result;
  }

  public static ByteBuffer slice(ByteBuffer map, int offset, int length) {
    ByteBuffer result = slice(map, offset);
    result.limit(length);
    return result;
  }
}
