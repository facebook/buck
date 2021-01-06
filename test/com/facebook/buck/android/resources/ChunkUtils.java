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

import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.List;

class ChunkUtils {
  private ChunkUtils() {}

  /**
   * Finds all chunks in the buffer with the requested type. This is useful for tests to find chunks
   * of a certain type without needing to understand all the other encountered chunks.
   *
   * @return A list of offsets in the buffer to chunks of the requested type.
   */
  static List<Integer> findChunks(ByteBuffer data, int wantedType) {
    ByteBuffer buf = ResChunk.slice(data, 0);
    ImmutableList.Builder<Integer> offsetsBuilder = ImmutableList.builder();
    int offset = 0;
    while (offset < buf.limit()) {
      int type = buf.getShort(offset);
      if (type == wantedType) {
        offsetsBuilder.add(offset);
      }
      int headerSize = buf.getShort(offset + 2);
      int chunkSize = buf.getInt(offset + 4);
      // These chunks consist of lists of sub-chunks after their headers.
      if (type == ResChunk.CHUNK_RESOURCE_TABLE
          || type == ResChunk.CHUNK_RES_TABLE_PACKAGE
          || type == ResChunk.CHUNK_XML_TREE) {
        int subchunksStart = offset + headerSize;
        int subchunksLength = chunkSize - headerSize;
        for (int subOffset :
            findChunks(ResChunk.slice(buf, subchunksStart, subchunksLength), wantedType)) {
          offsetsBuilder.add(subchunksStart + subOffset);
        }
      }
      offset += chunkSize;
    }
    return offsetsBuilder.build();
  }
}
