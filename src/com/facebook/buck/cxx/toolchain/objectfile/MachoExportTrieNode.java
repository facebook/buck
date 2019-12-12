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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Represents the Mach-O export trie node. */
public class MachoExportTrieNode {
  static long EXPORT_SYMBOL_FLAGS_REEXPORT = 0x08;
  static long EXPORT_SYMBOL_FLAGS_STUB_AND_RESOLVER = 0x10;

  /** Represents the export info, if present, for nodes. */
  public static class ExportInfo {
    public long address = 0;
    public long flags = 0;
    public long other = 0;
    // Includes terminating zero byte if present.
    public Optional<byte[]> importedName = Optional.empty();
  }

  private final MachoExportTrieEdge[] edges;
  private final Optional<ExportInfo> exportInfo;
  private final int order;

  private int trieOffset;

  MachoExportTrieNode(MachoExportTrieEdge[] edges, Optional<ExportInfo> exportInfo, int order) {
    this.edges = edges;
    this.exportInfo = exportInfo;
    this.order = order;
  }

  public MachoExportTrieEdge[] getEdges() {
    return edges;
  }

  public Optional<ExportInfo> getExportInfo() {
    return exportInfo;
  }

  int getOrder() {
    return order;
  }

  void setTrieOffset(int offset) {
    this.trieOffset = offset;
  }

  int getTrieOffset() {
    return this.trieOffset;
  }

  void resetTreeTrieOffsets() {
    trieOffset = 0;
    for (MachoExportTrieEdge edge : edges) {
      edge.node.resetTreeTrieOffsets();
    }
  }

  private boolean hasFlag(long flagValue) {
    return exportInfo.map(info -> (info.flags & flagValue) != 0).orElse(false);
  }

  boolean hasReexportFlag() {
    return hasFlag(EXPORT_SYMBOL_FLAGS_REEXPORT);
  }

  boolean hasStubAndResolverFlag() {
    return hasFlag(EXPORT_SYMBOL_FLAGS_STUB_AND_RESOLVER);
  }

  void setAddress(long address) {
    exportInfo.map(info -> info.address = address);
  }

  /**
   * Performs a depth-first traversal of the trie and collects all nodes which have non-null export
   * info.
   */
  public List<MachoExportTrieNode> collectNodesWithExportInfo() {
    ArrayList<MachoExportTrieNode> list = new ArrayList<>();
    collectNodesWithExportInfo(this, list);
    return list;
  }

  private static void collectNodesWithExportInfo(
      MachoExportTrieNode node, List<MachoExportTrieNode> list) {
    if (node.getExportInfo().isPresent()) {
      list.add(node);
    }

    for (MachoExportTrieEdge edge : node.getEdges()) {
      collectNodesWithExportInfo(edge.node, list);
    }
  }
}
