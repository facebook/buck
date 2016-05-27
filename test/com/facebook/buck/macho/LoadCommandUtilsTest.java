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
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import com.google.common.base.Function;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedInteger;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class LoadCommandUtilsTest {
  @Test
  public void testEnumeratingLoadCommands() throws Exception {
    byte[] header = MachoHeaderTestData.getBigEndian64Bit();
    header[19] = 2;   // ncmds
    header[23] = 16;  // sizeofcmds

    byte[] commandBytes = BaseEncoding.base16().decode(
        "000000AA00000008" +      // command 1 is AA, size 8
        "000000BB00000008");      // command 2 is BB, size 8

    ByteBuffer buffer = ByteBuffer
        .allocate(MachoHeaderTestData.getBigEndian64Bit().length + commandBytes.length)
        .put(header)
        .put(commandBytes)
        .order(ByteOrder.BIG_ENDIAN);

    final List<LoadCommand> result = new ArrayList<>();
    buffer.position(0);
    LoadCommandUtils.enumerateLoadCommandsInFile(
        buffer,
        new Function<LoadCommand, Boolean>() {
          @Override
          public Boolean apply(LoadCommand input) {
            result.add(input);
            return Boolean.TRUE;
          }
        });

    assertThat(result.size(), equalTo(2));

    assertThat(
        result.get(0).getLoadCommandCommonFields().getCmd(),
        equalTo(UnsignedInteger.fromIntBits(0xAA)));
    assertThat(
        result.get(0).getLoadCommandCommonFields().getCmdsize(),
        equalTo(UnsignedInteger.fromIntBits(0x08)));

    assertThat(
        result.get(1).getLoadCommandCommonFields().getCmd(),
        equalTo(UnsignedInteger.fromIntBits(0xBB)));
    assertThat(
        result.get(1).getLoadCommandCommonFields().getCmdsize(),
        equalTo(UnsignedInteger.fromIntBits(0x08)));
  }

  @Test
  public void testCreatingLoadCommandsFromBuffer() throws Exception {
    byte[] segmentBytes = SegmentCommandTestData.getBigEndian64Bits();
    byte[] symtabBytes = SymTabCommandTestData.getBigEndian();
    byte[] uuidBytes = UUIDCommandTestData.getBigEndian();
    byte[] unknownBytes = {
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x99,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
    };

    ByteBuffer byteBuffer = ByteBuffer
        .allocate(segmentBytes.length + symtabBytes.length + uuidBytes.length + unknownBytes.length)
        .order(ByteOrder.BIG_ENDIAN);
    byteBuffer
        .put(segmentBytes)
        .put(symtabBytes)
        .put(uuidBytes)
        .put(unknownBytes);
    byteBuffer.position(0);

    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(byteBuffer),
        instanceOf(SegmentCommand.class));
    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(byteBuffer),
        instanceOf(SymTabCommand.class));
    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(byteBuffer),
        instanceOf(UUIDCommand.class));
    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(byteBuffer),
        instanceOf(UnknownCommand.class));
  }
}
