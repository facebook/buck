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
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class NlistUtilsTest {

  @Test
  public void testGettingSize() {
    assertThat(NlistUtils.getSizeInBytes(false), equalTo(12));
    assertThat(NlistUtils.getSizeInBytes(true), equalTo(16));
  }

  @Test
  public void testWritingToByteBuffer64BitBigEndian() {
    ByteBuffer byteBuffer =
        ByteBuffer.wrap(NlistTestData.getBigEndian64Bit()).order(ByteOrder.BIG_ENDIAN);

    Nlist nlist =
        NlistUtils.createFromBuffer(
            ByteBuffer.wrap(NlistTestData.getBigEndian64Bit()).order(ByteOrder.BIG_ENDIAN), true);
    Nlist updatedNlist = nlist.withN_strx(UnsignedInteger.valueOf(321));
    updatedNlist = updatedNlist.withN_value(UnsignedLong.valueOf(432L));
    assertThat(updatedNlist, instanceOf(nlist.getClass()));

    NlistUtils.updateNlistEntry(byteBuffer, nlist, updatedNlist, true);

    byteBuffer.position(0);
    byte[] newBytes = new byte[NlistTestData.getBigEndian64Bit().length];
    byteBuffer.get(newBytes, 0, NlistTestData.getBigEndian64Bit().length);

    Nlist newNlist =
        NlistUtils.createFromBuffer(ByteBuffer.wrap(newBytes).order(ByteOrder.BIG_ENDIAN), true);
    assertThat(nlist.getOffsetInBinary(), equalTo(newNlist.getOffsetInBinary()));
    assertThat(newNlist.getN_strx(), equalToObject(UnsignedInteger.valueOf(321)));
    assertThat(nlist.getN_type(), equalToObject(newNlist.getN_type()));
    assertThat(nlist.getN_sect(), equalToObject(newNlist.getN_sect()));
    assertThat(nlist.getN_desc(), equalToObject(newNlist.getN_desc()));
    assertThat(newNlist.getN_value(), equalToObject(UnsignedLong.valueOf(432L)));
  }

  @Test
  public void testWritingToByteBuffer64BitLittleEndian() {
    ByteBuffer byteBuffer =
        ByteBuffer.wrap(NlistTestData.getLittleEndian64Bit()).order(ByteOrder.LITTLE_ENDIAN);

    Nlist nlist =
        NlistUtils.createFromBuffer(
            ByteBuffer.wrap(NlistTestData.getLittleEndian64Bit()).order(ByteOrder.BIG_ENDIAN),
            false);
    Nlist updatedNlist = nlist.withN_strx(UnsignedInteger.valueOf(321));
    updatedNlist = updatedNlist.withN_value(UnsignedLong.valueOf(432L));
    assertThat(updatedNlist, instanceOf(nlist.getClass()));

    NlistUtils.updateNlistEntry(byteBuffer, nlist, updatedNlist, true);

    byteBuffer.position(0);
    byte[] newBytes = new byte[NlistTestData.getLittleEndian64Bit().length];
    byteBuffer.get(newBytes, 0, NlistTestData.getLittleEndian64Bit().length);

    Nlist newNlist =
        NlistUtils.createFromBuffer(ByteBuffer.wrap(newBytes).order(ByteOrder.LITTLE_ENDIAN), true);
    assertThat(nlist.getOffsetInBinary(), equalTo(newNlist.getOffsetInBinary()));
    assertThat(newNlist.getN_strx(), equalToObject(UnsignedInteger.valueOf(321)));
    assertThat(nlist.getN_type(), equalToObject(newNlist.getN_type()));
    assertThat(nlist.getN_sect(), equalToObject(newNlist.getN_sect()));
    assertThat(nlist.getN_desc(), equalToObject(newNlist.getN_desc()));
    assertThat(newNlist.getN_value(), equalToObject(UnsignedLong.valueOf(432L)));
  }

  @Test
  public void testWritingToByteBuffer32BitBigEndian() {
    ByteBuffer byteBuffer =
        ByteBuffer.wrap(NlistTestData.getBigEndian32Bit()).order(ByteOrder.BIG_ENDIAN);

    Nlist nlist = NlistUtils.createFromBuffer(byteBuffer, false);
    Nlist updatedNlist = nlist.withN_strx(UnsignedInteger.valueOf(321));
    updatedNlist = updatedNlist.withN_value(UnsignedLong.valueOf(432L));
    assertThat(updatedNlist, instanceOf(nlist.getClass()));

    NlistUtils.updateNlistEntry(byteBuffer, nlist, updatedNlist, false);

    byteBuffer.position(0);
    byte[] newBytes = new byte[NlistTestData.getBigEndian32Bit().length];
    byteBuffer.get(newBytes, 0, NlistTestData.getBigEndian32Bit().length);

    Nlist newNlist =
        NlistUtils.createFromBuffer(ByteBuffer.wrap(newBytes).order(ByteOrder.BIG_ENDIAN), false);
    assertThat(nlist.getOffsetInBinary(), equalTo(newNlist.getOffsetInBinary()));
    assertThat(newNlist.getN_strx(), equalToObject(UnsignedInteger.valueOf(321)));
    assertThat(nlist.getN_type(), equalToObject(newNlist.getN_type()));
    assertThat(nlist.getN_sect(), equalToObject(newNlist.getN_sect()));
    assertThat(nlist.getN_desc(), equalToObject(newNlist.getN_desc()));
    assertThat(newNlist.getN_value(), equalToObject(UnsignedLong.valueOf(432L)));
  }

  @Test
  public void testWritingToByteBuffer32BitLittleEndian() {
    ByteBuffer byteBuffer =
        ByteBuffer.wrap(NlistTestData.getLittleEndian32Bit()).order(ByteOrder.LITTLE_ENDIAN);

    Nlist nlist = NlistUtils.createFromBuffer(byteBuffer, false);
    Nlist updatedNlist = nlist.withN_strx(UnsignedInteger.valueOf(321));
    updatedNlist = updatedNlist.withN_value(UnsignedLong.valueOf(432L));
    assertThat(updatedNlist, instanceOf(nlist.getClass()));

    NlistUtils.updateNlistEntry(byteBuffer, nlist, updatedNlist, false);

    byteBuffer.position(0);
    byte[] newBytes = new byte[NlistTestData.getLittleEndian32Bit().length];
    byteBuffer.get(newBytes, 0, NlistTestData.getLittleEndian32Bit().length);

    Nlist newNlist =
        NlistUtils.createFromBuffer(
            ByteBuffer.wrap(newBytes).order(ByteOrder.LITTLE_ENDIAN), false);
    assertThat(nlist.getOffsetInBinary(), equalTo(newNlist.getOffsetInBinary()));
    assertThat(newNlist.getN_strx(), equalToObject(UnsignedInteger.valueOf(321)));
    assertThat(nlist.getN_type(), equalToObject(newNlist.getN_type()));
    assertThat(nlist.getN_sect(), equalToObject(newNlist.getN_sect()));
    assertThat(nlist.getN_desc(), equalToObject(newNlist.getN_desc()));
    assertThat(newNlist.getN_value(), equalToObject(UnsignedLong.valueOf(432L)));
  }
}
