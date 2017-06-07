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
import java.util.Arrays;

public class FatArchTestData {
  private FatArchTestData() {}

  private static final byte[] BIG_ENDIAN = {
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x20,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x30,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x50,
  };

  private static final byte[] LITTLE_ENDIAN = {
    (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x50, (byte) 0x00, (byte) 0x00, (byte) 0x00,
  };

  public static byte[] getBigEndian() {
    return Arrays.copyOf(BIG_ENDIAN, BIG_ENDIAN.length);
  }

  public static byte[] getLittleEndian() {
    return Arrays.copyOf(LITTLE_ENDIAN, LITTLE_ENDIAN.length);
  }

  public static void checkValues(FatArch arch) {
    assertThat(arch.getCputype(), equalToObject(0x10));
    assertThat(arch.getCpusubtype(), equalToObject(0x20));
    assertThat(arch.getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x30)));
    assertThat(arch.getSize(), equalToObject(UnsignedInteger.fromIntBits(0x40)));
    assertThat(arch.getAlign(), equalToObject(UnsignedInteger.fromIntBits(0x50)));
  }
}
