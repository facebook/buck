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

public class SymTabCommandTestData {

  private SymTabCommandTestData() {}

  private static final byte[] BIG_ENDIAN = {
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02, // cmd = LC_SYMTAB
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x18, // cmdsize = 24
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, // symoff
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x20, // nsyms
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x30, // stroff
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40, // strsize
  };

  private static final byte[] LITTLE_ENDIAN = {
    (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cmd = LC_SYMTAB
    (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cmdsize = 24
    (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00, // symoff
    (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, // nsyms
    (byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00, // stroff
    (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, // strsize
  };

  public static byte[] getBigEndian() {
    return Arrays.copyOf(BIG_ENDIAN, BIG_ENDIAN.length);
  }

  public static byte[] getLittleEndian() {
    return Arrays.copyOf(LITTLE_ENDIAN, LITTLE_ENDIAN.length);
  }

  public static void checkValues(SymTabCommand command) {
    assertThat(
        command.getLoadCommandCommonFields().getCmd(),
        equalToObject(UnsignedInteger.fromIntBits(0x2)));
    assertThat(
        command.getLoadCommandCommonFields().getCmdsize(),
        equalToObject(UnsignedInteger.fromIntBits(0x18)));
    assertThat(command.getSymoff(), equalToObject(UnsignedInteger.fromIntBits(0x10)));
    assertThat(command.getNsyms(), equalToObject(UnsignedInteger.fromIntBits(0x20)));
    assertThat(command.getStroff(), equalToObject(UnsignedInteger.fromIntBits(0x30)));
    assertThat(command.getStrsize(), equalToObject(UnsignedInteger.fromIntBits(0x40)));
  }
}
