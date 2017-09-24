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

package com.facebook.buck.cxx.toolchain.objectfile;

import com.facebook.buck.io.file.FileContentsScrubber;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.common.primitives.Shorts;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class ObjectFileScrubbers {

  private static final int GLOBAL_HEADER_SIZE = 8;
  private static final ImmutableSet<String> SPECIAL_ENTRIES = ImmutableSet.of("/", "//");
  public static final byte[] GLOBAL_HEADER = "!<arch>\n".getBytes(Charsets.US_ASCII);
  public static final byte[] GLOBAL_THIN_HEADER = "!<thin>\n".getBytes(Charsets.US_ASCII);
  public static final byte[] END_OF_FILE_HEADER_MARKER = {0x60, 0x0A};

  public enum PaddingStyle {
    LEFT,
    RIGHT,
  };

  private ObjectFileScrubbers() {}

  private static boolean checkHeader(byte[] header) throws FileContentsScrubber.ScrubException {
    checkArchive(
        Arrays.equals(GLOBAL_HEADER, header) || Arrays.equals(GLOBAL_THIN_HEADER, header),
        "invalid global header");
    return Arrays.equals(GLOBAL_THIN_HEADER, header);
  }

  public static FileContentsScrubber createDateUidGidScrubber(final PaddingStyle paddingStyle) {
    return new FileContentsScrubber() {

      /**
       * Efficiently modifies the archive backed by the given buffer to remove any non-deterministic
       * meta-data such as timestamps, UIDs, and GIDs.
       */
      @SuppressWarnings("PMD.AvoidUsingOctalValues")
      @Override
      public void scrubFile(FileChannel file) throws IOException, ScrubException {
        try {
          ByteBuffer header = ByteBuffer.allocate(GLOBAL_HEADER_SIZE);
          file.read(header);
          // Grab the global header chunk and verify it's accurate.
          header.position(0);
          byte[] globalHeader = getBytes(header, GLOBAL_HEADER_SIZE);
          boolean thin = checkHeader(globalHeader);

          // Iterate over all the file meta-data entries, injecting zero's for timestamp,
          // UID, and GID.
          final int entrySize =
              16 /* fileName */
                  + 12 /* file modification time */
                  + 6 /* owner ID */
                  + 6 /* group ID */
                  + 8 /* file mode */
                  + 10 /* file size */
                  + 2 /* file magic */;

          long start = GLOBAL_HEADER_SIZE;
          ByteBuffer buffer = ByteBuffer.allocate(entrySize);
          while (start < file.size()) {
            checkArchive(file.size() - start >= entrySize, "Invalid entry metadata format");

            buffer.clear();
            file.position(start);
            int read = file.read(buffer);
            checkArchive(read == entrySize, "Not all bytes have been read");

            buffer.position(0); // position points just past the last byte read, so need to reset
            String fileName = new String(getBytes(buffer, 16), Charsets.US_ASCII).trim();

            // Inject 0's for the non-deterministic meta-data entries.
            /* File modification timestamp */ putIntAsDecimalString(
                buffer,
                12,
                ObjectFileCommonModificationDate.COMMON_MODIFICATION_TIME_STAMP,
                paddingStyle);
            /* Owner ID */ putIntAsDecimalString(buffer, 6, 0, paddingStyle);
            /* Group ID */ putIntAsDecimalString(buffer, 6, 0, paddingStyle);

            /* File mode */ putIntAsOctalString(buffer, 8, 0100644, paddingStyle);
            long fileSize = getDecimalStringAsLong(buffer, 10);

            // Lastly, grab the file magic entry and verify it's accurate.
            byte[] fileMagic = getBytes(buffer, 2);
            checkArchive(Arrays.equals(END_OF_FILE_HEADER_MARKER, fileMagic), "invalid file magic");

            // write the changes
            buffer.position(0); // position points just past the last byte accessed, need to reset
            file.position(start);
            int written = file.write(buffer);
            checkArchive(written == entrySize, "Not all bytes have been written");

            // Skip the file data.
            start += entrySize;
            if (!thin || SPECIAL_ENTRIES.contains(fileName)) {
              start += fileSize + fileSize % 2;
            }
          }

          // Convert any low-level exceptions to `ArchiveExceptions`s.
        } catch (BufferUnderflowException | ReadOnlyBufferException e) {
          throw new ScrubException(e.getMessage());
        }
      }
    };
  }

  public static byte[] getBytes(ByteBuffer buffer, int len) {
    byte[] bytes = new byte[len];
    buffer.get(bytes);
    return bytes;
  }

  public static int getOctalStringAsInt(ByteBuffer buffer, int len) {
    byte[] bytes = getBytes(buffer, len);
    String str = new String(bytes, Charsets.US_ASCII);
    return Integer.parseInt(str.trim(), 8);
  }

  public static int getDecimalStringAsInt(ByteBuffer buffer, int len) {
    byte[] bytes = getBytes(buffer, len);
    String str = new String(bytes, Charsets.US_ASCII);
    return Integer.parseInt(str.trim());
  }

  public static long getDecimalStringAsLong(ByteBuffer buffer, int len) {
    byte[] bytes = getBytes(buffer, len);
    String str = new String(bytes, Charsets.US_ASCII).trim();
    return str.isEmpty() ? 0 : Long.parseLong(str.trim());
  }

  public static long getLittleEndianLong(ByteBuffer buffer) {
    byte b1 = buffer.get();
    byte b2 = buffer.get();
    byte b3 = buffer.get();
    byte b4 = buffer.get();
    byte b5 = buffer.get();
    byte b6 = buffer.get();
    byte b7 = buffer.get();
    byte b8 = buffer.get();
    return Longs.fromBytes(b8, b7, b6, b5, b4, b3, b2, b1);
  }

  public static int getLittleEndianInt(ByteBuffer buffer) {
    byte b1 = buffer.get();
    byte b2 = buffer.get();
    byte b3 = buffer.get();
    byte b4 = buffer.get();
    return Ints.fromBytes(b4, b3, b2, b1);
  }

  public static short getLittleEndianShort(ByteBuffer buffer) {
    byte b1 = buffer.get();
    byte b2 = buffer.get();
    return Shorts.fromBytes(b2, b1);
  }

  public static String getAsciiString(ByteBuffer buffer) {
    int position = buffer.position();
    int length = 0;
    do {
      length++;
    } while (buffer.get() != 0x00);
    byte[] bytes = new byte[length - 1];
    buffer.position(position);
    buffer.get(bytes, 0, length - 1);
    return new String(bytes, Charsets.US_ASCII);
  }

  private static void putSpaceLeftPaddedString(ByteBuffer buffer, int len, String value) {
    Preconditions.checkState(value.length() <= len);
    value = Strings.padStart(value, len, ' ');
    buffer.put(value.getBytes(Charsets.US_ASCII));
  }

  private static void putSpaceRightPaddedString(ByteBuffer buffer, int len, String value) {
    Preconditions.checkState(value.length() <= len);
    value = Strings.padEnd(value, len, ' ');
    buffer.put(value.getBytes(Charsets.US_ASCII));
  }

  public static void putBytes(ByteBuffer buffer, byte[] bytes) {
    buffer.put(bytes);
  }

  public static void putIntAsOctalString(
      ByteBuffer buffer, int len, int value, PaddingStyle paddingStyle) {
    if (paddingStyle == PaddingStyle.LEFT) {
      putSpaceLeftPaddedString(buffer, len, String.format("0%o", value));
    } else {
      putSpaceRightPaddedString(buffer, len, String.format("0%o", value));
    }
  }

  public static void putIntAsDecimalString(
      ByteBuffer buffer, int len, int value, PaddingStyle paddingStyle) {
    if (paddingStyle == PaddingStyle.LEFT) {
      putSpaceLeftPaddedString(buffer, len, String.format("%d", value));
    } else {
      putSpaceRightPaddedString(buffer, len, String.format("%d", value));
    }
  }

  public static void putLittleEndianLong(ByteBuffer buffer, long value) {
    byte[] bytes = Longs.toByteArray(value);
    byte[] flipped = {
      bytes[7], bytes[6], bytes[5], bytes[4], bytes[3], bytes[2], bytes[1], bytes[0]
    };
    buffer.put(flipped);
  }

  public static void putLittleEndianInt(ByteBuffer buffer, int value) {
    byte[] bytes = Ints.toByteArray(value);
    byte[] flipped = {bytes[3], bytes[2], bytes[1], bytes[0]};
    buffer.put(flipped);
  }

  public static void putAsciiString(ByteBuffer buffer, String string) {
    byte[] bytes = string.getBytes(Charsets.US_ASCII);
    buffer.put(bytes);
    buffer.put((byte) 0x00);
  }

  public static void checkArchive(boolean expression, String msg)
      throws FileContentsScrubber.ScrubException {
    if (!expression) {
      throw new FileContentsScrubber.ScrubException(msg);
    }
  }
}
