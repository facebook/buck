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

import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SegmentCommandTest {

  private static ByteBuffer bufferWithBytes(byte[] bytes, int pos) {
    ByteBuffer buffer = ByteBuffer.allocate(pos + bytes.length);
    buffer.position(pos);
    buffer.put(bytes);
    buffer.position(pos);
    return buffer;
  }

  @Test
  public void testCreatingFromBytes64BitsBigEndian() {
    ByteBuffer byteBuffer = bufferWithBytes(SegmentCommandTestData.getBigEndian64Bits(), 10);
    byteBuffer.order(ByteOrder.BIG_ENDIAN);
    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(command.getLoadCommandCommonFields().getOffsetInBinary(), equalTo(10));
    SegmentCommandTestData.checkValues(command, true);
  }

  @Test
  public void testCreatingFromBytes32BitsBigEndian() {
    ByteBuffer byteBuffer = bufferWithBytes(SegmentCommandTestData.getBigEndian32Bits(), 10);
    byteBuffer.order(ByteOrder.BIG_ENDIAN);
    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(command.getLoadCommandCommonFields().getOffsetInBinary(), equalTo(10));
    SegmentCommandTestData.checkValues(command, false);
  }

  @Test
  public void testCreatingFromBytes64BitsLittleEndian() {
    ByteBuffer byteBuffer = bufferWithBytes(SegmentCommandTestData.getLittleEndian64Bits(), 10);
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(command.getLoadCommandCommonFields().getOffsetInBinary(), equalTo(10));
    SegmentCommandTestData.checkValues(command, true);
  }

  @Test
  public void testCreatingFromBytes32BitsLittleEndian() {
    ByteBuffer byteBuffer = bufferWithBytes(SegmentCommandTestData.getLittleEndian32Bits(), 10);
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(command.getLoadCommandCommonFields().getOffsetInBinary(), equalTo(10));
    SegmentCommandTestData.checkValues(command, false);
  }
}
