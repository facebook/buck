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

import java.util.Arrays;

public class SectionTestData {
  private SectionTestData() {}

  private static final byte[] BIG_ENDIAN_32_BIT = {
    (byte) 0x53,
    (byte) 0x45,
    (byte) 0x43,
    (byte) 0x54,
    (byte) 0x4E,
    (byte) 0x41,
    (byte) 0x4D,
    (byte) 0x45,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // sectname = "SECTNAME"
    (byte) 0x53,
    (byte) 0x45,
    (byte) 0x47,
    (byte) 0x4E,
    (byte) 0x41,
    (byte) 0x4D,
    (byte) 0x45,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // segname = "SEGNAME"
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x10, // addr
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x20, // size
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x30, // offset
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x40, // align
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x50, // reloff
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x60, // nreloc
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x70, // flags
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x80, // reserved1
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x90, // reserved2
  };

  private static final byte[] LITTLE_ENDIAN_32_BIT = {
    (byte) 0x53,
    (byte) 0x45,
    (byte) 0x43,
    (byte) 0x54,
    (byte) 0x4E,
    (byte) 0x41,
    (byte) 0x4D,
    (byte) 0x45,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // sectname = "SECTNAME"
    (byte) 0x53,
    (byte) 0x45,
    (byte) 0x47,
    (byte) 0x4E,
    (byte) 0x41,
    (byte) 0x4D,
    (byte) 0x45,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // segname = "SEGNAME"
    (byte) 0x10,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // addr
    (byte) 0x20,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // size
    (byte) 0x30,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // offset
    (byte) 0x40,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // align
    (byte) 0x50,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // reloff
    (byte) 0x60,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // nreloc
    (byte) 0x70,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // flags
    (byte) 0x80,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // reserved1
    (byte) 0x90,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // reserved2
  };

  private static final byte[] BIG_ENDIAN_64_BIT = {
    (byte) 0x53,
    (byte) 0x45,
    (byte) 0x43,
    (byte) 0x54,
    (byte) 0x4E,
    (byte) 0x41,
    (byte) 0x4D,
    (byte) 0x45,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // sectname = "SECTNAME"
    (byte) 0x53,
    (byte) 0x45,
    (byte) 0x47,
    (byte) 0x4E,
    (byte) 0x41,
    (byte) 0x4D,
    (byte) 0x45,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // segname = "SEGNAME"
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x10, // addr
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x20, // size
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x30, // offset
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x40, // align
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x50, // reloff
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x60, // nreloc
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x70, // flags
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x80, // reserved1
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x90, // reserved2
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0xA0, // reserved3
  };

  private static final byte[] LITTLE_ENDIAN_64_BIT = {
    (byte) 0x53,
    (byte) 0x45,
    (byte) 0x43,
    (byte) 0x54,
    (byte) 0x4E,
    (byte) 0x41,
    (byte) 0x4D,
    (byte) 0x45,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // sectname = "SECTNAME"
    (byte) 0x53,
    (byte) 0x45,
    (byte) 0x47,
    (byte) 0x4E,
    (byte) 0x41,
    (byte) 0x4D,
    (byte) 0x45,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // segname = "SEGNAME"
    (byte) 0x10,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // addr
    (byte) 0x20,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // size
    (byte) 0x30,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // offset
    (byte) 0x40,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // align
    (byte) 0x50,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // reloff
    (byte) 0x60,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // nreloc
    (byte) 0x70,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // flags
    (byte) 0x80,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // reserved1
    (byte) 0x90,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // reserved2
    (byte) 0xA0,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // reserved3
  };

  public static byte[] getBigEndian32Bit() {
    return Arrays.copyOf(BIG_ENDIAN_32_BIT, BIG_ENDIAN_32_BIT.length);
  }

  public static byte[] getLittleEndian32Bit() {
    return Arrays.copyOf(LITTLE_ENDIAN_32_BIT, LITTLE_ENDIAN_32_BIT.length);
  }

  public static byte[] getBigEndian64Bit() {
    return Arrays.copyOf(BIG_ENDIAN_64_BIT, BIG_ENDIAN_64_BIT.length);
  }

  public static byte[] getLittleEndian64Bit() {
    return Arrays.copyOf(LITTLE_ENDIAN_64_BIT, LITTLE_ENDIAN_64_BIT.length);
  }

  public static void checkValues(Section section, boolean is64Bit) {
    assertThat(section.getSectname(), equalToObject("SECTNAME"));
    assertThat(section.getSegname(), equalToObject("SEGNAME"));
    assertThat(section.getAddr().intValue(), equalTo(0x10));
    assertThat(section.getSize().intValue(), equalTo(0x20));
    assertThat(section.getOffset().intValue(), equalTo(0x30));
    assertThat(section.getAlign().intValue(), equalTo(0x40));
    assertThat(section.getReloff().intValue(), equalTo(0x50));
    assertThat(section.getNreloc().intValue(), equalTo(0x60));
    assertThat(section.getFlags().intValue(), equalTo(0x70));
    assertThat(section.getReserved1().intValue(), equalTo(0x80));
    assertThat(section.getReserved2().intValue(), equalTo(0x90));
    if (is64Bit) {
      assertThat(section.getReserved3().get().intValue(), equalTo(0xA0));
    } else {
      assertThat(section.getReserved3().isPresent(), equalTo(false));
    }
  }
}
