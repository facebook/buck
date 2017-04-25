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

import com.facebook.buck.timing.Clock;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

/**
 * A wrapper containing the {@link ZipEntry} and additional book keeping information required to
 * write the entry to a zip file.
 */
class EntryAccounting {

  private static final ThreadLocal<Calendar> CALENDAR =
      new ThreadLocal<Calendar>() {
        @Override
        protected Calendar initialValue() {
          // We explicitly use the US locale to get a Gregorian calendar (zip file timestamps
          // are encoded using the year, month, date, etc. in the Gregorian calendar).
          return Calendar.getInstance(Locale.US);
        }
      };

  private static final int DATA_DESCRIPTOR_FLAG = 1 << 3;
  private static final int UTF8_NAMES_FLAG = 1 << 11;
  private static final int ARBITRARY_SIZE = 1024;
  private static final byte[] emptyBytes = new byte[] {};

  private final ZipEntry entry;
  private final Method method;
  private Hasher crc = Hashing.crc32().newHasher();
  private long offset;
  private long length = 0;
  private long externalAttributes = 0;

  /**
   * General purpose bit flag: Bit 00: encrypted file Bit 01: compression option Bit 02: compression
   * option Bit 03: data descriptor Bit 04: enhanced deflation Bit 05: compressed patched data Bit
   * 06: strong encryption Bit 07-10: unused Bit 11: language encoding Bit 12: reserved Bit 13: mask
   * header values Bit 14-15: reserved
   *
   * <p>The important one is bit 3: the data descriptor. Defaults to indicate that names are stored
   * as UTF8.
   */
  private int flags = UTF8_NAMES_FLAG;

  private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
  private final byte[] buffer = new byte[ARBITRARY_SIZE];

  public EntryAccounting(Clock clock, ZipEntry entry, long currentOffset) {
    this.entry = entry;
    this.method = Method.detect(entry.getMethod());
    this.offset = currentOffset;

    if (entry.getTime() == -1) {
      entry.setTime(clock.currentTimeMillis());
    }

    if (entry instanceof CustomZipEntry) {
      deflater.setLevel(((CustomZipEntry) entry).getCompressionLevel());
      externalAttributes = ((CustomZipEntry) entry).getExternalAttributes();
    }
  }

  /** @return The time of the entry in DOS format. */
  public long getTime() {
    // Calendar objects aren't thread-safe, but they're quite expensive to create, so we'll re-use
    // them per thread.
    Calendar instance = CALENDAR.get();
    instance.setTimeInMillis(entry.getTime());

    int year = instance.get(Calendar.YEAR);

    // The DOS epoch begins in 1980. If the year is before that, then default to the start of the
    // epoch (the 1st day of the 1st month)
    if (year < 1980) {
      return ZipConstants.DOS_FAKE_TIME;
    }
    return (year - 1980) << 25
        | (instance.get(Calendar.MONTH) + 1) << 21
        | instance.get(Calendar.DAY_OF_MONTH) << 16
        | instance.get(Calendar.HOUR_OF_DAY) << 11
        | instance.get(Calendar.MINUTE) << 5
        | instance.get(Calendar.SECOND) >> 1;
  }

  public String getName() {
    return entry.getName();
  }

  public long getSize() {
    return entry.getSize();
  }

  public long getCompressedSize() {
    return entry.getCompressedSize();
  }

  public int getFlags() {
    return flags;
  }

  public long getOffset() {
    return offset;
  }

  public void setOffset(long offset) {
    this.offset = offset;
  }

  public long getCrc() {
    return entry.getCrc();
  }

  public int getCompressionMethod() {
    return method.compressionMethod;
  }

  public int getRequiredExtractVersion() {
    int requiredExtractVersion = method.requiredVersion;
    // Set the creator system indicator if we have UNIX-style file attributes.
    // http://forensicswiki.org/wiki/Zip#External_file_attributes
    if (externalAttributes >= (1 << 16)) {
      requiredExtractVersion |= (3 << 8);
    }
    return requiredExtractVersion;
  }

  public long getExternalAttributes() {
    return externalAttributes;
  }

  public long writeLocalFileHeader(OutputStream out) throws IOException {
    if (method == Method.DEFLATE && entry instanceof CustomZipEntry) {
      // See http://www.pkware.com/documents/casestudies/APPNOTE.TXT (section 4.4.4)
      // Essentially, we're about to set bits 1 and 2 to indicate to tools such as zipinfo which
      // level of compression we're using. If we've not set a compression level, then we're using
      // the default one, which is right. It turns out. For your viewing pleasure:
      //
      // +----------+-------+-------+
      // | Level    | Bit 1 | Bit 2 |
      // +----------+-------+-------+
      // | Fastest  |   0   |   1   |
      // | Normal   |   0   |   0   |
      // | Best     |   1   |   0   |
      // +----------+-------+-------+
      int level = ((CustomZipEntry) entry).getCompressionLevel();
      switch (level) {
        case Deflater.BEST_COMPRESSION:
          flags |= (1 << 1);
          break;

        case Deflater.BEST_SPEED:
          flags |= (1 << 2);
          break;
      }
    }

    if (requiresDataDescriptor()) {
      flags |= DATA_DESCRIPTOR_FLAG;
    }

    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      ByteIo.writeInt(stream, ZipEntry.LOCSIG);

      ByteIo.writeShort(stream, getRequiredExtractVersion());
      ByteIo.writeShort(stream, flags);
      ByteIo.writeShort(stream, getCompressionMethod());
      ByteIo.writeInt(stream, getTime());

      // If we don't know the size or CRC of the data in advance (such as when in deflate mode),
      // we write zeros now, and append the actual values (the data descriptor) after the entry
      // bytes has been fully written.
      if (requiresDataDescriptor()) {
        ByteIo.writeInt(stream, 0);
        ByteIo.writeInt(stream, 0);
        ByteIo.writeInt(stream, 0);
      } else {
        ByteIo.writeInt(stream, entry.getCrc());
        ByteIo.writeInt(stream, entry.getSize());
        ByteIo.writeInt(stream, entry.getSize());
      }

      byte[] nameBytes = entry.getName().getBytes(Charsets.UTF_8);
      ByteIo.writeShort(stream, nameBytes.length);
      ByteIo.writeShort(stream, 0);
      stream.write(nameBytes);

      byte[] bytes = stream.toByteArray();
      out.write(bytes);
      return bytes.length;
    }
  }

  private byte[] getDataDescriptor() throws IOException {
    if (!requiresDataDescriptor()) {
      return emptyBytes;
    }

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ByteIo.writeInt(out, ZipEntry.EXTSIG);
      ByteIo.writeInt(out, getCrc());
      ByteIo.writeInt(out, getCompressedSize());
      ByteIo.writeInt(out, getSize());
      return out.toByteArray();
    }
  }

  private int deflate(OutputStream out) throws IOException {
    int written = deflater.deflate(buffer, 0, buffer.length);
    if (written > 0) {
      out.write(Arrays.copyOf(buffer, written));
    }
    return written;
  }

  public void write(OutputStream out, byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return;
    }
    updateCrc(b, off, len);

    if (method == Method.STORE) {
      out.write(b, off, len);
      length += len;
    } else if (method == Method.DEFLATE) {
      Preconditions.checkState(!deflater.finished());
      deflater.setInput(b, off, len);
      while (!deflater.needsInput()) {
        deflate(out);
      }
    }
  }

  /**
   * Finish the entry and return the total number of compressed bytes written (not counting the
   * local file header, but counting the data descriptor if present). Must be called exactly once.
   */
  public long finish(OutputStream out) throws IOException {
    if (method == Method.STORE) {
      Preconditions.checkState(
          entry.getSize() == length && entry.getCompressedSize() == length,
          "Number of bytes written differs from what is specified in the entry.");
      Preconditions.checkState(
          entry.getCrc() == calculateCrc(),
          "CRC of bytes written differs from what is specified in the entry.");
    } else if (method == Method.DEFLATE) {
      deflater.finish();
      while (!deflater.finished()) {
        deflate(out);
      }
      entry.setSize(deflater.getBytesRead());
      entry.setCompressedSize(deflater.getBytesWritten());
      entry.setCrc(calculateCrc());
    }

    // regardless of the method used, end the deflater to free native resources.
    deflater.end();

    // write the data descriptor if required
    byte[] dataDescriptor = getDataDescriptor();
    out.write(dataDescriptor);

    return entry.getCompressedSize() + dataDescriptor.length;
  }

  private boolean requiresDataDescriptor() {
    return method == Method.DEFLATE;
  }

  private void updateCrc(byte[] b, int off, int len) {
    crc = crc.putBytes(b, off, len);
  }

  private long calculateCrc() {
    return crc.hash().padToLong();
  }

  private enum Method {
    DEFLATE(20, 8),
    STORE(10, 0),
    ;

    private final int requiredVersion;
    private final int compressionMethod;

    Method(int requiredVersion, int compressionMethod) {
      this.requiredVersion = requiredVersion;
      this.compressionMethod = compressionMethod;
    }

    public static Method detect(int fromZipMethod) {
      switch (fromZipMethod) {
        case -1:
          return DEFLATE;
        case ZipEntry.DEFLATED:
          return DEFLATE;
        case ZipEntry.STORED:
          return STORE;
        default:
          throw new IllegalArgumentException("Cannot determine zip method from: " + fromZipMethod);
      }
    }
  }
}
