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

import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SegmentCommandUtilsTest {
  @Test
  public void testAligning() {
    assertThat(SegmentCommandUtils.alignValue(10), equalTo(32 * 1024));
    assertThat(SegmentCommandUtils.alignValue(100 * 1024), equalTo(128 * 1024));
    assertThat(SegmentCommandUtils.alignValue(987 * 1024), equalTo(992 * 1024));
  }

  @Test
  public void testGettingHeaderSize64Bit() {
    byte[] bytes = SegmentCommandTestData.getBigEndian64Bits();
    int commandSize = bytes.length;
    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(SegmentCommandUtils.getSegmentCommandHeaderSize(command), equalTo(commandSize));
  }

  @Test
  public void testGettingHeaderSize32Bit() {
    byte[] bytes = SegmentCommandTestData.getBigEndian32Bits();
    int commandSize = bytes.length;
    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(SegmentCommandUtils.getSegmentCommandHeaderSize(command), equalTo(commandSize));
  }

  @Test
  public void testUpdatingSegmentCommandInByteBuffer64Bit() {
    byte[] bytes = SegmentCommandTestData.getBigEndian64Bits();
    int commandSize = bytes.length;
    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    SegmentCommand updated =
        command
            .withFilesize(UnsignedLong.fromLongBits(1234))
            .withVmsize(UnsignedLong.fromLongBits(SegmentCommandUtils.alignValue(4321)));

    ByteBuffer buffer = ByteBuffer.allocate(commandSize);
    SegmentCommandUtils.updateSegmentCommand(buffer, command, updated);
    buffer.position(0);

    SegmentCommand commandInBuffer =
        SegmentCommandUtils.createFromBuffer(
            buffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(commandInBuffer.getFilesize(), equalToObject(updated.getFilesize()));
    assertThat(commandInBuffer.getVmsize(), equalToObject(updated.getVmsize()));
  }

  @Test
  public void testUpdatingSegmentCommandInByteBuffer32Bit() {
    byte[] bytes = SegmentCommandTestData.getBigEndian32Bits();
    int commandSize = bytes.length;

    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    SegmentCommand updated =
        command
            .withFilesize(UnsignedLong.fromLongBits(1234))
            .withVmsize(UnsignedLong.fromLongBits(SegmentCommandUtils.alignValue(4321)));

    ByteBuffer buffer = ByteBuffer.allocate(commandSize);
    SegmentCommandUtils.updateSegmentCommand(buffer, command, updated);
    buffer.position(0);

    SegmentCommand commandInBuffer =
        SegmentCommandUtils.createFromBuffer(
            buffer, new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(commandInBuffer.getFilesize(), equalToObject(updated.getFilesize()));
    assertThat(commandInBuffer.getVmsize(), equalToObject(updated.getVmsize()));
  }

  @Test
  public void testEnumeratingSections64Bit() throws Exception {
    int sectionSize = SectionTestData.getBigEndian64Bit().length;
    byte[] sectionData1 = SectionTestData.getBigEndian64Bit();
    sectionData1[51] = (byte) 0x01; // offset = 1

    byte[] sectionData2 = SectionTestData.getBigEndian64Bit();
    sectionData2[0] = (byte) 0x44; // sectname = "DECTNAME"
    sectionData2[16] = (byte) 0x44; // segname = "DEGNAME"
    sectionData2[51] = (byte) 0x02; // offset = 2

    byte[] sectionData3 = SectionTestData.getBigEndian64Bit();
    sectionData3[0] = (byte) 0x4C; // sectname = "LECTNAME"
    sectionData3[16] = (byte) 0x4C; // segname = "LEGNAME"
    sectionData3[51] = (byte) 0x03; // offset = 3

    byte[] segmentBytes = SegmentCommandTestData.getBigEndian64Bits();
    segmentBytes[67] = (byte) 0x03; // nsects = 3

    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            ByteBuffer.wrap(segmentBytes).order(ByteOrder.BIG_ENDIAN),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));

    ByteBuffer buffer =
        ByteBuffer.allocate(
            command.getLoadCommandCommonFields().getCmdsize().intValue() + 3 * sectionSize);
    buffer.order(ByteOrder.BIG_ENDIAN);
    SegmentCommandUtils.writeCommandToBuffer(command, buffer, true);
    buffer.put(sectionData1);
    buffer.put(sectionData2);
    buffer.put(sectionData3);

    List<Section> enumeratedSections = new ArrayList<>();

    SegmentCommandUtils.enumerateSectionsInSegmentLoadCommand(
        buffer,
        new MachoMagicInfo(UnsignedInteger.fromIntBits(0xFEEDFACF)),
        command,
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()),
        input -> {
          enumeratedSections.add(input);
          return Boolean.TRUE;
        });

    assertThat(enumeratedSections.size(), equalTo(3));

    assertThat(enumeratedSections.get(0).getSectname(), equalToObject("SECTNAME"));
    assertThat(enumeratedSections.get(0).getSegname(), equalToObject("SEGNAME"));
    assertThat(
        enumeratedSections.get(0).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x01)));

    assertThat(enumeratedSections.get(1).getSectname(), equalToObject("DECTNAME"));
    assertThat(enumeratedSections.get(1).getSegname(), equalToObject("DEGNAME"));
    assertThat(
        enumeratedSections.get(1).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x02)));

    assertThat(enumeratedSections.get(2).getSectname(), equalToObject("LECTNAME"));
    assertThat(enumeratedSections.get(2).getSegname(), equalToObject("LEGNAME"));
    assertThat(
        enumeratedSections.get(2).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x03)));
  }

  @Test
  public void testEnumeratingSections32Bit() throws Exception {
    int sectionSize = SectionTestData.getBigEndian32Bit().length;
    byte[] sectionData1 = SectionTestData.getBigEndian32Bit();
    sectionData1[43] = (byte) 0x01; // offset = 1

    byte[] sectionData2 = SectionTestData.getBigEndian32Bit();
    sectionData2[0] = (byte) 0x44; // sectname = "DECTNAME"
    sectionData2[16] = (byte) 0x44; // segname = "DEGNAME"
    sectionData2[43] = (byte) 0x02; // offset = 2

    byte[] sectionData3 = SectionTestData.getBigEndian32Bit();
    sectionData3[0] = (byte) 0x4C; // sectname = "LECTNAME"
    sectionData3[16] = (byte) 0x4C; // segname = "LEGNAME"
    sectionData3[43] = (byte) 0x03; // offset = 3

    byte[] segmentBytes = SegmentCommandTestData.getBigEndian32Bits();
    segmentBytes[51] = (byte) 0x03; // nsects = 3

    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            ByteBuffer.wrap(segmentBytes).order(ByteOrder.BIG_ENDIAN),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));

    ByteBuffer buffer =
        ByteBuffer.allocate(
            command.getLoadCommandCommonFields().getCmdsize().intValue() + 3 * sectionSize);
    buffer.order(ByteOrder.BIG_ENDIAN);
    SegmentCommandUtils.writeCommandToBuffer(command, buffer, false);
    buffer.put(sectionData1);
    buffer.put(sectionData2);
    buffer.put(sectionData3);

    List<Section> enumeratedSections = new ArrayList<>();

    SegmentCommandUtils.enumerateSectionsInSegmentLoadCommand(
        buffer,
        new MachoMagicInfo(UnsignedInteger.fromIntBits(0xFEEDFACE)),
        command,
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()),
        input -> {
          enumeratedSections.add(input);
          return Boolean.TRUE;
        });

    assertThat(enumeratedSections.size(), equalTo(3));

    assertThat(enumeratedSections.get(0).getSectname(), equalToObject("SECTNAME"));
    assertThat(enumeratedSections.get(0).getSegname(), equalToObject("SEGNAME"));
    assertThat(
        enumeratedSections.get(0).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x01)));

    assertThat(enumeratedSections.get(1).getSectname(), equalToObject("DECTNAME"));
    assertThat(enumeratedSections.get(1).getSegname(), equalToObject("DEGNAME"));
    assertThat(
        enumeratedSections.get(1).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x02)));

    assertThat(enumeratedSections.get(2).getSectname(), equalToObject("LECTNAME"));
    assertThat(enumeratedSections.get(2).getSegname(), equalToObject("LEGNAME"));
    assertThat(
        enumeratedSections.get(2).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x03)));
  }

  @Test
  public void testEnumeratingSectionsWorksRegardingOfCmdsize() throws Exception {
    // There was a bug when section's offset was computed incorrectly: offset = cmd.offset + cmdsize
    // It was fixed roughly by the next formula: offset = cmd.offset + cmd.sizeOfHeader
    // So this test checks this

    int sectionSize = SectionTestData.getBigEndian64Bit().length;
    byte[] sectionData1 = SectionTestData.getBigEndian64Bit();
    sectionData1[51] = (byte) 0x01; // offset = 1

    byte[] sectionData2 = SectionTestData.getBigEndian64Bit();
    sectionData2[0] = (byte) 0x44; // sectname = "DECTNAME"
    sectionData2[16] = (byte) 0x44; // segname = "DEGNAME"
    sectionData2[51] = (byte) 0x02; // offset = 2

    byte[] sectionData3 = SectionTestData.getBigEndian64Bit();
    sectionData3[0] = (byte) 0x4C; // sectname = "LECTNAME"
    sectionData3[16] = (byte) 0x4C; // segname = "LEGNAME"
    sectionData3[51] = (byte) 0x03; // offset = 3

    byte[] segmentBytes = SegmentCommandTestData.getBigEndian64Bits();
    segmentBytes[6] = (byte) 0xAA;
    segmentBytes[7] = (byte) 0xFF; // cmdsize = 0xAAFF == 43755 bytes!!!
    segmentBytes[67] = (byte) 0x03; // nsects = 3

    SegmentCommand command =
        SegmentCommandUtils.createFromBuffer(
            ByteBuffer.wrap(segmentBytes).order(ByteOrder.BIG_ENDIAN),
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));

    ByteBuffer buffer =
        ByteBuffer.allocate(
            command.getLoadCommandCommonFields().getCmdsize().intValue() + 3 * sectionSize);
    buffer.order(ByteOrder.BIG_ENDIAN);
    SegmentCommandUtils.writeCommandToBuffer(command, buffer, true);
    buffer.put(sectionData1);
    buffer.put(sectionData2);
    buffer.put(sectionData3);

    List<Section> enumeratedSections = new ArrayList<>();

    SegmentCommandUtils.enumerateSectionsInSegmentLoadCommand(
        buffer,
        new MachoMagicInfo(UnsignedInteger.fromIntBits(0xFEEDFACF)),
        command,
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()),
        input -> {
          enumeratedSections.add(input);
          return Boolean.TRUE;
        });

    assertThat(enumeratedSections.size(), equalTo(3));

    assertThat(enumeratedSections.get(0).getSectname(), equalToObject("SECTNAME"));
    assertThat(enumeratedSections.get(0).getSegname(), equalToObject("SEGNAME"));
    assertThat(
        enumeratedSections.get(0).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x01)));

    assertThat(enumeratedSections.get(1).getSectname(), equalToObject("DECTNAME"));
    assertThat(enumeratedSections.get(1).getSegname(), equalToObject("DEGNAME"));
    assertThat(
        enumeratedSections.get(1).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x02)));

    assertThat(enumeratedSections.get(2).getSectname(), equalToObject("LECTNAME"));
    assertThat(enumeratedSections.get(2).getSegname(), equalToObject("LEGNAME"));
    assertThat(
        enumeratedSections.get(2).getOffset(), equalToObject(UnsignedInteger.fromIntBits(0x03)));
  }
}
