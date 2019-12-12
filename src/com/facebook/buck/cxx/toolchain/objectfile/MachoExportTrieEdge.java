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

/**
 * Represents a Mach-O export trie edge, containing a C-string (prefix) and a pointer to the node.
 */
public class MachoExportTrieEdge {
  // Includes terminating zero byte.
  protected final byte[] substring;

  protected final MachoExportTrieNode node;

  MachoExportTrieEdge(byte[] substring, MachoExportTrieNode node) {
    this.substring = substring;
    this.node = node;
  }
}
