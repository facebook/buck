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
import java.util.Optional;
import org.junit.Test;

public class MachoHeaderUtilsTest {

  @Test
  public void testGettingSize() {
    assertThat(
        MachoHeaderUtils.getHeaderSize(
            MachoHeader.of(
                MachoHeader.MH_MAGIC,
                0,
                0,
                UnsignedInteger.ZERO,
                UnsignedInteger.ZERO,
                UnsignedInteger.ZERO,
                UnsignedInteger.ZERO,
                Optional.empty())),
        equalTo(MachoHeader.MACH_HEADER_SIZE_32));
    assertThat(
        MachoHeaderUtils.getHeaderSize(
            MachoHeader.of(
                MachoHeader.MH_MAGIC_64,
                0,
                0,
                UnsignedInteger.ZERO,
                UnsignedInteger.ZERO,
                UnsignedInteger.ZERO,
                UnsignedInteger.ZERO,
                Optional.of(UnsignedInteger.ZERO))),
        equalTo(MachoHeader.MACH_HEADER_SIZE_64));
  }

  @Test
  public void testCreating64BitHeaderBigEndianFromBytes() {
    ByteBuffer buffer =
        ByteBuffer.wrap(MachoHeaderTestData.getBigEndian64Bit()).order(ByteOrder.BIG_ENDIAN);
    MachoHeader header = MachoHeaderUtils.createFromBuffer(buffer);
    assertThat(header.getMagic(), equalToObject(UnsignedInteger.fromIntBits(0xFEEDFACF)));
    MachoHeaderTestData.assertHeaderHasValidFields(header);
  }

  @Test
  public void testCreating64BitHeaderLittleEndianFromBytes() {
    ByteBuffer buffer =
        ByteBuffer.wrap(MachoHeaderTestData.getLittleEndian64Bit()).order(ByteOrder.LITTLE_ENDIAN);
    MachoHeader header = MachoHeaderUtils.createFromBuffer(buffer);
    assertThat(header.getMagic(), equalToObject(UnsignedInteger.fromIntBits(0xFEEDFACF)));
    MachoHeaderTestData.assertHeaderHasValidFields(header);
  }

  @Test
  public void testCreating32BitHeaderBigEndianFromBytes() {
    ByteBuffer buffer =
        ByteBuffer.wrap(MachoHeaderTestData.getBigEndian32Bit()).order(ByteOrder.BIG_ENDIAN);
    MachoHeader header = MachoHeaderUtils.createFromBuffer(buffer);
    assertThat(header.getMagic(), equalToObject(UnsignedInteger.fromIntBits(0xFEEDFACE)));
    MachoHeaderTestData.assertHeaderHasValidFields(header);
  }

  @Test
  public void testCreating32BitHeaderLittleEndianFromBytes() {
    ByteBuffer buffer =
        ByteBuffer.wrap(MachoHeaderTestData.getLittleEndian32Bit()).order(ByteOrder.LITTLE_ENDIAN);
    MachoHeader header = MachoHeaderUtils.createFromBuffer(buffer);
    assertThat(header.getMagic(), equalToObject(UnsignedInteger.fromIntBits(0xFEEDFACE)));
    MachoHeaderTestData.assertHeaderHasValidFields(header);
  }
}
