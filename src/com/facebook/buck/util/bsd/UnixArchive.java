/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.util.bsd;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Supplier;

public class UnixArchive {
  private static final byte[] EXPECTED_GLOBAL_HEADER =
      "!<arch>\n".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ENTRY_MARKER = "#1/".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] END_OF_HEADER_MAGIC = {0x60, 0x0A};

  private static final int LENGTH_OF_FILENAME_SIZE = 13;
  private static final int MODIFICATION_TIME_SIZE = 12;
  private static final int OWNER_ID_SIZE = 6;
  private static final int GROUP_ID_SIZE = 6;
  private static final int FILE_MODE_SIZE = 8;
  private static final int FILE_AND_FILENAME_SIZE = 10;
  private static final int END_OF_HEADER_MAGIC_SIZE = END_OF_HEADER_MAGIC.length;

  private final FileChannel fileChannel;
  private final ByteBuffer buffer;
  private final Supplier<ImmutableList<UnixArchiveEntry>> entries;
  private final NulTerminatedCharsetDecoder nulTerminatedCharsetDecoder;

  public UnixArchive(
      FileChannel fileChannel, NulTerminatedCharsetDecoder nulTerminatedCharsetDecoder)
      throws IOException {
    this.fileChannel = fileChannel;
    this.buffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileChannel.size());
    if (!checkHeader(buffer)) {
      throw new IOException("Archive file has unexpected header");
    }
    this.entries = MoreSuppliers.memoize(this::loadEntries);
    this.nulTerminatedCharsetDecoder = nulTerminatedCharsetDecoder;
  }

  public static boolean checkHeader(ByteBuffer byteBuffer) {
    byte[] header = new byte[EXPECTED_GLOBAL_HEADER.length];
    byteBuffer.get(header, 0, header.length);
    return Arrays.equals(EXPECTED_GLOBAL_HEADER, header);
  }

  public ImmutableList<UnixArchiveEntry> getEntries() {
    return entries.get();
  }

  public MappedByteBuffer getMapForEntry(UnixArchiveEntry entry) throws IOException {
    long start = entry.getFileOffset();
    long len = entry.getFileSize();
    return fileChannel.map(FileChannel.MapMode.READ_WRITE, start, len);
  }

  public void close() throws IOException {
    fileChannel.close();
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  private ImmutableList<UnixArchiveEntry> loadEntries() {
    int offset = EXPECTED_GLOBAL_HEADER.length;
    int headerOffset = offset;

    ImmutableList.Builder<UnixArchiveEntry> builder = ImmutableList.builder();

    CharsetDecoder decoder = StandardCharsets.US_ASCII.newDecoder();

    while (true) {
      buffer.position(offset);

      byte[] markerBytes = new byte[ENTRY_MARKER.length];
      buffer.get(markerBytes, 0, markerBytes.length);
      if (!Arrays.equals(markerBytes, ENTRY_MARKER)) {
        throw new HumanReadableException("Unknown entry marker");
      }

      int filenameLength = getIntFromStringAtRange(LENGTH_OF_FILENAME_SIZE, decoder);
      long fileModification = getLongFromStringAtRange(MODIFICATION_TIME_SIZE, decoder);
      int ownerId = getIntFromStringAtRange(OWNER_ID_SIZE, decoder);
      int groupId = getIntFromStringAtRange(GROUP_ID_SIZE, decoder);
      int fileMode = getIntFromStringAtRange(FILE_MODE_SIZE, decoder);
      int fileAndFilenameSize = getIntFromStringAtRange(FILE_AND_FILENAME_SIZE, decoder);

      byte[] magic = new byte[END_OF_HEADER_MAGIC_SIZE];
      buffer.get(magic, 0, magic.length);
      if (!Arrays.equals(magic, END_OF_HEADER_MAGIC)) {
        throw new HumanReadableException("Unknown file magic");
      }

      long fileSizeWithoutFilename = fileAndFilenameSize - filenameLength;

      offset = buffer.position();
      String filename;
      try {
        filename = nulTerminatedCharsetDecoder.decodeString(buffer);
      } catch (CharacterCodingException e) {
        throw new HumanReadableException(
            e, "Unable to read filename from buffer starting at %d", offset);
      }
      offset += filenameLength;

      builder.add(
          UnixArchiveEntry.of(
              filenameLength,
              fileModification,
              ownerId,
              groupId,
              fileMode,
              fileSizeWithoutFilename,
              filename,
              headerOffset,
              offset - headerOffset,
              offset));
      offset += fileSizeWithoutFilename;

      if (offset == buffer.capacity()) {
        break;
      }
    }

    return builder.build();
  }

  @VisibleForTesting
  static String readStringWithLength(ByteBuffer buffer, int length, CharsetDecoder charsetDecoder)
      throws CharacterCodingException {
    int oldLimit = buffer.limit();

    buffer.limit(buffer.position() + length);
    charsetDecoder.reset();
    String result = charsetDecoder.decode(buffer).toString();

    buffer.limit(oldLimit);
    return result;
  }

  private int getIntFromStringAtRange(int len, CharsetDecoder decoder) {
    String filenameLengthString;
    int offset = buffer.position();
    try {
      filenameLengthString = readStringWithLength(buffer, len, decoder);
    } catch (CharacterCodingException e) {
      throw new HumanReadableException(
          e, "Unable to read int from buffer (range %d..%d)", offset, offset + len);
    }
    return Integer.parseInt(filenameLengthString.trim());
  }

  private long getLongFromStringAtRange(int len, CharsetDecoder decoder) {
    String filenameLengthString;
    int offset = buffer.position();
    try {
      filenameLengthString = readStringWithLength(buffer, len, decoder);
    } catch (CharacterCodingException e) {
      throw new HumanReadableException(
          e, "Unable to read long from buffer (range %d..%d)", offset, offset + len);
    }
    return Long.parseLong(filenameLengthString.trim());
  }
}
