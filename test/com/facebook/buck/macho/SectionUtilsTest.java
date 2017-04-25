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

import com.facebook.buck.charset.NulTerminatedCharsetDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SectionUtilsTest {

  @Test
  public void testGettingSize() {
    assertThat(SectionUtils.sizeOfSectionHeader(false), equalTo(68));
    assertThat(SectionUtils.sizeOfSectionHeader(true), equalTo(80));
  }

  @Test
  public void testCreatingFromBytes64Bits() throws CharacterCodingException {
    ByteBuffer buffer = ByteBuffer.allocate(10 + SectionTestData.getBigEndian64Bit().length);
    buffer.order(ByteOrder.BIG_ENDIAN);
    buffer.position(10);
    buffer.put(SectionTestData.getBigEndian64Bit());
    buffer.position(10);

    Section section =
        SectionUtils.createFromBuffer(
            buffer, true, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(section.getOffsetInBinary(), equalTo(10));
    SectionTestData.checkValues(section, true);
  }

  @Test
  public void testCreatingFromBytes32Bits() throws CharacterCodingException {
    ByteBuffer buffer = ByteBuffer.allocate(20 + SectionTestData.getBigEndian32Bit().length);
    buffer.order(ByteOrder.BIG_ENDIAN);
    buffer.position(20);
    buffer.put(SectionTestData.getBigEndian32Bit());
    buffer.position(20);

    Section section =
        SectionUtils.createFromBuffer(
            buffer, false, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(section.getOffsetInBinary(), equalTo(20));
    SectionTestData.checkValues(section, false);
  }
}
