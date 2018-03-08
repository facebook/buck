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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class SymTabCommandUtilsTest {
  @Test
  public void testGettingNlistAtIndex64BitBigEndian() throws Exception {
    checkWithBytes(NlistTestData.getBigEndian64Bit(), true, false);
  }

  @Test
  public void testGettingNlistAtIndex32BitBigEndian() throws Exception {
    checkWithBytes(NlistTestData.getBigEndian32Bit(), false, false);
  }

  @Test
  public void testGettingNlistAtIndex64BitLittleEndian() throws Exception {
    checkWithBytes(NlistTestData.getLittleEndian64Bit(), true, true);
  }

  @Test
  public void testGettingNlistAtIndex32BitLittleEndian() throws Exception {
    checkWithBytes(NlistTestData.getLittleEndian32Bit(), false, true);
  }

  private void checkWithBytes(byte[] nlistTemplateBytes, boolean is64Bit, boolean isSwapped) {

    byte[] nlistBytes1 = Arrays.copyOf(nlistTemplateBytes, nlistTemplateBytes.length);
    if (isSwapped) {
      nlistBytes1[0] = (byte) 0x01; // strx
    } else {
      nlistBytes1[3] = (byte) 0x01; // strx
    }
    nlistBytes1[4] = (byte) 0x11; // type

    byte[] nlistBytes2 = Arrays.copyOf(nlistTemplateBytes, nlistTemplateBytes.length);
    if (isSwapped) {
      nlistBytes2[0] = (byte) 0x02; // strx
    } else {
      nlistBytes2[3] = (byte) 0x02; // strx
    }
    nlistBytes2[4] = (byte) 0x22; // type

    byte[] commandBytes;
    if (isSwapped) {
      commandBytes = SymTabCommandTestData.getLittleEndian();
    } else {
      commandBytes = SymTabCommandTestData.getBigEndian();
    }
    int cmdSize = commandBytes.length;
    if (isSwapped) {
      commandBytes[8] = (byte) cmdSize; // symoff
      commandBytes[12] = (byte) 2; // nsyms
    } else {
      commandBytes[11] = (byte) cmdSize; // symoff
      commandBytes[15] = (byte) 2; // nsyms
    }

    ByteBuffer commandBuffer =
        ByteBuffer.wrap(commandBytes)
            .order(isSwapped ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

    SymTabCommand symTabCommand = SymTabCommandUtils.createFromBuffer(commandBuffer);
    assertThat(symTabCommand.getSymoff(), equalToObject(UnsignedInteger.fromIntBits(cmdSize)));
    assertThat(symTabCommand.getNsyms(), equalToObject(UnsignedInteger.fromIntBits(2)));

    ByteBuffer byteBuffer =
        ByteBuffer.allocate(cmdSize + nlistTemplateBytes.length * 2)
            .order(commandBuffer.order())
            .put(commandBytes)
            .put(nlistBytes1)
            .put(nlistBytes2);

    Nlist entry1 = SymTabCommandUtils.getNlistAtIndex(byteBuffer, symTabCommand, 0, is64Bit);
    assertThat(entry1.getN_strx(), equalToObject(UnsignedInteger.fromIntBits(0x01)));
    assertThat(entry1.getN_type(), equalToObject(UnsignedInteger.fromIntBits(0x11)));

    Nlist entry2 = SymTabCommandUtils.getNlistAtIndex(byteBuffer, symTabCommand, 1, is64Bit);
    assertThat(entry2.getN_strx(), equalToObject(UnsignedInteger.fromIntBits(0x02)));
    assertThat(entry2.getN_type(), equalToObject(UnsignedInteger.fromIntBits(0x22)));
  }

  @Test
  public void testGettingStringTableValueForNlist() throws Exception {
    String stringTableEntry = "string_table_entry";

    byte[] nlistBytes = NlistTestData.getBigEndian64Bit();
    nlistBytes[3] = (byte) (0x01); // strx - first entry

    byte[] commandBytes = SymTabCommandTestData.getBigEndian();
    int cmdSize = commandBytes.length;
    commandBytes[11] = (byte) cmdSize; // symoff
    commandBytes[15] = (byte) 1; // nsyms
    commandBytes[19] = (byte) (cmdSize + nlistBytes.length); // stroff
    commandBytes[23] = (byte) (stringTableEntry.length() + 1); // strsize

    ByteBuffer byteBuffer =
        ByteBuffer.allocate(
                commandBytes.length + nlistBytes.length + 1 + stringTableEntry.length() + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .put(commandBytes)
            .put(nlistBytes)
            .put((byte) 0x00)
            .put(stringTableEntry.getBytes(StandardCharsets.UTF_8))
            .put((byte) 0x00);

    byteBuffer.position(0);
    SymTabCommand symTabCommand = SymTabCommandUtils.createFromBuffer(byteBuffer);
    assertThat(symTabCommand.getSymoff(), equalToObject(UnsignedInteger.fromIntBits(cmdSize)));
    assertThat(symTabCommand.getNsyms(), equalToObject(UnsignedInteger.fromIntBits(1)));

    byteBuffer.position(cmdSize);
    Nlist nlist = NlistUtils.createFromBuffer(byteBuffer, true);
    assertThat(nlist.getN_strx(), equalToObject(UnsignedInteger.fromIntBits(1)));

    String result =
        SymTabCommandUtils.getStringTableEntryForNlist(
            byteBuffer,
            symTabCommand,
            nlist,
            new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()));
    assertThat(result, equalToObject(stringTableEntry));
  }

  @Test
  public void testGettingStringTableEntrySize() {
    assertThat(SymTabCommandUtils.sizeOfStringTableEntryWithContents("abc"), equalTo(4));
  }

  @Test
  public void testInsertingNewStringTableEntry() {
    byte[] commandBytes = SymTabCommandTestData.getBigEndian();
    int cmdSize = commandBytes.length;
    commandBytes[11] = (byte) cmdSize; // symoff
    commandBytes[15] = (byte) 0; // nsyms
    commandBytes[19] = (byte) cmdSize; // stroff
    commandBytes[23] = (byte) 20; // strsize

    String content = "new_entry";

    ByteBuffer byteBuffer =
        ByteBuffer.allocate(
                cmdSize + 20 + SymTabCommandUtils.sizeOfStringTableEntryWithContents(content))
            .order(ByteOrder.BIG_ENDIAN)
            .put(commandBytes)
            .put(new byte[20]);

    byteBuffer.position(0);
    SymTabCommand symTabCommand = SymTabCommandUtils.createFromBuffer(byteBuffer);

    UnsignedInteger offset =
        SymTabCommandUtils.insertNewStringTableEntry(byteBuffer, symTabCommand, content);
    assertThat(offset, equalToObject(UnsignedInteger.fromIntBits(20)));

    byteBuffer.position(symTabCommand.getStroff().plus(offset).intValue());
    byte[] entryBytes = new byte[content.length()];
    byteBuffer.get(entryBytes, 0, content.length());
    assertThat(entryBytes, equalTo(content.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void testUpdatingSymTabCommand() {
    byte[] commandBytes = SymTabCommandTestData.getBigEndian();
    int cmdSize = commandBytes.length;
    commandBytes[11] = (byte) cmdSize; // symoff
    commandBytes[15] = (byte) 3; // nsyms
    commandBytes[18] = (byte) cmdSize; // stroff
    commandBytes[23] = (byte) 20; // strsize

    SymTabCommand symTabCommand =
        SymTabCommandUtils.createFromBuffer(
            ByteBuffer.wrap(commandBytes).order(ByteOrder.BIG_ENDIAN));

    String content = "new_entry";

    ByteBuffer byteBuffer =
        ByteBuffer.allocate(
                cmdSize + 20 + SymTabCommandUtils.sizeOfStringTableEntryWithContents(content))
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(SymTabCommand.LC_SYMTAB.intValue())
            .putInt(cmdSize)
            .put(commandBytes);

    SymTabCommand updated =
        SymTabCommandUtils.updateSymTabCommand(byteBuffer, symTabCommand, content);

    assertThat(
        updated.getStrsize(),
        equalToObject(
            symTabCommand
                .getStrsize()
                .plus(
                    UnsignedInteger.fromIntBits(
                        SymTabCommandUtils.sizeOfStringTableEntryWithContents(content)))));

    byteBuffer.position(updated.getLoadCommandCommonFields().getOffsetInBinary());

    byte[] updatedBytes = new byte[commandBytes.length];
    byteBuffer.get(updatedBytes, 0, updatedBytes.length);

    SymTabCommand commandFromBuffer =
        SymTabCommandUtils.createFromBuffer(
            ByteBuffer.wrap(updatedBytes).order(ByteOrder.BIG_ENDIAN));
    assertThat(commandFromBuffer.getSymoff(), equalToObject(updated.getSymoff()));
    assertThat(commandFromBuffer.getNsyms(), equalToObject(updated.getNsyms()));
    assertThat(commandFromBuffer.getStroff(), equalToObject(updated.getStroff()));
    assertThat(commandFromBuffer.getStrsize(), equalToObject(updated.getStrsize()));
  }

  @Test
  public void testCheckingForNulValue() {
    byte[] nlistBytes = NlistTestData.getBigEndian64Bit();
    nlistBytes[3] = (byte) 0x00; // strx - first entry

    byte[] commandBytes = SymTabCommandTestData.getBigEndian();
    int cmdSize = commandBytes.length;
    commandBytes[11] = (byte) cmdSize; // symoff
    commandBytes[15] = (byte) 1; // nsyms
    commandBytes[19] = (byte) (cmdSize + nlistBytes.length); // stroff
    commandBytes[23] = (byte) 0x00; // strsize

    ByteBuffer byteBuffer =
        ByteBuffer.allocate(cmdSize + nlistBytes.length)
            .order(ByteOrder.BIG_ENDIAN)
            .put(commandBytes)
            .put(nlistBytes);

    byteBuffer.position(cmdSize);
    Nlist nlist = NlistUtils.createFromBuffer(byteBuffer, false);

    assertThat(SymTabCommandUtils.stringTableEntryIsNull(nlist), equalTo(true));
  }

  @Test
  public void testCheckingSlashesAtStartAndEnd() {
    byte[] nlistBytes = NlistTestData.getBigEndian64Bit();
    nlistBytes[3] = (byte) 0x01; // strx

    String entryContents = "/some/path/";

    byte[] commandBytes = SymTabCommandTestData.getBigEndian();
    int cmdSize = commandBytes.length;
    commandBytes[11] = (byte) cmdSize; // symoff
    commandBytes[15] = (byte) 1; // nsyms
    commandBytes[19] = (byte) (cmdSize + nlistBytes.length); // stroff
    commandBytes[23] = (byte) (1 + entryContents.length() + 1); // strsize - nul + contents + nul

    ByteBuffer byteBuffer =
        ByteBuffer.allocate(cmdSize + nlistBytes.length + 1 + entryContents.length() + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .put(commandBytes)
            .put(nlistBytes)
            .put((byte) 0x00)
            .put(entryContents.getBytes(StandardCharsets.UTF_8))
            .put((byte) 0x00);

    byteBuffer.position(0);
    SymTabCommand symTabCommand = SymTabCommandUtils.createFromBuffer(byteBuffer);

    byteBuffer.position(cmdSize);
    Nlist nlist = NlistUtils.createFromBuffer(byteBuffer, false);

    assertThat(
        SymTabCommandUtils.stringTableEntryStartsWithSlash(byteBuffer, symTabCommand, nlist),
        equalTo(true));
    assertThat(
        SymTabCommandUtils.stringTableEntryEndsWithSlash(byteBuffer, symTabCommand, nlist),
        equalTo(true));
  }

  @Test
  public void testCheckingForEmptyString() {
    byte[] nlistBytes = NlistTestData.getBigEndian64Bit();
    nlistBytes[3] = (byte) 0x01; // strx

    byte[] commandBytes = SymTabCommandTestData.getBigEndian();
    int cmdSize = commandBytes.length;
    commandBytes[11] = (byte) cmdSize; // symoff
    commandBytes[15] = (byte) 1; // nsyms
    commandBytes[19] = (byte) (cmdSize + nlistBytes.length); // stroff
    commandBytes[23] = (byte) (1 + 1); // strsize - nul + nul

    ByteBuffer byteBuffer =
        ByteBuffer.allocate(cmdSize + nlistBytes.length + 1 + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .put(commandBytes)
            .put(nlistBytes)
            .put((byte) 0x00)
            .put((byte) 0x00);

    byteBuffer.position(0);
    SymTabCommand symTabCommand = SymTabCommandUtils.createFromBuffer(byteBuffer);

    byteBuffer.position(cmdSize);
    Nlist nlist = NlistUtils.createFromBuffer(byteBuffer, false);

    assertThat(
        SymTabCommandUtils.stringTableEntryIsEmptyString(byteBuffer, symTabCommand, nlist),
        equalTo(true));
  }
}
