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

public class SegmentCommandTestData {

  private SegmentCommandTestData() {}

  private static final byte[] BIG_ENDIAN_64_BITS = {
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x19, // cmd =  LC_SEGMENT_64
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x48, // cmdsize = 72
    (byte) 0x53, (byte) 0x45, (byte) 0x47, (byte) 0x4E,
    (byte) 0x41, (byte) 0x4D, (byte) 0x45, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // segname = "SEGNAME"
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, // vmaddr
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x20, // vmsize
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x30, // fileoff
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40, // filesize
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x50, // maxprot
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x60, // initprot
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x70, // nsects
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80, // flags
  };

  private static final byte[] LITTLE_ENDIAN_64_BITS = {
    (byte) 0x19, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cmd =  LC_SEGMENT_64
    (byte) 0x48, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cmdsize = 72
    (byte) 0x53, (byte) 0x45, (byte) 0x47, (byte) 0x4E,
    (byte) 0x41, (byte) 0x4D, (byte) 0x45, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // segname = "SEGNAME"
    (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // vmaddr
    (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // vmsize
    (byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // fileoff
    (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // filesize
    (byte) 0x50, (byte) 0x00, (byte) 0x00, (byte) 0x00, // maxprot
    (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x00, // initprot
    (byte) 0x70, (byte) 0x00, (byte) 0x00, (byte) 0x00, // nsects
    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00, // flags
  };

  private static final byte[] BIG_ENDIAN_32_BITS = {
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, // cmd =  LC_SEGMENT
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x38, // cmdsize = 56
    (byte) 0x53, (byte) 0x45, (byte) 0x47, (byte) 0x4E,
    (byte) 0x41, (byte) 0x4D, (byte) 0x45, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // segname = "SEGNAME"
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, // vmaddr
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x20, // vmsize
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x30, // fileoff
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40, // filesize
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x50, // maxprot
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x60, // initprot
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x70, // nsects
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80, // flags
  };

  private static final byte[] LITTLE_ENDIAN_32_BITS = {
    (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cmd =  LC_SEGMENT
    (byte) 0x38, (byte) 0x00, (byte) 0x00, (byte) 0x00, // cmdsize = 56
    (byte) 0x53, (byte) 0x45, (byte) 0x47, (byte) 0x4E,
    (byte) 0x41, (byte) 0x4D, (byte) 0x45, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // segname = "SEGNAME"
    (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00, // vmaddr
    (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, // vmsize
    (byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00, // fileoff
    (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, // filesize
    (byte) 0x50, (byte) 0x00, (byte) 0x00, (byte) 0x00, // maxprot
    (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x00, // initprot
    (byte) 0x70, (byte) 0x00, (byte) 0x00, (byte) 0x00, // nsects
    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00, // flags
  };

  public static byte[] getBigEndian64Bits() {
    return Arrays.copyOf(BIG_ENDIAN_64_BITS, BIG_ENDIAN_64_BITS.length);
  }

  public static byte[] getLittleEndian64Bits() {
    return Arrays.copyOf(LITTLE_ENDIAN_64_BITS, LITTLE_ENDIAN_64_BITS.length);
  }

  public static byte[] getBigEndian32Bits() {
    return Arrays.copyOf(BIG_ENDIAN_32_BITS, BIG_ENDIAN_32_BITS.length);
  }

  public static byte[] getLittleEndian32Bits() {
    return Arrays.copyOf(LITTLE_ENDIAN_32_BITS, LITTLE_ENDIAN_32_BITS.length);
  }

  public static void checkValues(SegmentCommand command, boolean is64Bit) {
    if (is64Bit) {
      assertThat(
          command.getLoadCommandCommonFields().getCmd(),
          equalToObject(SegmentCommand.LC_SEGMENT_64));
      assertThat(command.getLoadCommandCommonFields().getCmdsize().intValue(), equalTo(72));
    } else {
      assertThat(
          command.getLoadCommandCommonFields().getCmd(), equalToObject(SegmentCommand.LC_SEGMENT));
      assertThat(command.getLoadCommandCommonFields().getCmdsize().intValue(), equalTo(56));
    }

    assertThat(command.getSegname(), equalToObject("SEGNAME"));
    assertThat(command.getVmaddr().intValue(), equalTo(0x10));
    assertThat(command.getVmsize().intValue(), equalTo(0x20));
    assertThat(command.getFileoff().intValue(), equalTo(0x30));
    assertThat(command.getFilesize().intValue(), equalTo(0x40));
    assertThat(command.getMaxprot(), equalToObject(0x50));
    assertThat(command.getInitprot(), equalToObject(0x60));
    assertThat(command.getNsects(), equalToObject(UnsignedInteger.fromIntBits(0x70)));
    assertThat(command.getFlags(), equalToObject(UnsignedInteger.fromIntBits(0x80)));
  }
}
