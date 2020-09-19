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

package com.facebook.buck.apple;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommand;
import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommandReader;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieNode;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieReader;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Utilities related to symbols in Mach-O executables. */
public class AppleMachoSymbolBindingUtilities {

  private static final Logger LOG = Logger.get(AppleMachoSymbolBindingUtilities.class);

  private static final AppleMachoSymbolBindingUtilities UTILITIES =
      new AppleMachoSymbolBindingUtilities();

  // This is a two-level map from dylibPath -> Mach-O UUID -> Export Trie. Aa two-level map is
  // specifically used as it allows us to set a limit on the 2nd level cache, so it will perform
  // automatic cache eviction on a _per dylib_ basis.
  private final LoadingCache<AbsPath, LoadingCache<String, Optional<MachoExportTrieNode>>>
      trieCache;

  private AppleMachoSymbolBindingUtilities() {
    this.trieCache =
        CacheBuilder.newBuilder()
            .build(
                CacheLoader.from(
                    dylibPath -> {
                      return CacheBuilder.newBuilder()
                          // Setting the maximum size to 1 means that we will only ever keep the
                          // current
                          // Mach-O export trie for any dylib. Effectively, for each incremental
                          // build,
                          // we will be recomputing the export trie for a dylib at most once.
                          .maximumSize(1)
                          .build(CacheLoader.from(unusedUuid -> tryReadingExportTrie(dylibPath)));
                    }));
  }

  Optional<MachoExportTrieNode> getExportTrie(AbsPath dylibPath) throws IOException {
    Optional<String> maybeUuid = Machos.getMachoUuid(dylibPath);
    return maybeUuid.flatMap(uuid -> trieCache.getUnchecked(dylibPath).getUnchecked(uuid));
  }

  private static Optional<MachoExportTrieNode> tryReadingExportTrie(AbsPath dylibPath) {
    try (FileChannel file = FileChannel.open(dylibPath.getPath(), StandardOpenOption.READ)) {
      try (ByteBufferUnmapper unmapper =
          ByteBufferUnmapper.createUnsafe(
              file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))) {

        ByteBuffer machoExecutableBuffer = unmapper.getByteBuffer();

        Optional<MachoDyldInfoCommand> maybeDyldCommand =
            MachoDyldInfoCommandReader.read(machoExecutableBuffer);
        if (maybeDyldCommand.isPresent()) {
          MachoDyldInfoCommand dyldCommand = maybeDyldCommand.get();
          Optional<MachoExportTrieNode> maybeRootNode =
              MachoExportTrieReader.readFromExecutable(
                  machoExecutableBuffer,
                  dyldCommand.getExportInfoOffset(),
                  dyldCommand.getExportInfoSize());

          return maybeRootNode;
        } else {
          LOG.error(
              "Could not read Mach-O DYLD_INFO_COMMAND for dylib at path: ", dylibPath.toString());
        }
      }
    } catch (IOException e) {
      LOG.error(e, String.format("Could not read Mach-O export trie at %s", dylibPath.toString()));
    }

    return Optional.empty();
  }

  /**
   * Computes the intersection of the input symbols and the exported symbols of a dylib. An empty
   * optional is returned if there's a failure in reading the exported symbols.
   */
  public static Optional<ImmutableList<String>> computeExportedSymbolIntersection(
      ImmutableList<String> allSymbols, AbsPath dylibPath) throws IOException {
    Optional<MachoExportTrieNode> maybeRootNode = UTILITIES.getExportTrie(dylibPath);

    return maybeRootNode.map(
        rootNode ->
            allSymbols
                .parallelStream()
                // containsSymbol() operates on an immutable trie, so the filtering can be parallel.
                .filter(symbol -> rootNode.containsSymbol(symbol))
                .collect(ImmutableList.toImmutableList()));
  }
}
