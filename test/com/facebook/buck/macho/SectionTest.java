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

import com.facebook.buck.charset.NulTerminatedCharsetDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SectionTest {

  private static ByteBuffer createBuffer(byte[] bytes, int offset, ByteOrder order) {
    ByteBuffer buffer = ByteBuffer.allocate(offset + bytes.length);
    buffer.order(order);
    buffer.position(offset);
    buffer.put(bytes);
    buffer.position(offset);
    return buffer;
  }

  @Test
  public void testCreatingFromBytesBigEndian64Bit() throws Exception {
    Section section =
        SectionUtils.createFromBuffer(
            createBuffer(SectionTestData.getBigEndian64Bit(), 10, ByteOrder.BIG_ENDIAN),
            true,
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(section.getOffsetInBinary(), equalTo(10));
    checkValues(section);
  }

  @Test
  public void testCreatingFromBytesLittleEndian64Bit() throws Exception {
    Section section =
        SectionUtils.createFromBuffer(
            createBuffer(SectionTestData.getLittleEndian64Bit(), 20, ByteOrder.LITTLE_ENDIAN),
            true,
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(section.getOffsetInBinary(), equalTo(20));
    checkValues(section);
  }

  @Test
  public void testCreatingFromBytesBigEndian32Bit() throws Exception {
    Section section =
        SectionUtils.createFromBuffer(
            createBuffer(SectionTestData.getBigEndian32Bit(), 30, ByteOrder.BIG_ENDIAN),
            false,
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(section.getOffsetInBinary(), equalTo(30));
    checkValues(section);
  }

  @Test
  public void testCreatingFromBytesLittleEndian32Bit() throws Exception {
    Section section =
        SectionUtils.createFromBuffer(
            createBuffer(SectionTestData.getLittleEndian32Bit(), 40, ByteOrder.LITTLE_ENDIAN),
            false,
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(section.getOffsetInBinary(), equalTo(40));
    checkValues(section);
  }

  public static void checkValues(Section section) {
    assertThat(section.getSectname(), equalToObject("SECTNAME"));
    assertThat(section.getSegname(), equalToObject("SEGNAME"));
    assertThat(section.getAddr().intValue(), equalTo(0x10));
    assertThat(section.getSize().intValue(), equalTo(0x20));
    assertThat(section.getOffset().intValue(), equalTo(0x30));
    assertThat(section.getAlign().intValue(), equalTo(0x40));
    assertThat(section.getReloff().intValue(), equalTo(0x50));
    assertThat(section.getNreloc().intValue(), equalTo(0x60));
    assertThat(section.getFlags().intValue(), equalTo(0x70));
    assertThat(section.getReserved1().intValue(), equalTo(0x80));
    assertThat(section.getReserved2().intValue(), equalTo(0x90));
  }
}
