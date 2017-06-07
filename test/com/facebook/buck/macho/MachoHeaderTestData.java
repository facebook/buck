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
package com.facebook.buck.macho;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToObject;
import static org.junit.Assert.assertThat;

import com.google.common.primitives.UnsignedInteger;
import java.util.Arrays;

public class MachoHeaderTestData {
  private MachoHeaderTestData() {}

  private static final byte[] BIG_ENDIAN_64_BIT = {
    (byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCF, // magic
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, // cputype
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x20, // cpusubtype
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x30, // filetype
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40, // ncmds
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x50, // sizeofcmds
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x60, // flags
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x70 // reserved
  };
  private static final byte[] LITTLE_ENDIAN_64_BIT = {
    (byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, // magic
    (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cputype
    (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cpusubtype
    (byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00, // filetype
    (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, // ncmds
    (byte) 0x50, (byte) 0x00, (byte) 0x00, (byte) 0x00, // sizeofcmds
    (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x00, // flags
    (byte) 0x70, (byte) 0x00, (byte) 0x00, (byte) 0x00 // reserved
  };
  private static final byte[] BIG_ENDIAN_32_BIT = {
    (byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCE, // magic
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, // cputype
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x20, // cpusubtype
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x30, // filetype
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40, // ncmds
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x50, // sizeofcmds
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x60 // flags
  };
  private static final byte[] LITTLE_ENDIAN_32_BIT = {
    (byte) 0xCE, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, // magic
    (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cputype
    (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cpusubtype
    (byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00, // filetype
    (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, // ncmds
    (byte) 0x50, (byte) 0x00, (byte) 0x00, (byte) 0x00, // sizeofcmds
    (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x00 // flags
  };

  public static byte[] getBigEndian64Bit() {
    return Arrays.copyOf(BIG_ENDIAN_64_BIT, BIG_ENDIAN_64_BIT.length);
  }

  public static byte[] getLittleEndian64Bit() {
    return Arrays.copyOf(LITTLE_ENDIAN_64_BIT, LITTLE_ENDIAN_64_BIT.length);
  }

  public static byte[] getBigEndian32Bit() {
    return Arrays.copyOf(BIG_ENDIAN_32_BIT, BIG_ENDIAN_32_BIT.length);
  }

  public static byte[] getLittleEndian32Bit() {
    return Arrays.copyOf(LITTLE_ENDIAN_32_BIT, LITTLE_ENDIAN_32_BIT.length);
  }

  public static void assertHeaderHasValidFields(MachoHeader header) {
    assertThat(header.getCputype(), equalToObject(0x10));
    assertThat(header.getCpusubtype(), equalToObject(0x20));
    assertThat(header.getFiletype(), equalToObject(UnsignedInteger.fromIntBits(0x30)));
    assertThat(header.getNcmds(), equalToObject(UnsignedInteger.fromIntBits(0x40)));
    assertThat(header.getSizeofcmds(), equalToObject(UnsignedInteger.fromIntBits(0x50)));
    assertThat(header.getFlags(), equalToObject(UnsignedInteger.fromIntBits(0x60)));

    if (header.getMagic().equals(MachoHeader.MH_MAGIC_64)
        || header.getMagic().equals(MachoHeader.MH_CIGAM_64)) {
      assertThat(header.getReserved().get(), equalToObject(UnsignedInteger.fromIntBits(0x70)));
    } else {
      assertThat(header.getReserved().isPresent(), equalTo(false));
    }
  }
}
