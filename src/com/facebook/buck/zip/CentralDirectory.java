/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.zip;

import com.google.common.base.Charsets;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;

/**
 * Each zip file has a "central directory" at the end of the archive, which provides the indexes
 * required for fast random access to the contents of the zip. This class models that.
 * <p>
 * The central directory consists of a series of "file headers", describing each entry in the zip,
 * and a "end of central directory" signature containing book keeping information.
 */
class CentralDirectory {

  /**
   * Write the entire central directory, including the file headers and the end of central directory
   * signature.
   *
   * @param out The stream to output to.
   * @param startOffset The number of bytes offset within the zip file that this starts at.
   * @param entries The entries that are contained within the zip.
   * @throws IOException Should something go awry.
   */
  public void writeCentralDirectory(
      OutputStream out,
      long startOffset,
      Iterable<EntryAccounting> entries) throws IOException {

    int entryCount = 0;
    long size = 0;
    for (EntryAccounting entry : entries) {
      entryCount++;
      size += writeCentralDirectoryFileHeader(out, entry);
    }

    // End of central directory

    ByteIo.writeInt(out, ZipEntry.ENDSIG);

    ByteIo.writeShort(out, 0);  // Number of this disk (with end of central directory)
    ByteIo.writeShort(out, 0);  // Number of disk on which central directory starts.
    ByteIo.writeShort(out, entryCount);  // Number of central directory entries in this disk.
    ByteIo.writeShort(out, entryCount);  // Number of central directory entries.
    ByteIo.writeInt(out, size);  // Size of the central directory in bytes.
    ByteIo.writeInt(out, startOffset);    // Offset of the start of the central directory.
    ByteIo.writeShort(out, 0);  // Size of the comment (we don't have one)
  }

  /**
   * Each entry requires a description of that entry to be contained in the central directory.
   */
  private long writeCentralDirectoryFileHeader(
      OutputStream out,
      EntryAccounting entry) throws IOException {
    long size = 0;
    size += ByteIo.writeInt(out, ZipEntry.CENSIG);
    size += ByteIo.writeShort(out, entry.getRequiredExtractVersion());  // version made by.
    size += ByteIo.writeShort(out, entry.getRequiredExtractVersion());  // version to extract with.
    size += ByteIo.writeShort(out, entry.getFlags());
    size += ByteIo.writeShort(out, entry.getCompressionMethod());  // Compression.
    size += ByteIo.writeInt(out, entry.getTime());      // Modification time.
    size += ByteIo.writeInt(out, entry.getCrc());
    size += ByteIo.writeInt(out, entry.getCompressedSize());
    size += ByteIo.writeInt(out, entry.getSize());

    byte[] nameBytes = entry.getName().getBytes(Charsets.UTF_8);
    long externalAttributes = entry.getExternalAttributes();
    size += ByteIo.writeShort(out, nameBytes.length);  // Length of name.
    size += ByteIo.writeShort(out, 0);                 // Length of extra data.
    size += ByteIo.writeShort(out, 0);                 // Length of file comment.
    size += ByteIo.writeShort(out, 0);                 // Disk on which file starts.
    size += ByteIo.writeShort(out, 0);                 // internal file attributes (unknown)
    size += ByteIo.writeInt(out, externalAttributes);  // external file attributes
    size += ByteIo.writeInt(out, entry.getOffset());   // Offset of local file header.
    out.write(nameBytes);
    size += nameBytes.length;

    return size;
  }
}
