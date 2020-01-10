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

package com.facebook.buck.util.zip;

import com.google.common.base.Charsets;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;

/**
 * Each zip file has a "central directory" at the end of the archive, which provides the indexes
 * required for fast random access to the contents of the zip. This class models that.
 *
 * <p>The central directory consists of a series of "file headers", describing each entry in the
 * zip, and a "end of central directory" signature containing book keeping information.
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
      OutputStream out, long startOffset, Iterable<EntryAccounting> entries) throws IOException {

    int entryCount = 0;
    long size = 0;
    for (EntryAccounting entry : entries) {
      entryCount++;
      size += writeCentralDirectoryFileHeader(out, entry);
    }

    boolean useZip64 = false;

    if (startOffset >= ZipConstants.ZIP64_MAGICVAL) {
      useZip64 = true;
    }
    if (size >= ZipConstants.ZIP64_MAGICVAL) {
      useZip64 = true;
    }
    if (entryCount >= ZipConstants.ZIP64_MAGICCOUNT) {
      useZip64 = true;
    }

    if (useZip64) {
      // Zip64 end of central directory record.
      ByteIo.writeInt(out, ZipConstants.ZIP64_ENDSIG);
      ByteIo.writeLong(out, ZipConstants.ZIP64_ENDHDR - 12);
      // Version made by.
      ByteIo.writeShort(out, 45);
      // Version needed to extract.
      ByteIo.writeShort(out, 45);
      // Number of this disk.
      ByteIo.writeInt(out, 0);
      // Number of the disk with the start of the central directory.
      ByteIo.writeInt(out, 0);
      // Total number of entries in the central directory on this disk.
      ByteIo.writeLong(out, entryCount);
      // Total number of entries in the central directory.
      ByteIo.writeLong(out, entryCount);
      // Size of the central directory.
      ByteIo.writeLong(out, size);
      // Offset of start of central directory.
      ByteIo.writeLong(out, startOffset);

      // Zip64 end of central directory locator.
      ByteIo.writeInt(out, ZipConstants.ZIP64_LOCSIG);
      // number of the disk with the start of the zip64 end of central directory.
      ByteIo.writeInt(out, 0);
      // relative offset of the zip64 end of central directory record.
      ByteIo.writeLong(out, startOffset + size);
      // total number of disks.
      ByteIo.writeInt(out, 1);
    }

    // End of central directory record.
    ByteIo.writeInt(out, ZipEntry.ENDSIG);
    // Number of this disk (with end of central directory)
    ByteIo.writeShort(out, 0);
    // Number of disk on which central directory starts.
    ByteIo.writeShort(out, 0);
    // Number of central directory entries in this disk.
    ByteIo.writeShort(out, Math.min(entryCount, ZipConstants.ZIP64_MAGICCOUNT));
    // Number of central directory entries.
    ByteIo.writeShort(out, Math.min(entryCount, ZipConstants.ZIP64_MAGICCOUNT));
    // Size of the central directory in bytes.
    ByteIo.writeInt(out, Math.min(size, ZipConstants.ZIP64_MAGICVAL));
    // Offset of the start of the central directory.
    ByteIo.writeInt(out, Math.min(startOffset, ZipConstants.ZIP64_MAGICVAL));
    // Size of the comment (we don't have one)
    ByteIo.writeShort(out, 0);
  }

  /** Each entry requires a description of that entry to be contained in the central directory. */
  private long writeCentralDirectoryFileHeader(OutputStream out, EntryAccounting entry)
      throws IOException {
    int zip64DataLength = 0;
    if (entry.getSize() >= ZipConstants.ZIP64_MAGICVAL) {
      zip64DataLength += 8;
    }
    if (entry.getCompressedSize() >= ZipConstants.ZIP64_MAGICVAL) {
      zip64DataLength += 8;
    }
    if (entry.getOffset() >= ZipConstants.ZIP64_MAGICVAL) {
      zip64DataLength += 8;
    }

    int extraDataLength;
    if (zip64DataLength > 0) {
      extraDataLength = zip64DataLength + 4;
    } else {
      extraDataLength = 0;
    }

    boolean useZip64 = zip64DataLength > 0;

    long size = 0;
    size += ByteIo.writeInt(out, ZipEntry.CENSIG);
    // version made by.
    size += ByteIo.writeShort(out, entry.getRequiredExtractVersion(useZip64));
    // version to extract with.
    size += ByteIo.writeShort(out, entry.getRequiredExtractVersion(useZip64));
    size += ByteIo.writeShort(out, entry.getFlags());
    // Compression.
    size += ByteIo.writeShort(out, entry.getCompressionMethod());
    // Modification time.
    size += ByteIo.writeInt(out, entry.getTime());
    size += ByteIo.writeInt(out, entry.getCrc());
    size += ByteIo.writeInt(out, Math.min(entry.getCompressedSize(), ZipConstants.ZIP64_MAGICVAL));
    size += ByteIo.writeInt(out, Math.min(entry.getSize(), ZipConstants.ZIP64_MAGICVAL));

    byte[] nameBytes = entry.getName().getBytes(Charsets.UTF_8);
    long externalAttributes = entry.getExternalAttributes();
    // Length of name.
    size += ByteIo.writeShort(out, nameBytes.length);
    // Length of extra data.
    size += ByteIo.writeShort(out, extraDataLength);
    // Length of file comment.
    size += ByteIo.writeShort(out, 0);
    // Disk on which file starts.
    size += ByteIo.writeShort(out, 0);
    // internal file attributes (unknown)
    size += ByteIo.writeShort(out, 0);
    // external file attributes
    size += ByteIo.writeInt(out, externalAttributes);
    // Offset of local file header.
    size += ByteIo.writeInt(out, Math.min(entry.getOffset(), ZipConstants.ZIP64_MAGICVAL));
    out.write(nameBytes);
    size += nameBytes.length;

    if (useZip64) {
      size += ByteIo.writeShort(out, ZipConstants.ZIP64_EXTID);
      size += ByteIo.writeShort(out, zip64DataLength);
      if (entry.getSize() >= ZipConstants.ZIP64_MAGICVAL) {
        size += ByteIo.writeLong(out, entry.getSize());
      }
      if (entry.getCompressedSize() >= ZipConstants.ZIP64_MAGICVAL) {
        size += ByteIo.writeLong(out, entry.getCompressedSize());
      }
      if (entry.getOffset() >= ZipConstants.ZIP64_MAGICVAL) {
        size += ByteIo.writeLong(out, entry.getOffset());
      }
    }

    return size;
  }
}
