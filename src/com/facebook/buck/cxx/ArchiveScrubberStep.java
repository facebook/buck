/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Scrub any non-deterministic meta-data from the given archive (e.g. timestamp, UID, GID).
 */
public class ArchiveScrubberStep implements Step {

  private static final byte[] GLOBAL_HEADER =
      String.format("!<arch>%s", System.lineSeparator()).getBytes(Charsets.US_ASCII);
  private static final byte[] FILE_MAGIC = {0x60, 0x0A};

  private final Path archive;

  public ArchiveScrubberStep(Path archive) {
    this.archive = Preconditions.checkNotNull(archive);
  }

  private byte[] getBytes(ByteBuffer buffer, int len) {
    byte[] bytes = new byte[len];
    buffer.get(bytes);
    return bytes;
  }

  private int getDecimalStringAsInt(ByteBuffer buffer, int len) {
    byte[] bytes = getBytes(buffer, len);
    String str = new String(bytes, Charsets.US_ASCII);
    return Integer.parseInt(str.trim());
  }

  private void putIntAsDecimalString(ByteBuffer buffer, int len, int value) {
    String val = String.format("%d", value);
    Preconditions.checkState(val.length() <= len);
    val = Strings.repeat(" ", len - val.length()) + val;
    buffer.put(val.getBytes(Charsets.US_ASCII));
  }

  private void checkArchive(boolean expression, String msg) throws ArchiveException {
    if (!expression) {
      throw new ArchiveException(msg);
    }
  }

  /**
   * Efficiently modifies the archive backed by the given buffer to remove any non-deterministic
   * meta-data such as timestamps, UIDs, and GIDs.
   * @param archive a {@link ByteBuffer} wrapping the contents of the archive.
   */
  private void scrubArchive(ByteBuffer archive) throws ArchiveException {
    try {

      // Grab the global header chunk and verify it's accurate.
      byte[] globalHeader = getBytes(archive, GLOBAL_HEADER.length);
      checkArchive(
          Arrays.equals(GLOBAL_HEADER, globalHeader),
          "invalid global header");

      // Iterate over all the file meta-data entries, injecting zero's for timestamp,
      // UID, and GID.
      while (archive.hasRemaining()) {
        /* File name */ getBytes(archive, 16);

        // Inject 0's for the non-deterministic meta-data entries.
        /* File modification timestamp */ putIntAsDecimalString(archive, 12, 0);
        /* Owner ID */ putIntAsDecimalString(archive, 6, 0);
        /* Group ID */ putIntAsDecimalString(archive, 6, 0);

        /* File mode */ getBytes(archive, 8);
        int fileSize = getDecimalStringAsInt(archive, 10);

        // Lastly, grab the file magic entry and verify it's accurate.
        byte[] fileMagic = getBytes(archive, 2);
        checkArchive(
            Arrays.equals(FILE_MAGIC, fileMagic),
            "invalid file magic");

        // Skip the file data.
        archive.position(archive.position() + fileSize + fileSize % 2);
      }

      // Convert any low-level exceptions to `ArchiveExceptions`s.
    } catch (BufferUnderflowException | ReadOnlyBufferException e) {
      throw new ArchiveException(e.getMessage());
    }
  }

  private FileChannel readWriteChannel(Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    Path archivePath = context.getProjectFilesystem().resolve(archive);
    try {
      try (FileChannel channel = readWriteChannel(archivePath)) {
        MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
        scrubArchive(map);
      }
    } catch (IOException | ArchiveException e) {
      context.logError(e, "Error scrubbing non-deterministic metadata from %s", archivePath);
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "archive-scrub";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "archive-scrub";
  }

  @SuppressWarnings("serial")
  public static class ArchiveException extends Exception {
    public ArchiveException(String msg) {
      super(msg);
    }
  }

}
