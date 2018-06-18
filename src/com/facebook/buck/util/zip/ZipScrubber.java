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

package com.facebook.buck.util.zip;

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
public class ZipScrubber {
  private ZipScrubber() {}

  private static final long ZIP64_ENDSIG = 0x06064b50L;

  private static final int EXTENDED_TIMESTAMP_ID = 0x5455;

  private static void check(boolean expression, String msg) throws IOException {
    if (!expression) {
      throw new IOException(msg);
    }
  }

  public static void scrubZip(Path zipPath) throws IOException {
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

      long cdEntries = Short.toUnsignedLong(map.getShort(eocdOffset + ZipEntry.ENDTOT));
      if ((cdEntries & 0xffff) == 0xffff) {
        // It's ZIP64 format and number of entries is stored in a different
        // field: https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
        int zip64eocdOffset = eocdOffset;
        while (map.getInt(zip64eocdOffset) != ZIP64_ENDSIG) {
          zip64eocdOffset--;
        }
        // 32 = 4 + 8 + 2 + 2 + 4 + 4 + 8
        cdEntries = map.getLong(zip64eocdOffset + 32);
      }

      int cdOffset = map.getInt(eocdOffset + ZipEntry.ENDOFF);
      for (long idx = 0; idx < cdEntries; idx++) {
        // Wrap the central directory header and zero out it's timestamp.
        ByteBuffer entry = slice(map, cdOffset);
        check(entry.getInt(0) == ZipEntry.CENSIG, "expected central directory header signature");

        entry.putInt(ZipEntry.CENTIM, ZipConstants.DOS_FAKE_TIME);
        ByteBuffer localEntry = slice(map, entry.getInt(ZipEntry.CENOFF));
        scrubLocalEntry(localEntry);
        scrubExtraFields(
            slice(entry, ZipEntry.CENHDR + entry.getShort(ZipEntry.CENNAM)),
            entry.getShort(ZipEntry.CENEXT));

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
