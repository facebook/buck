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
import java.util.Optional;

/**
 * ResourcesXml handles Android's compiled xml format. It consists of:
 *   ResChunk_header
 *     u16 chunk_type
 *     u16 header_size
 *     u32 chunk_size
 *
 * The header is followed by a StringPool containing all the strings used in the xml. This is then
 * followed by an optional RefMap. After the StringPool/RefMap comes a list of xml nodes of the
 * form:
 *   ResChunk_header
 *     u16 chunk_type
 *     u16 header_size
 *     u32 chunk_size
 *   u32       lineNumber
 *   StringRef comment
 *
 * Each node contains extra information depending on the type:
 *
 * XML_START_NS:
 *   StringRef prefix
 *   StringRef uri
 *
 * XML_END_NS:
 *   StringRef prefix
 *   StringRef uri
 *
 * XML_CDATA:
 *   StringRef data
 *   Res_value typedData
 *
 * XML_END_ELEMENT:
 *   StringRef namespace
 *   StringRef name
 *
 * XML_START_ELEMENT:
 *   StringRef namespace
 *   StringRef name
 *   u16       attrStart
 *   u16       attrSize
 *   u16       attrCount
 *   u16       idIndex
 *   u16       classIndex
 *   u16       styleIndex
 *
 * XML_START_ELEMENT is then followed by attrCount attributes of the form:
 *   StringRef namespace
 *   StringRef name
 *   StringRef rawValue
 *   Res_value typedValue
 */
public class ResourcesXml extends ResChunk {
  public static final int HEADER_SIZE = 8;

  private final StringPool strings;
  private final Optional<RefMap> refMap;
  private final ByteBuffer nodeBuf;

  public static ResourcesXml get(ByteBuffer buf) {
    int type = buf.getShort();
    int headerSize = buf.getShort();
    int chunkSize = buf.getInt();

    Preconditions.checkState(type == CHUNK_XML_TREE);
    Preconditions.checkState(headerSize == HEADER_SIZE);
    Preconditions.checkState(chunkSize == buf.limit());

    // The header should be immediately followed by a string pool.
    StringPool strings = StringPool.get(slice(buf, HEADER_SIZE));
    buf.position(HEADER_SIZE + strings.getChunkSize());

    int nextType = buf.getShort(buf.position());
    Optional<RefMap> refMap = Optional.empty();
    if (nextType == ResChunk.CHUNK_XML_REF_MAP) {
      RefMap map = new RefMap(slice(buf, buf.position()));
      refMap = Optional.of(map);
      buf.position(buf.position() + map.getChunkSize());
    }
    return new ResourcesXml(buf.limit(), strings, refMap, slice(buf, buf.position()));
  }

  @Override
  public void put(ByteBuffer buf) {
    putChunkHeader(buf);
    strings.put(buf);
    refMap.ifPresent(m -> m.put(buf));
    buf.put(slice(nodeBuf, 0));
  }

  private ResourcesXml(
      int chunkSize,
      StringPool strings,
      Optional<RefMap> refMap,
      ByteBuffer nodeBuf) {
    super(CHUNK_XML_TREE, HEADER_SIZE, chunkSize);
    this.strings = strings;
    this.refMap = refMap;
    this.nodeBuf = nodeBuf;
  }

  public StringPool getStrings() {
    return strings;
  }

  /**
   * A RefMap is an optional entry in a compiled xml that provides the reference ids for some of the
   * strings in the stringpool. It consists of
   *   ResTable_header
   *       u32 chunk_type
   *       u32 header_size
   *       u32 chunk_size
   *
   * The header is followed by an array of u32 ids of size (chunk_size - header_size) / 4. Each
   * entry is the reference id for the string with the same position in the string pool.
   */
  private static class RefMap extends ResChunk {
    private final ByteBuffer buf;
    private final int refCount;

    RefMap(ByteBuffer buf) {
      super(buf.getShort(), buf.getShort(), buf.getInt());
      this.buf = slice(buf, 0, getChunkSize());
      this.refCount = (getChunkSize() - getHeaderSize()) / 4;
      Preconditions.checkState(refCount >= 0);
    }

    @Override
    public void put(ByteBuffer output) {
      output.put(slice(buf, 0));
    }
  }
}
