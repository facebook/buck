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

package com.facebook.buck.cxx.toolchain.objectfile;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Optional;

/**
 * The export info is a serialized trie. For trie format reference, see MachOTrie.hpp in ld64.
 * https://opensource.apple.com/source/dyld/dyld-635.2/launch-cache/MachOTrie.hpp.auto.html
 */
public class MachoExportTrieReader {

  public static Optional<MachoExportTrieNode> read(ByteBuffer byteBuffer) {
    return readTreeNode(byteBuffer);
  }

  private static Optional<MachoExportTrieNode> readTreeNode(ByteBuffer byteBuffer) {
    final int nodeOffset = byteBuffer.position();
    long exportInfoSize = ULEB128.read(byteBuffer);

    Optional<MachoExportTrieNode.ExportInfo> exportInfo = Optional.empty();
    if (exportInfoSize > 0) {
      int startPosition = byteBuffer.position();
      exportInfo = Optional.of(readExportInfo(byteBuffer));
      int bytesRead = byteBuffer.position() - startPosition;
      if (bytesRead != exportInfoSize) {
        throw new IllegalStateException(
            "Mismatch between expected export info size and actual bytes read");
      }
    }

    int numberOfChildren = byteBuffer.get() & 0xFF;
    int nextChildEdgePosition = byteBuffer.position();

    ArrayList<MachoExportTrieEdge> edges = new ArrayList<>();

    for (int i = 0; i < numberOfChildren; ++i) {
      byteBuffer.position(nextChildEdgePosition);
      byte[] prefix = readCString(byteBuffer);
      int childOffset = (int) ULEB128.read(byteBuffer);

      nextChildEdgePosition = byteBuffer.position();
      byteBuffer.position(childOffset);

      Optional<MachoExportTrieNode> childNode = readTreeNode(byteBuffer);
      if (!childNode.isPresent()) {
        return Optional.empty();
      }

      edges.add(new MachoExportTrieEdge(prefix, childNode.get()));
    }

    return Optional.of(
        new MachoExportTrieNode(
            edges.toArray(new MachoExportTrieEdge[] {}), exportInfo, nodeOffset));
  }

  private static MachoExportTrieNode.ExportInfo readExportInfo(ByteBuffer byteBuffer) {
    MachoExportTrieNode.ExportInfo info = new MachoExportTrieNode.ExportInfo();

    info.flags = ULEB128.read(byteBuffer);
    if ((info.flags & MachoExportTrieNode.EXPORT_SYMBOL_FLAGS_REEXPORT) != 0) {
      info.other = ULEB128.read(byteBuffer);
      info.importedName = Optional.of(readCString(byteBuffer));
    } else {
      info.address = ULEB128.read(byteBuffer);
      if ((info.flags & MachoExportTrieNode.EXPORT_SYMBOL_FLAGS_STUB_AND_RESOLVER) != 0) {
        info.other = ULEB128.read(byteBuffer);
      }
    }

    return info;
  }

  // Prefer dealing with C-style strings as byte[] as we do not require string operations
  private static byte[] readCString(ByteBuffer byteBuffer) {
    ByteArrayOutputStream cStringBuffer = new ByteArrayOutputStream();
    while (true) {
      byte currentByte = byteBuffer.get();
      cStringBuffer.write(currentByte);
      if (currentByte == 0x0) {
        break;
      }
    }

    return cStringBuffer.toByteArray();
  }
}
