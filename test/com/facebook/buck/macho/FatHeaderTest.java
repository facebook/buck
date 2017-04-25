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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class FatHeaderTest {

  @Test
  public void testCreatingHeader() {
    FatHeader header = FatHeader.of(FatHeader.FAT_MAGIC, UnsignedInteger.fromIntBits(2));
    assertThat(header.getMagic(), equalTo(FatHeader.FAT_MAGIC));
    assertThat(header.getNfat_arch(), equalToObject(UnsignedInteger.fromIntBits(2)));
  }

  @Test
  public void testCreatingFromBytesBigEndian() {
    byte[] bytes = {
      (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x04
    };
    FatHeader header =
        FatHeaderUtils.createFromBuffer(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN));
    assertThat(header.getMagic(), equalTo(FatHeader.FAT_MAGIC));
    assertThat(header.getNfat_arch(), equalToObject(UnsignedInteger.fromIntBits(4)));
  }

  @Test
  public void testCreatingFromBytesLittleEndian() {
    byte[] bytes = {
      (byte) 0xBE, (byte) 0xBA, (byte) 0xFE, (byte) 0xCA,
      (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x00
    };
    FatHeader header =
        FatHeaderUtils.createFromBuffer(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
    assertThat(header.getMagic(), equalTo(FatHeader.FAT_MAGIC));
    assertThat(header.getNfat_arch(), equalToObject(UnsignedInteger.fromIntBits(6)));
  }
}
