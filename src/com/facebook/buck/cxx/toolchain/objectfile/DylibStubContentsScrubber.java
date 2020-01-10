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

import com.facebook.buck.io.file.FileContentsScrubber;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Optional;

/**
 * Scrubs the contents of dylib stubs, so that they're independent of the contents of the source
 * dylib. That means that if the source dylib's ABI does not change, it will produce the exact same
 * dylib stub.
 *
 * <p>The linker itself does not actually care about the symbol addresses, it just wants to ensure
 * any referenced symbols exist. That means we can reset the addresses to any value we would like.
 * Note that the symbol addresses themselves are already invalid in the dylib stub, as the contents
 * (i.e., binary instructions) have been stripped by "strip".
 *
 * <p>Dylib stubs produced by "strip" are still dependent on symbol addresses and the UUID. To make
 * them independent, we do the following:
 *
 * <ul>
 *   <li>Set the UUID to the same value each time
 *   <li>Update the _LINKEDIT segment and remove any addresses for symbols
 *       <ul>
 *         <li>Update the exports info
 *         <li>Update the symbol table
 *       </ul>
 */
public class DylibStubContentsScrubber implements FileContentsScrubber {

  @Override
  public void scrubFile(FileChannel file) throws IOException, ScrubException {
    MappedByteBuffer mappedFile = file.map(FileChannel.MapMode.READ_WRITE, 0, file.size());
    LcUuidContentsScrubber.resetUuidIfPresent(mappedFile);

    Optional<MachoSymTabCommand> maybeCmd = MachoSymTabCommandReader.read(mappedFile);
    if (maybeCmd.isPresent()) {
      resetSymbolAddressesInSymbolTable(mappedFile, maybeCmd.get());
    }

    Optional<MachoDyldInfoCommand> maybeDyldInfo = MachoDyldInfoCommandReader.read(mappedFile);
    if (maybeDyldInfo.isPresent()) {
      resetSymbolAddressesInExportInfo(mappedFile, maybeDyldInfo.get());
    }
  }

  private static void resetSymbolAddressesInSymbolTable(
      MappedByteBuffer machoBuffer, MachoSymTabCommand symTabCommand) {
    machoBuffer.position(symTabCommand.getSymbolTableOffset());

    for (int i = 0; i < symTabCommand.getNumberOfSymbolTableEntries(); ++i) {
      // struct nlist_64 {
      //     union {
      //         uint32_t  n_strx;  // index into the string table
      //     } n_un;
      //     uint8_t n_type;        // type flag, see below
      //     uint8_t n_sect;        // section number or NO_SECT
      //     uint16_t n_desc;       // see <mach-o/stab.h>
      //     uint64_t n_value;      // value of this symbol (or stab offset)
      // };
      ObjectFileScrubbers.getLittleEndianInt(machoBuffer); // n_strx
      machoBuffer.get(); // n_type
      machoBuffer.get(); // n_sect
      ObjectFileScrubbers.getLittleEndianShort(machoBuffer); // n_desc
      ObjectFileScrubbers.putLittleEndianLong(machoBuffer, 0x0); // n_value
    }
  }

  private static void resetSymbolAddressesInExportInfo(
      MappedByteBuffer machoBuffer, MachoDyldInfoCommand dyldInfoCommand) {
    machoBuffer.position(dyldInfoCommand.getExportInfoOffset());
    machoBuffer.limit(dyldInfoCommand.getExportInfoOffset() + dyldInfoCommand.getExportInfoSize());

    ByteBuffer exportInfoBuffer = machoBuffer.slice();
    Optional<MachoExportTrieNode> tree = MachoExportTrieReader.read(exportInfoBuffer);
    if (tree.isPresent()) {
      resetSymbolAddressesInTree(tree.get());

      // We want to reset all addresses to zero and write out a new tree rather than just setting
      // all existing addresses to zero: that's because symbol offset sizes depends on the offsets
      // themselves, so we want to make the scrubbed dylib stub be independent from those addresses.
      // Otherwise, it's possible that the dylib stub will be logically the same (i.e., all
      // addresses zero) but different on disk due to zero being expressed using different number
      // of ULEB128 bytes.
      exportInfoBuffer.rewind();
      MachoExportTrieWriter.write(tree.get(), exportInfoBuffer);

      // As we set all addresses to 0x0, that means the total size of the trie will _never_ be
      // larger than the initial trie. Consequently, we do not need to adjust segment sizes and
      // just need to zero out any remaining parts in the export section.
      while (exportInfoBuffer.position() < exportInfoBuffer.limit()) {
        exportInfoBuffer.put((byte) 0x00);
      }
    }
  }

  private static void resetSymbolAddressesInTree(MachoExportTrieNode node) {
    if (!node.hasReexportFlag() && !node.hasStubAndResolverFlag()) {
      node.setAddress(0x0);
    }

    for (MachoExportTrieEdge edge : node.getEdges()) {
      resetSymbolAddressesInTree(edge.node);
    }
  }
}
