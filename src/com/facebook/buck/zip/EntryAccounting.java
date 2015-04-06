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
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

/**
 * A wrapper containing the {@link ZipEntry} and additional book keeping information required to
 * write the entry to a zip file.
 */
class EntryAccounting {
  private static final int DATA_DESCRIPTOR_FLAG = 1 << 3;
  private static final int UTF8_NAMES_FLAG = 1 << 11;
  private static final int ARBITRARY_SIZE = 1024;
  private static final long DOS_EPOCH_START = (1 << 21) | (1 << 16);

  private final ZipEntry entry;
  private final Method method;
  private Hasher crc = Hashing.crc32().newHasher();
  private long offset;
  private long externalAttributes = 0;

  /*
   * General purpose bit flag:
   *  Bit 00: encrypted file
   *  Bit 01: compression option
   *  Bit 02: compression option
   *  Bit 03: data descriptor
   *  Bit 04: enhanced deflation
   *  Bit 05: compressed patched data
   *  Bit 06: strong encryption
   *  Bit 07-10: unused
   *  Bit 11: language encoding
   *  Bit 12: reserved
   *  Bit 13: mask header values
   *  Bit 14-15: reserved
   *
   *  The important one is bit 3: the data descriptor.
   *  Defaults to indicate that names are stored as UTF8.
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

  public void updateCrc(byte[] b, int off, int len) {
    crc = crc.putBytes(b, off, len);
  }

  /**
   * @return The time of the entry in DOS format.
   */
  public long getTime() {
    // It'd be nice to use a Calendar for this, but (and here's the fun bit), that's a Really Bad
    // Idea since the calendar's internal time representation keeps ticking once set. Instead, do
    // this long way.

    Calendar instance = Calendar.getInstance();
    instance.setTimeInMillis(entry.getTime());

    int year = instance.get(Calendar.YEAR);

    // The DOS epoch begins in 1980. If the year is before that, then default to the start of the
    // epoch (the 1st day of the 1st month)
    if (year < 1980) {
      return DOS_EPOCH_START;
    }
    return (year - 1980) << 25 |
        (instance.get(Calendar.MONTH) + 1) << 21 |
        instance.get(Calendar.DAY_OF_MONTH) << 16 |
        instance.get(Calendar.HOUR_OF_DAY) << 11 |
        instance.get(Calendar.MINUTE) << 5 |
        instance.get(Calendar.SECOND) >> 1;
  }

  private boolean isDeflated() {
    return method == Method.DEFLATE;
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

  public void calculateCrc() {
    entry.setCrc(crc.hash().padToLong());
  }

  public int getCompressionMethod() {
    return method.compressionMethod;
  }

  public int getRequiredExtractVersion() {
    return method.getRequiredExtractVersion();
  }

  public long getExternalAttributes() {
    return externalAttributes;
  }

  public long writeLocalFileHeader(OutputStream out) throws IOException {
    if (method == Method.DEFLATE) {
      flags |= DATA_DESCRIPTOR_FLAG;

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
      if (entry instanceof CustomZipEntry) {
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
    }

    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      ByteIo.writeInt(stream, ZipEntry.LOCSIG);

      ByteIo.writeShort(stream, getRequiredExtractVersion());
      ByteIo.writeShort(stream, flags);
      ByteIo.writeShort(stream, getCompressionMethod());
      ByteIo.writeInt(stream, getTime());

      // In deflate mode, we don't know the size or CRC of the data.
      if (isDeflated()) {
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

  private byte[] close() throws IOException {
    if (!isDeflated()) {
      return new byte[0];
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

  public long write(OutputStream out, byte[] b, int off, int len) throws IOException {
    updateCrc(b, off, len);

    if (!isDeflated()) {
      out.write(b, off, len);
      return len;
    }

    if (len == 0) {
      return 0;
    }

    Preconditions.checkState(!deflater.finished());
    deflater.setInput(b, off, len);

    while (!deflater.needsInput()) {
      deflate(out);
    }
    return 0; // We calculate how many bytes we write when closing deflated entries.
  }

  public long close(OutputStream out) throws IOException {
    if (!isDeflated()) {
      // If we're not doing deflation, end the deflater to free native resources.
      deflater.end();
      // Nothing left to do.
      return 0;
    }

    deflater.finish();
    while (!deflater.finished()) {
      deflate(out);
    }
    entry.setSize(deflater.getBytesRead());
    entry.setCompressedSize(deflater.getBytesWritten());
    calculateCrc();

    deflater.end();

    byte[] closeBytes = close();
    out.write(closeBytes);

    return entry.getCompressedSize() + closeBytes.length;
  }


  private static enum Method {
    DEFLATE(ZipEntry.DEFLATED, 20, 8),
    STORE(ZipEntry.STORED, 10, 0),
    ;

    private final int zipEntryMethod;
    private final int requiredVersion;
    private final int compressionMethod;

    private Method(int zipEntryMethod, int requiredVersion, int compressionMethod) {
      this.zipEntryMethod = zipEntryMethod;
      this.requiredVersion = requiredVersion;
      this.compressionMethod = compressionMethod;
    }

    public int getRequiredExtractVersion() {
      return requiredVersion;
    }

    public static Method detect(int fromZipMethod) {
      if (fromZipMethod == -1) {
        return DEFLATE;
      }

      for (Method value : values()) {
        if (value.zipEntryMethod == fromZipMethod) {
          return value;
        }
      }

      throw new IllegalArgumentException("Cannot determine zip method from: " + fromZipMethod);
    }
  }

}
