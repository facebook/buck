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

import static org.hamcrest.Matchers.equalToObject;
import static org.junit.Assert.assertThat;

import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.util.Arrays;

public class NlistTestData {
  private NlistTestData() {}

  private static final byte[] BIG_ENDIAN_32_BIT = {
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x0F, // strx
    (byte) 0x10, // type
    (byte) 0x20, // section
    (byte) 0x00,
    (byte) 0x30, // desc
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x40, // value
  };

  private static final byte[] LITTLE_ENDIAN_32_BIT = {
    (byte) 0x0F,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // strx
    (byte) 0x10, // type
    (byte) 0x20, // section
    (byte) 0x30,
    (byte) 0x00, // desc
    (byte) 0x40,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // value
  };

  private static final byte[] BIG_ENDIAN_64_BIT = {
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x0F, // strx
    (byte) 0x10, // type
    (byte) 0x20, // section
    (byte) 0x00,
    (byte) 0x30, // desc
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x40, // value
  };

  private static final byte[] LITTLE_ENDIAN_64_BIT = {
    (byte) 0x0F,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // strx
    (byte) 0x10, // type
    (byte) 0x20, // section
    (byte) 0x30,
    (byte) 0x00, // desc
    (byte) 0x40,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00,
    (byte) 0x00, // value
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

  public static void checkValues(Nlist nlist) {
    assertThat(nlist.getN_strx(), equalToObject(UnsignedInteger.fromIntBits(15)));
    assertThat(nlist.getN_type(), equalToObject(UnsignedInteger.fromIntBits(16)));
    assertThat(nlist.getN_sect(), equalToObject(UnsignedInteger.fromIntBits(32)));
    assertThat(nlist.getN_desc(), equalToObject(UnsignedInteger.fromIntBits(48)));
    assertThat(nlist.getN_value(), equalToObject(UnsignedLong.fromLongBits(64L)));
  }
}
