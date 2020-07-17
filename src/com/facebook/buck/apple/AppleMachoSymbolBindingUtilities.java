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
import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommand;
import com.facebook.buck.cxx.toolchain.objectfile.MachoDyldInfoCommandReader;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieNode;
import com.facebook.buck.cxx.toolchain.objectfile.MachoExportTrieReader;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Utilities related to symbols in Mach-O executables. */
public class AppleMachoSymbolBindingUtilities {

  /** Computes the intersection of the input symbols and the exported symbols of a dylib. */
  public static ImmutableList<String> computeExportedSymbolIntersection(
      ImmutableList<String> allSymbols, AbsPath dylibPath) throws IOException {
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

          if (maybeRootNode.isPresent()) {
            // containsSymbol() operates on an immutable trie, so the filtering can be parallel.
            return allSymbols
                .parallelStream()
                .filter(symbol -> maybeRootNode.get().containsSymbol(symbol))
                .collect(ImmutableList.toImmutableList());
          }
        }
      }
    }

    return ImmutableList.of();
  }
}
