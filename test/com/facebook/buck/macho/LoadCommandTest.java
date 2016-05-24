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
import static org.hamcrest.Matchers.equalTo;

import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedInteger;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class LoadCommandTest {
  @Test
  public void testCanCreate() {
    LoadCommand command = LoadCommand.of(
        123,
        UnsignedInteger.fromIntBits(111),
        UnsignedInteger.fromIntBits(222));
    assertThat(command.getOffsetInBinary(), equalTo(123));
    assertThat(command.getCmd(), equalTo(UnsignedInteger.fromIntBits(111)));
    assertThat(command.getCmdsize(), equalTo(UnsignedInteger.fromIntBits(222)));
  }

  @Test
  public void testCreatingFromBuffer() throws Exception {
    byte[] commandBytes = BaseEncoding.base16().decode("FFFF000000AA00000008");

    ByteBuffer buffer = ByteBuffer.wrap(commandBytes).order(ByteOrder.BIG_ENDIAN);
    buffer.position(2);
    LoadCommand command = LoadCommandUtils.createFromBuffer(buffer);
    assertThat(command.getCmd(), equalToObject(UnsignedInteger.fromIntBits(0xAA)));
    assertThat(command.getCmdsize(), equalToObject(UnsignedInteger.fromIntBits(0x08)));
    assertThat(command.getOffsetInBinary(), equalTo(2));
  }

  @Test
  public void testGetBytes() {
    LoadCommand command = LoadCommand.of(
        1,
        UnsignedInteger.fromIntBits(2),
        UnsignedInteger.fromIntBits(3));

    byte[] expected = BaseEncoding.base16().decode("0000000200000003");
    byte[] expectedSwapped = BaseEncoding.base16().decode("0200000003000000");

    ByteBuffer bigEndian = ByteBuffer.allocate(expected.length).order(ByteOrder.BIG_ENDIAN);
    LoadCommandUtils.writeCommandToBuffer(command, bigEndian);
    ByteBuffer littleEndian = ByteBuffer.allocate(expected.length).order(ByteOrder.LITTLE_ENDIAN);
    LoadCommandUtils.writeCommandToBuffer(command, littleEndian);

    assertThat(bigEndian.array(), equalTo(expected));
    assertThat(littleEndian.array(), equalTo(expectedSwapped));
  }
}
