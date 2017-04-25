/*
 * Copyright 2015-present Facebook, Inc.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;

/** Tool to eliminate non-deterministic or problematic bits of zip files. */
class ZipScrubber {
  private ZipScrubber() {}

  private static final int EXTENDED_TIMESTAMP_ID = 0x5455;
  private static final int DATA_DESCRIPTOR_BIT_FLAG = 0x0008;

  private static void check(boolean expression, String msg) throws IOException {
    if (!expression) {
      throw new IOException(msg);
    }
  }

  static void scrubZip(Path zipPath) throws IOException {
    try (FileChannel channel =
        FileChannel.open(zipPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
      map.order(ByteOrder.LITTLE_ENDIAN);

      // Search backwards from the end of the ZIP file, searching for the EOCD signature, which
      // designates the start of the EOCD.
      int eocdOffset = (int) channel.size() - ZipEntry.ENDHDR;
      while (map.getInt(eocdOffset) != ZipEntry.ENDSIG) {
        eocdOffset--;
      }

      int cdEntries = map.getShort(eocdOffset + ZipEntry.ENDTOT);
      int cdOffset = map.getInt(eocdOffset + ZipEntry.ENDOFF);

      for (int idx = 0; idx < cdEntries; idx++) {
        // Wrap the central directory header and zero out it's timestamp.
        ByteBuffer entry = slice(map, cdOffset);
        check(entry.getInt(0) == ZipEntry.CENSIG, "expected central directory header signature");

        entry.putInt(ZipEntry.CENTIM, ZipConstants.DOS_FAKE_TIME);
        ByteBuffer localEntry = slice(map, entry.getInt(ZipEntry.CENOFF));
        scrubLocalEntry(localEntry);
        scrubExtraFields(
            slice(entry, ZipEntry.CENHDR + entry.getShort(ZipEntry.CENNAM)),
            entry.getShort(ZipEntry.CENEXT));
        if (idx == cdEntries - 1) {
          // Only run this on the last entry.
          populateLocalEntryIfNecessary(entry, localEntry);
        }

        cdOffset +=
            ZipEntry.CENHDR
                + entry.getShort(ZipEntry.CENNAM)
                + entry.getShort(ZipEntry.CENEXT)
                + entry.getShort(ZipEntry.CENCOM);
      }
    }
  }

  private static ByteBuffer slice(ByteBuffer map, int offset) {
    ByteBuffer result = map.duplicate();
    result.position(offset);
    result = result.slice();
    result.order(ByteOrder.LITTLE_ENDIAN);
    return result;
  }

  private static void scrubLocalEntry(ByteBuffer entry) throws IOException {
    check(entry.getInt(0) == ZipEntry.LOCSIG, "expected local header signature");
    entry.putInt(ZipEntry.LOCTIM, ZipConstants.DOS_FAKE_TIME);
    scrubExtraFields(
        slice(entry, ZipEntry.LOCHDR + entry.getShort(ZipEntry.LOCNAM)),
        entry.getShort(ZipEntry.LOCEXT));
  }

  private static void scrubExtraFields(ByteBuffer data, short length) {
    // See http://mdfs.net/Docs/Comp/Archiving/Zip/ExtraField for structure of extra fields.
    int end = data.position() + length;
    while (data.position() < end) {
      int id = data.getShort();
      int size = data.getShort();

      if (id == EXTENDED_TIMESTAMP_ID) {
        // 1 byte flag
        // 0-3 4-byte unix timestamps
        data.get(); // ignore flags
        size -= 1;
        while (size > 0) {
          data.putInt((int) (ZipConstants.getFakeTime() / 1000));
          size -= 4;
        }
      } else {
        if (data.position() + size >= end) {
          break;
        }
        data.position(data.position() + size);
      }
    }
  }

  /**
   * Android's libziparchive produces zip files that only store the file size in the central
   * directory and data descriptor, not in the local file header. ZipInputStream doesn't tolerate
   * this for STORED files, so this step will (1) identify whether the file is STORED with a data
   * descriptor, (2) validate the DD against the central directory entry, (3) copy those values to
   * the local file header, (4) clear the DD bit.
   *
   * <p>We leave the data descriptor as garbage data between entries, WHICH IS A PROBLEM for
   * ZipInputStream, which can't tolerate that garbage (it views it as end-of-file). Therefore, we
   * only call this on the last file in the zip (which is the only time it is needed for aapt2
   * output).
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  private static void populateLocalEntryIfNecessary(ByteBuffer centralEntry, ByteBuffer localEntry)
      throws IOException {

    // Check to see if we even need to do anything.
    if (localEntry.getShort(ZipEntry.LOCHOW) != ZipEntry.STORED
        || (localEntry.getShort(ZipEntry.LOCFLG) & DATA_DESCRIPTOR_BIT_FLAG) == 0) {
      return;
    }

    // Load the data from the central directory.
    int crc = centralEntry.getInt(ZipEntry.CENCRC);
    int csize = centralEntry.getInt(ZipEntry.CENSIZ);
    int usize = centralEntry.getInt(ZipEntry.CENLEN);
    if (csize != usize) {
      throw new IOException("Compressed and uncompressed size mismatch for STORED entry.");
    }

    // Load the data from the data descriptor as a double-check.
    int dataDescriptorOffset =
        ZipEntry.LOCHDR
            + localEntry.getShort(ZipEntry.LOCNAM)
            + localEntry.getShort(ZipEntry.LOCEXT)
            + csize;
    ByteBuffer extBuffer = slice(localEntry, dataDescriptorOffset);
    int extsig = extBuffer.getInt(0);
    if (extsig != ZipEntry.EXTSIG) {
      throw new IOException("No EXT sig.  Too dangerous to proceed.");
    }
    int extcrc = extBuffer.getInt(ZipEntry.EXTCRC);
    int extcsize = extBuffer.getInt(ZipEntry.EXTSIZ);
    int extusize = extBuffer.getInt(ZipEntry.EXTLEN);
    if (extcrc != crc) {
      throw new IOException("CRC mismatch between central entry and data descriptor");
    }
    if (extcsize != csize) {
      throw new IOException("Size mismatch between central entry and data descriptor");
    }
    if (extusize != usize) {
      throw new IOException("Length mismatch between central entry and data descriptor");
    }

    // Write the data into the local entry.
    localEntry.putInt(ZipEntry.LOCCRC, crc);
    localEntry.putInt(ZipEntry.LOCSIZ, csize);
    localEntry.putInt(ZipEntry.LOCLEN, usize);
    localEntry.putShort(
        ZipEntry.LOCFLG,
        (short) (localEntry.getShort(ZipEntry.LOCFLG) & ~DATA_DESCRIPTOR_BIT_FLAG));
  }

  /** Read the name of a zip file from a local entry. Useful for debugging. */
  @SuppressWarnings("unused")
  private static String localEntryName(ByteBuffer entry) {
    byte[] nameBytes = new byte[entry.getShort(ZipEntry.LOCNAM)];
    ((ByteBuffer) entry.slice().position(ZipEntry.LOCHDR)).get(nameBytes);
    return new String(nameBytes);
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("usage: ZipScrubberCli file-to-scrub-in-place.zip");
      System.exit(2);
    }

    scrubZip(Paths.get(args[0]));
  }
}
