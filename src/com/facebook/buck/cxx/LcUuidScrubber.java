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
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class LcUuidScrubber implements FileScrubber {

  private static final byte[] MH_MAGIC = Ints.toByteArray(0xFEEDFACE);
  private static final byte[] MH_MAGIC_64 = Ints.toByteArray(0xFEEDFACF);
  private static final byte[] MH_CIGAM = Ints.toByteArray(0xCEFAEDFE);
  private static final byte[] MH_CIGAM_64 = Ints.toByteArray(0xCFFAEDFE);

  private static final int LC_UUID = 0x0000001B;
  private static final byte[] ZERO_UUID = new byte[16];

  @Override
  public void scrubFile(FileChannel file) throws IOException, ScrubException {
    long size = file.size();
    MappedByteBuffer map = file.map(FileChannel.MapMode.READ_WRITE, 0, size);

    setUuid(map, ZERO_UUID);
    map.rewind();

    Hasher hasher = Hashing.sha1().newHasher();
    while (map.hasRemaining()) {
      hasher.putByte(map.get());
    }

    map.rewind();
    setUuid(map, Arrays.copyOf(hasher.hash().asBytes(), 16));
  }

  private void setUuid(MappedByteBuffer map, byte[] uuid) throws ScrubException {
    byte[] magic = ObjectFileScrubbers.getBytes(map, MH_MAGIC.length);
    boolean is64bit;
    if (Arrays.equals(MH_MAGIC, magic) || Arrays.equals(MH_CIGAM, magic)) {
      is64bit = false;
    } else if (Arrays.equals(MH_MAGIC_64, magic) || Arrays.equals(MH_CIGAM_64, magic)) {
      is64bit = true;
    } else {
      throw new ScrubException("invalid Mach-O magic");
    }

    /* CPU type */
    ObjectFileScrubbers.getBytes(map, 4);
    /* CPU subtype */
    ObjectFileScrubbers.getBytes(map, 4);
    /* File type */
    ObjectFileScrubbers.getBytes(map, 4);
    int commandsCount = ObjectFileScrubbers.getLittleEndian32BitLong(map);
    /* Commands size */
    ObjectFileScrubbers.getLittleEndian32BitLong(map);
    /* Flags */
    ObjectFileScrubbers.getBytes(map, 4);
    if (is64bit) {
      /* reserved */ ObjectFileScrubbers.getBytes(map, 4);
    }

    for (int i = 0; i < commandsCount; i++) {
      int command = ObjectFileScrubbers.getLittleEndian32BitLong(map);
      int commandSize = ObjectFileScrubbers.getLittleEndian32BitLong(map);
      if (LC_UUID == command) {
        ObjectFileScrubbers.putBytes(map, uuid);
        return;
      } else {
        /* Command body */ ObjectFileScrubbers.getBytes(map, commandSize - 8);
      }
    }

    throw new ScrubException("LC_UUID command not found");
  }

}
