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

import java.util.Arrays;

public class UUIDCommandTestData {
  private UUIDCommandTestData() {}

  private static final byte[] BIG_ENDIAN = {
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1B, // cmd =  LC_UUID
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x18, // cmdsize = 24
    (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
    (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
    (byte) 0xFF, (byte) 0xEE, (byte) 0xDD, (byte) 0xCC,
    (byte) 0xBB, (byte) 0xAA, (byte) 0x12, (byte) 0x34, // UUID
  };
  private static final byte[] LITTLE_ENDIAN = {
    (byte) 0x1B, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cmd =  LC_UUID
    (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cmdsize = 24
    (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
    (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
    (byte) 0xFF, (byte) 0xEE, (byte) 0xDD, (byte) 0xCC,
    (byte) 0xBB, (byte) 0xAA, (byte) 0x12, (byte) 0x34, // UUID
  };

  public static byte[] getBigEndian() {
    return Arrays.copyOf(BIG_ENDIAN, BIG_ENDIAN.length);
  }

  public static byte[] getLittleEndian() {
    return Arrays.copyOf(LITTLE_ENDIAN, LITTLE_ENDIAN.length);
  }
}
