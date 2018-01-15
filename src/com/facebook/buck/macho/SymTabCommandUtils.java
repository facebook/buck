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

import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedInteger;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class SymTabCommandUtils {

  private static final byte SLASH_BYTE = (byte) 0x2F;
  private static final byte NUL_BYTE = (byte) 0x00;

  private SymTabCommandUtils() {}

  public static SymTabCommand createFromBuffer(ByteBuffer buffer) {
    return SymTabCommand.of(
        LoadCommandCommonFieldsUtils.createFromBuffer(buffer),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()));
  }

  public static void writeCommandToBuffer(SymTabCommand command, ByteBuffer buffer) {
    LoadCommandCommonFieldsUtils.writeCommandToBuffer(command.getLoadCommandCommonFields(), buffer);
    buffer
        .putInt(command.getSymoff().intValue())
        .putInt(command.getNsyms().intValue())
        .putInt(command.getStroff().intValue())
        .putInt(command.getStrsize().intValue());
  }

  /**
   * Creates and returns the Nlist entry at the given index for the given SymTabCommand.
   *
   * @param buffer The buffer which holds all data.
   * @param command The command that describes the Nlist entries.
   * @param index Index of the entry that should be returned.
   * @param is64Bit Indicates if binary is 64 bit or not.
   * @return The Nlist entry at given index for the given SymTabCommand.
   * @throws IOException
   */
  public static Nlist getNlistAtIndex(
      ByteBuffer buffer, SymTabCommand command, int index, boolean is64Bit) {
    final int nlistSizeInBytes = NlistUtils.getSizeInBytes(is64Bit);
    final int offset = command.getSymoff().intValue() + index * nlistSizeInBytes;
    buffer.position(offset);
    return NlistUtils.createFromBuffer(buffer, is64Bit);
  }

  /**
   * Returns the string entry from the string table described by the given Nlist entry of the given
   * command.
   *
   * @param buffer The buffer which holds all data.
   * @param command SymTabCommand that has reference to the string table.
   * @param nlist The entry which describes the reference to the string table which value should be
   *     returned.
   * @return The value from string table that Nlist refers to.
   * @throws IOException
   */
  public static String getStringTableEntryForNlist(
      ByteBuffer buffer, SymTabCommand command, Nlist nlist, NulTerminatedCharsetDecoder decoder)
      throws IOException {
    int entryIndex = nlist.getN_strx().intValue();
    Preconditions.checkArgument(entryIndex != 0, "Nlist has null strx value");
    int offset = command.getStroff().intValue() + nlist.getN_strx().intValue();
    buffer.position(offset);
    return decoder.decodeString(buffer);
  }

  /**
   * Quick check if Nlist string table entry is a null value.
   *
   * @param nlist Nlist which strx value should be checked for null value.
   * @return true if Nlist string table value is null.
   */
  public static boolean stringTableEntryIsNull(Nlist nlist) {
    return nlist.getN_strx().intValue() == 0;
  }

  /**
   * Quick check if Nlist string table entry starts with a slash symbol. Nlist's string value must
   * not be null.
   *
   * @param nlist Nlist which strx value should be checked .
   * @return true if Nlist string table value is null.
   */
  public static boolean stringTableEntryStartsWithSlash(
      ByteBuffer buffer, SymTabCommand command, Nlist nlist) {
    Preconditions.checkArgument(
        !stringTableEntryIsNull(nlist), "Provided nlist object has null string table value");
    int offset = command.getStroff().intValue() + nlist.getN_strx().intValue();
    buffer.position(offset);
    byte value = buffer.get();
    buffer.position(offset);
    return value == SLASH_BYTE;
  }

  /**
   * Quick check if Nlist string table entry ends with a slash symbol. Nlist's string value must not
   * be null.
   *
   * @param nlist Nlist which strx value should be checked .
   * @return true if Nlist string table value is null.
   */
  public static boolean stringTableEntryEndsWithSlash(
      ByteBuffer buffer, SymTabCommand command, Nlist nlist) {
    Preconditions.checkArgument(!stringTableEntryIsNull(nlist));
    int offset = command.getStroff().intValue() + nlist.getN_strx().intValue();
    buffer.position(offset);
    byte lastNonNullValue;
    byte lastValue = 0;
    do {
      lastNonNullValue = lastValue;
      lastValue = buffer.get();
    } while (lastValue != NUL_BYTE);
    buffer.position(offset);
    return lastNonNullValue == SLASH_BYTE;
  }

  /**
   * Quick check if Nlist string table entry is an empty string value. Nlist's string value must not
   * be null.
   *
   * @param nlist Nlist which strx value should be checked for empty string value.
   * @return true if Nlist string table value is empty string.
   */
  public static boolean stringTableEntryIsEmptyString(
      ByteBuffer buffer, SymTabCommand command, Nlist nlist) {
    Preconditions.checkArgument(
        !stringTableEntryIsNull(nlist), "Provided nlist object has null string table value");
    int offset = command.getStroff().intValue() + nlist.getN_strx().intValue();
    buffer.position(offset);
    byte value = buffer.get();
    buffer.position(offset);
    return value == NUL_BYTE;
  }

  /**
   * This method updates the given buffer by updating the given SymTabCommand to reflect the
   * insertion of the entry into string table.
   *
   * @param buffer The buffer which holds all the data that needs to be updated.
   * @param command The old SymTabCommand that does not reflect the updated string table yet.
   * @param newEntryContents Contents of the new string table entry that previously has been
   *     inserted into the string table.
   * @return New SymTabCommand that is updated to reflect the availability of the new entry in its
   *     string table.
   * @throws IOException
   */
  public static SymTabCommand updateSymTabCommand(
      ByteBuffer buffer, SymTabCommand command, String newEntryContents) {
    SymTabCommand updatedCommand =
        getUpdatedSymTabCommandWithNewEntryContents(command, newEntryContents);
    writeCommand(buffer, command, updatedCommand);
    return updatedCommand;
  }

  /**
   * @param entry The contents of the string table entry.
   * @return The actual size of the entry for the given string.
   */
  public static int sizeOfStringTableEntryWithContents(String entry) {
    return entry.getBytes(StandardCharsets.UTF_8).length + 1; // + 1 for null terminator
  }

  /**
   * Inserts the given string into the string buffer and returns its location in the string table.
   *
   * @param buffer the buffer which contains all data that should be modified. Buffer should be
   *     ready to accept new bytes at its end.
   * @param command SymTabCommand which string table is being updated.
   * @param newEntryContents the value for new entry.
   * @return the location of the newly inserted entry in the string table.
   * @throws IOException
   */
  public static UnsignedInteger insertNewStringTableEntry(
      ByteBuffer buffer, SymTabCommand command, String newEntryContents) {
    buffer.position(command.getStroff().intValue() + command.getStrsize().intValue());
    buffer.put(newEntryContents.getBytes(StandardCharsets.UTF_8));
    buffer.put(NUL_BYTE);
    return command.getStrsize();
  }

  /**
   * Updates the given SymTabCommand with the new data in the given buffer.
   *
   * @param buffer The buffer which holds the data for the old command
   * @param command Old command
   * @param updatedCommand New command that should replace the old command
   * @throws IOException
   */
  private static void writeCommand(
      ByteBuffer buffer, SymTabCommand command, SymTabCommand updatedCommand) {
    Preconditions.checkArgument(
        command.getLoadCommandCommonFields().getOffsetInBinary()
            == updatedCommand.getLoadCommandCommonFields().getOffsetInBinary());
    Preconditions.checkArgument(
        command
            .getLoadCommandCommonFields()
            .getCmd()
            .equals(updatedCommand.getLoadCommandCommonFields().getCmd()));
    Preconditions.checkArgument(
        command
            .getLoadCommandCommonFields()
            .getCmdsize()
            .equals(updatedCommand.getLoadCommandCommonFields().getCmdsize()));

    buffer.position(command.getLoadCommandCommonFields().getOffsetInBinary());
    writeCommandToBuffer(updatedCommand, buffer);
  }

  /**
   * Constructs new SymTabCommand which would contain the given string table entry.
   *
   * @param command The old command which must be updated to include the given string table entry.
   * @param newEntryContents String that was inserted into string table.
   * @return new command which string table size is updated to include the new string table entry.
   */
  private static SymTabCommand getUpdatedSymTabCommandWithNewEntryContents(
      SymTabCommand command, String newEntryContents) {
    return command.withStrsize(
        command
            .getStrsize()
            .plus(
                UnsignedInteger.fromIntBits(sizeOfStringTableEntryWithContents(newEntryContents))));
  }
}
