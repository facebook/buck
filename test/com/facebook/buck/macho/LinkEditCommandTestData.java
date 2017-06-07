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

public class LinkEditCommandTestData {
  private LinkEditCommandTestData() {}

  private static final byte[] codeSignBigEndian = {
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1D,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xAA,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xBB,
  };

  private static final byte[] codeSignLittleEndian = {
    (byte) 0x1D, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0xAA, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0xBB, (byte) 0x00, (byte) 0x00, (byte) 0x00,
  };

  public static byte[] getCodeSignBigEndian() {
    return Arrays.copyOf(codeSignBigEndian, codeSignBigEndian.length);
  }

  public static byte[] getCodeSignLittleEndian() {
    return Arrays.copyOf(codeSignLittleEndian, codeSignLittleEndian.length);
  }

  public static void checkValues(LinkEditDataCommand command) {
    assertThat(
        command.getLoadCommandCommonFields().getCmd(),
        equalToObject(UnsignedInteger.fromIntBits(0x1D)));
    assertThat(
        command.getLoadCommandCommonFields().getCmdsize(),
        equalToObject(UnsignedInteger.fromIntBits(0x10)));
    assertThat(command.getDataoff(), equalToObject(UnsignedInteger.fromIntBits(0xAA)));
    assertThat(command.getDatasize(), equalToObject(UnsignedInteger.fromIntBits(0xBB)));
  }
}
