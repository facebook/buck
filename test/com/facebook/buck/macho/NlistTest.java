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
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class NlistTest {

  private static ByteBuffer byteBufferWithBytesOrderAndPosition(
      byte[] bytes, ByteOrder order, int position) {
    ByteBuffer buffer = ByteBuffer.allocate(position + bytes.length).order(order);
    buffer.position(position);
    buffer.put(bytes);
    buffer.position(position);
    return buffer;
  }

  @Test
  public void testCreatingFromBytes32BitBigEndian() {
    Nlist nlist =
        NlistUtils.createFromBuffer(
            byteBufferWithBytesOrderAndPosition(
                NlistTestData.getBigEndian32Bit(), ByteOrder.BIG_ENDIAN, 10),
            false);
    assertThat(nlist.getOffsetInBinary(), equalTo(10));
    NlistTestData.checkValues(nlist);
  }

  @Test
  public void testCreatingFromBytes32BitLittleEndian() {
    Nlist nlist =
        NlistUtils.createFromBuffer(
            byteBufferWithBytesOrderAndPosition(
                NlistTestData.getLittleEndian32Bit(), ByteOrder.LITTLE_ENDIAN, 11),
            false);
    assertThat(nlist.getOffsetInBinary(), equalTo(11));
    NlistTestData.checkValues(nlist);
  }

  @Test
  public void testCreatingFromBytes64BitBigEndian() {
    Nlist nlist =
        NlistUtils.createFromBuffer(
            byteBufferWithBytesOrderAndPosition(
                NlistTestData.getBigEndian64Bit(), ByteOrder.BIG_ENDIAN, 12),
            true);
    assertThat(nlist.getOffsetInBinary(), equalTo(12));
    NlistTestData.checkValues(nlist);
  }

  @Test
  public void testCreatingFromBytes64BitLittleEndian() {
    Nlist nlist =
        NlistUtils.createFromBuffer(
            byteBufferWithBytesOrderAndPosition(
                NlistTestData.getLittleEndian64Bit(), ByteOrder.LITTLE_ENDIAN, 13),
            true);
    assertThat(nlist.getOffsetInBinary(), equalTo(13));
    NlistTestData.checkValues(nlist);
  }

  @Test
  public void testConvertingToBytes32BitBigEndian() {
    Nlist nlist =
        NlistUtils.createFromBuffer(
            byteBufferWithBytesOrderAndPosition(
                NlistTestData.getBigEndian32Bit(), ByteOrder.BIG_ENDIAN, 0),
            false);
    ByteBuffer bigEndian =
        ByteBuffer.allocate(Nlist.SIZE_IN_BYTES_32_BIT).order(ByteOrder.BIG_ENDIAN);
    NlistUtils.writeNlistToBuffer(nlist, bigEndian, false);
    ByteBuffer littleEndian =
        ByteBuffer.allocate(Nlist.SIZE_IN_BYTES_32_BIT).order(ByteOrder.LITTLE_ENDIAN);
    NlistUtils.writeNlistToBuffer(nlist, littleEndian, false);
    assertThat(bigEndian.array(), equalTo(NlistTestData.getBigEndian32Bit()));
    assertThat(littleEndian.array(), equalTo(NlistTestData.getLittleEndian32Bit()));
  }

  @Test
  public void testConvertingToBytes32BitLittleEndian() {
    Nlist nlist =
        NlistUtils.createFromBuffer(
            byteBufferWithBytesOrderAndPosition(
                NlistTestData.getLittleEndian32Bit(), ByteOrder.LITTLE_ENDIAN, 0),
            false);
    ByteBuffer bigEndian =
        ByteBuffer.allocate(Nlist.SIZE_IN_BYTES_32_BIT).order(ByteOrder.BIG_ENDIAN);
    NlistUtils.writeNlistToBuffer(nlist, bigEndian, false);
    ByteBuffer littleEndian =
        ByteBuffer.allocate(Nlist.SIZE_IN_BYTES_32_BIT).order(ByteOrder.LITTLE_ENDIAN);
    NlistUtils.writeNlistToBuffer(nlist, littleEndian, false);
    assertThat(littleEndian.array(), equalTo(NlistTestData.getLittleEndian32Bit()));
    assertThat(bigEndian.array(), equalTo(NlistTestData.getBigEndian32Bit()));
  }

  @Test
  public void testConvertingToBytes64BitBigEndian() {
    Nlist nlist =
        NlistUtils.createFromBuffer(
            byteBufferWithBytesOrderAndPosition(
                NlistTestData.getBigEndian64Bit(), ByteOrder.BIG_ENDIAN, 10),
            true);
    ByteBuffer bigEndian =
        ByteBuffer.allocate(Nlist.SIZE_IN_BYTES_64_BIT).order(ByteOrder.BIG_ENDIAN);
    NlistUtils.writeNlistToBuffer(nlist, bigEndian, true);
    ByteBuffer littleEndian =
        ByteBuffer.allocate(Nlist.SIZE_IN_BYTES_64_BIT).order(ByteOrder.LITTLE_ENDIAN);
    NlistUtils.writeNlistToBuffer(nlist, littleEndian, true);
    assertThat(bigEndian.array(), equalTo(NlistTestData.getBigEndian64Bit()));
    assertThat(littleEndian.array(), equalTo(NlistTestData.getLittleEndian64Bit()));
  }

  @Test
  public void testConvertingToBytes64BitLittleEndian() {
    Nlist nlist =
        NlistUtils.createFromBuffer(
            byteBufferWithBytesOrderAndPosition(
                NlistTestData.getLittleEndian64Bit(), ByteOrder.LITTLE_ENDIAN, 10),
            true);
    ByteBuffer bigEndian =
        ByteBuffer.allocate(Nlist.SIZE_IN_BYTES_64_BIT).order(ByteOrder.BIG_ENDIAN);
    NlistUtils.writeNlistToBuffer(nlist, bigEndian, true);
    ByteBuffer littleEndian =
        ByteBuffer.allocate(Nlist.SIZE_IN_BYTES_64_BIT).order(ByteOrder.LITTLE_ENDIAN);
    NlistUtils.writeNlistToBuffer(nlist, littleEndian, true);
    assertThat(littleEndian.array(), equalTo(NlistTestData.getLittleEndian64Bit()));
    assertThat(bigEndian.array(), equalTo(NlistTestData.getBigEndian64Bit()));
  }
}
