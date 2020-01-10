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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Provides ability to serialize an export trie, compatible with ld64. For reference, see
 * MachOTrie.hpp in ld64.
 * https://opensource.apple.com/source/dyld/dyld-635.2/launch-cache/MachOTrie.hpp.auto.html
 */
public class MachoExportTrieWriter {

  /** Writes the export trie to a buffer. */
  public static void write(MachoExportTrieNode root, ByteBuffer byteBuffer) {
    ArrayList<MachoExportTrieNode> orderedNodes = new ArrayList<>();
    flattenTreeToList(root, orderedNodes);
    Collections.sort(
        orderedNodes,
        (lhs, rhs) -> {
          return (lhs.getOrder() < rhs.getOrder() ? -1 : (lhs.getOrder() > rhs.getOrder() ? 1 : 0));
        });

    computeTrieOffsets(root, orderedNodes);
    writeNodes(orderedNodes, byteBuffer);
  }

  private static void flattenTreeToList(MachoExportTrieNode tree, List<MachoExportTrieNode> list) {
    list.add(tree);

    for (MachoExportTrieEdge edge : tree.getEdges()) {
      flattenTreeToList(edge.node, list);
    }
  }

  private static void computeTrieOffsets(
      MachoExportTrieNode root, List<MachoExportTrieNode> nodes) {
    // There's a circular dependency between node sizes. Put succinctly, the size of a preceding
    // node depends on the offset of all succeeding nodes (children). But the offset of all
    // succeeding nodes depends on the size of their parent (i.e., preceding node). That's because
    // all offsets are stored as ULEB128, which means preceding nodes' sizes depend on the
    // offset of the succeeding nodes which in turn depend on the aforementioned size.
    //
    // Here's an example: imagine we have two nodes, A and B, where B is a child of A. In order to
    // compute the size of A, we need to know the offset of B because A stores the offset to B
    // as an ULEB128. But in order to compare the offset of B, we need to know the size of A, as B
    // follows A.
    //
    // To resolve the situation, we start off with all offsets being reset to 0: those can be
    // thought of as candidate offsets. Then we start iterating and adjusting the offsets as more
    // nodes start to converge to their true size. We keep doing that until all offsets stabilize.
    root.resetTreeTrieOffsets();

    boolean isStable = false;
    while (!isStable) {
      isStable = true;

      int nextTrieOffset = 0;

      for (MachoExportTrieNode currentNode : nodes) {
        if (currentNode.getTrieOffset() != nextTrieOffset) {
          isStable = false;
        }

        currentNode.setTrieOffset(nextTrieOffset);
        nextTrieOffset += getNodeSize(currentNode);
      }
    }
  }

  private static void writeNodes(List<MachoExportTrieNode> nodes, ByteBuffer byteBuffer) {
    // NB: The code is deliberately written in a way that follows similar paths compared
    //     to ld64 (see Node::appendToStream() in MachOTrie.hpp).
    // https://opensource.apple.com/source/dyld/dyld-635.2/launch-cache/MachOTrie.hpp.auto.html
    for (MachoExportTrieNode node : nodes) {
      Optional<MachoExportTrieNode.ExportInfo> maybeExportInfo = node.getExportInfo();
      if (maybeExportInfo.isPresent()) {
        MachoExportTrieNode.ExportInfo exportInfo = maybeExportInfo.get();
        if (node.hasReexportFlag()) {
          if (exportInfo.importedName.isPresent()) {
            long nodeSize =
                ULEB128.size(exportInfo.flags)
                    + ULEB128.size(exportInfo.other)
                    + exportInfo.importedName.get().length;
            ULEB128.write(byteBuffer, nodeSize);
            ULEB128.write(byteBuffer, exportInfo.flags);
            ULEB128.write(byteBuffer, exportInfo.other);
            byteBuffer.put(exportInfo.importedName.get());
          } else {
            long nodeSize = ULEB128.size(exportInfo.flags) + ULEB128.size(exportInfo.other) + 1;
            ULEB128.write(byteBuffer, nodeSize);
            ULEB128.write(byteBuffer, exportInfo.flags);
            ULEB128.write(byteBuffer, exportInfo.other);
            ULEB128.write(byteBuffer, 0x0); // empty C string
          }
        } else if (node.hasStubAndResolverFlag()) {
          long nodeSize =
              ULEB128.size(exportInfo.flags)
                  + ULEB128.size(exportInfo.address)
                  + ULEB128.size(exportInfo.other);
          ULEB128.write(byteBuffer, nodeSize);
          ULEB128.write(byteBuffer, exportInfo.flags);
          ULEB128.write(byteBuffer, exportInfo.address);
          ULEB128.write(byteBuffer, exportInfo.other);
        } else {
          long nodeSize = ULEB128.size(exportInfo.flags) + ULEB128.size(exportInfo.address);
          ULEB128.write(byteBuffer, nodeSize);
          ULEB128.write(byteBuffer, exportInfo.flags);
          ULEB128.write(byteBuffer, exportInfo.address);
        }
      } else {
        // The zero byte is used to signify no export info
        ULEB128.write(byteBuffer, 0x0);
      }

      MachoExportTrieEdge[] edges = node.getEdges();
      byteBuffer.put((byte) edges.length);

      for (MachoExportTrieEdge edge : edges) {
        byteBuffer.put(edge.substring);
        ULEB128.write(byteBuffer, edge.node.getTrieOffset());
      }
    }
  }

  private static int getNodeSize(MachoExportTrieNode node) {
    int nodeSize = 0;

    Optional<MachoExportTrieNode.ExportInfo> maybeExportInfo = node.getExportInfo();
    if (maybeExportInfo.isPresent()) {
      MachoExportTrieNode.ExportInfo exportInfo = maybeExportInfo.get();
      if (node.hasReexportFlag()) {
        nodeSize += ULEB128.size(exportInfo.flags) + ULEB128.size(exportInfo.other);
        if (exportInfo.importedName.isPresent()) {
          nodeSize += exportInfo.importedName.get().length;
        } else {
          // empty C string trailing zero
          nodeSize += 1;
        }
      } else {
        nodeSize += ULEB128.size(exportInfo.flags) + ULEB128.size(exportInfo.address);
        if (node.hasStubAndResolverFlag()) {
          nodeSize += ULEB128.size(exportInfo.other);
        }
      }
      nodeSize += ULEB128.size(nodeSize);
    } else {
      // Size for the zero byte
      nodeSize += 1;
    }

    ++nodeSize; // size for children

    for (MachoExportTrieEdge edge : node.getEdges()) {
      int substringLength = (edge.substring != null) ? edge.substring.length : 0;
      nodeSize += substringLength + ULEB128.size(edge.node.getTrieOffset());
    }

    return nodeSize;
  }
}
