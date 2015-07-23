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

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileScrubber;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class ObjectFileScrubbers {

  public static final byte[] END_OF_FILE_HEADER_MARKER = {0x60, 0x0A};

  private ObjectFileScrubbers() {}

  public static FileScrubber createDateUidGidScrubber(final byte[] expectedGlobalHeader) {
    return new FileScrubber() {

      /**
       * Efficiently modifies the archive backed by the given buffer to remove any non-deterministic
       * meta-data such as timestamps, UIDs, and GIDs.
       */
      @SuppressWarnings("PMD.AvoidUsingOctalValues")
      @Override
      public void scrubFile(FileChannel file) throws IOException, ScrubException {
        MappedByteBuffer map = file.map(FileChannel.MapMode.READ_WRITE, 0, file.size());
        try {

          // Grab the global header chunk and verify it's accurate.
          byte[] globalHeader = getBytes(map, expectedGlobalHeader.length);
          checkArchive(
              Arrays.equals(expectedGlobalHeader, globalHeader),
              "invalid global header");

          // Iterate over all the file meta-data entries, injecting zero's for timestamp,
          // UID, and GID.
          while (map.hasRemaining()) {
        /* File name */ getBytes(map, 16);

            // Inject 0's for the non-deterministic meta-data entries.
        /* File modification timestamp */ putIntAsDecimalString(map, 12, 0);
        /* Owner ID */ putIntAsDecimalString(map, 6, 0);
        /* Group ID */ putIntAsDecimalString(map, 6, 0);

        /* File mode */ putIntAsOctalString(map, 8, 0100644);
            int fileSize = getDecimalStringAsInt(map, 10);

            // Lastly, grab the file magic entry and verify it's accurate.
            byte[] fileMagic = getBytes(map, 2);
            checkArchive(
                Arrays.equals(END_OF_FILE_HEADER_MARKER, fileMagic),
                "invalid file magic");

            // Skip the file data.
            map.position(map.position() + fileSize + fileSize % 2);
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

  public static int getLittleEndian32BitLong(ByteBuffer buffer) {
    byte[] bytes = getBytes(buffer, 4);
    return bytes[3] << 24 | (bytes[2] & 0xFF) << 16 | (bytes[1] & 0xFF) << 8 | (bytes[0] & 0xFF);
  }

  public static void putSpaceLeftPaddedString(ByteBuffer buffer, int len, String value) {
    Preconditions.checkState(value.length() <= len);
    value = Strings.padStart(value, len, ' ');
    buffer.put(value.getBytes(Charsets.US_ASCII));
  }

  public static void putBytes(ByteBuffer buffer, byte[] bytes) {
    buffer.put(bytes);
  }

  public static void putIntAsOctalString(ByteBuffer buffer, int len, int value) {
    putSpaceLeftPaddedString(buffer, len, String.format("0%o", value));
  }

  public static void putIntAsDecimalString(ByteBuffer buffer, int len, int value) {
    putSpaceLeftPaddedString(buffer, len, String.format("%d", value));
  }

  public static void checkArchive(boolean expression, String msg)
      throws FileScrubber.ScrubException {
    if (!expression) {
      throw new FileScrubber.ScrubException(msg);
    }
  }

}
