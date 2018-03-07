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
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class LoadCommandUtilsTest {
  @Test
  public void testEnumeratingLoadCommands() {
    byte[] header = MachoHeaderTestData.getBigEndian64Bit();
    header[19] = 2; // ncmds
    header[23] = 16; // sizeofcmds

    byte[] commandBytes =
        BaseEncoding.base16()
            .decode(
                "000000AA00000008"
                    + // command 1 is AA, size 8
                    "000000BB00000008"); // command 2 is BB, size 8

    ByteBuffer buffer =
        ByteBuffer.allocate(MachoHeaderTestData.getBigEndian64Bit().length + commandBytes.length)
            .put(header)
            .put(commandBytes)
            .order(ByteOrder.BIG_ENDIAN);

    List<LoadCommand> result = new ArrayList<>();
    buffer.position(0);
    LoadCommandUtils.enumerateLoadCommandsInFile(
        buffer,
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()),
        input -> {
          result.add(input);
          return Boolean.TRUE;
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
  public void testFindingLoadCommands() {
    byte[] header = MachoHeaderTestData.getBigEndian64Bit();
    header[19] = 3; // ncmds
    header[23] = 16; // sizeofcmds

    byte[] symtabBytes = SymTabCommandTestData.getBigEndian();
    byte[] uuid1Bytes = UUIDCommandTestData.getBigEndian();
    uuid1Bytes[8] = (byte) 0x11;
    byte[] uuid2Bytes = UUIDCommandTestData.getBigEndian();
    uuid2Bytes[8] = (byte) 0x22;

    ByteBuffer buffer =
        ByteBuffer.allocate(
                MachoHeaderTestData.getBigEndian64Bit().length
                    + symtabBytes.length
                    + uuid1Bytes.length
                    + uuid2Bytes.length)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .put(uuid1Bytes)
            .put(symtabBytes)
            .put(uuid2Bytes);

    buffer.position(0);
    ImmutableList<UUIDCommand> uuidCommands =
        LoadCommandUtils.findLoadCommandsWithClass(
            buffer,
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()),
            UUIDCommand.class);

    assertThat(uuidCommands.size(), equalTo(2));
    assertThat(uuidCommands.get(0).getUuid().toString(), startsWith("11"));
    assertThat(
        uuidCommands.get(0).getLoadCommandCommonFields().getOffsetInBinary(),
        equalTo(header.length + 0));
    assertThat(uuidCommands.get(1).getUuid().toString(), startsWith("22"));
    assertThat(
        uuidCommands.get(1).getLoadCommandCommonFields().getOffsetInBinary(),
        equalTo(header.length + 48));

    buffer.position(0);
    ImmutableList<SymTabCommand> symTabCommands =
        LoadCommandUtils.findLoadCommandsWithClass(
            buffer,
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()),
            SymTabCommand.class);
    assertThat(symTabCommands.size(), equalTo(1));
    assertThat(
        symTabCommands.get(0).getLoadCommandCommonFields().getOffsetInBinary(),
        equalTo(header.length + 24));
  }

  @Test
  public void testCreatingLoadCommandsFromBuffer() {
    byte[] segmentBytes = SegmentCommandTestData.getBigEndian64Bits();
    byte[] symtabBytes = SymTabCommandTestData.getBigEndian();
    byte[] uuidBytes = UUIDCommandTestData.getBigEndian();
    byte[] linkEditBytes = LinkEditCommandTestData.getCodeSignBigEndian();
    byte[] unknownBytes = {
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x99,
      (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
    };

    ByteBuffer byteBuffer =
        ByteBuffer.allocate(
                segmentBytes.length
                    + symtabBytes.length
                    + uuidBytes.length
                    + linkEditBytes.length
                    + unknownBytes.length)
            .order(ByteOrder.BIG_ENDIAN);
    byteBuffer
        .put(segmentBytes)
        .put(symtabBytes)
        .put(uuidBytes)
        .put(linkEditBytes)
        .put(unknownBytes);
    byteBuffer.position(0);

    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder())),
        instanceOf(SegmentCommand.class));
    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder())),
        instanceOf(SymTabCommand.class));
    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder())),
        instanceOf(UUIDCommand.class));
    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder())),
        instanceOf(LinkEditDataCommand.class));
    assertThat(
        LoadCommandUtils.createLoadCommandFromBuffer(
            byteBuffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder())),
        instanceOf(UnknownCommand.class));
  }
}
