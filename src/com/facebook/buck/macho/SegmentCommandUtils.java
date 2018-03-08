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

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

public class SegmentCommandUtils {

  private SegmentCommandUtils() {}

  /**
   * VM size field of the SegmentCommand needs to be aligned. This method returns the alignment for
   * the given value.
   *
   * @param value The value that needs to be aligned.
   * @return Aligned value.
   */
  public static int alignValue(int value) {
    /**
     * According to lld sources, different CPU architectures have different code alignment (see
     * links below):
     *
     * <p>- x86_64 has 2Kb code alignment - x86 has 2Kb code alignment - arm64 has 4Kb code
     * alignment - arm has 4Kb code alignment
     *
     * <p>Since Mach O file does not have this information, but sections have to be aligned (even
     * though not all of them appear to be aligned), we will align to 32Kb. Thus, it potentially
     * should cover all possible alignments and even more that needed.
     *
     * <p>https://github.com/llvm-mirror/lld/blob/master/lib/ReaderWriter/MachO/ArchHandler_x86_64.cpp
     * https://github.com/llvm-mirror/lld/blob/master/lib/ReaderWriter/MachO/ArchHandler_x86.cpp
     * https://github.com/llvm-mirror/lld/blob/master/lib/ReaderWriter/MachO/ArchHandler_arm64.cpp
     * https://github.com/llvm-mirror/lld/blob/master/lib/ReaderWriter/MachO/ArchHandler_arm.cpp
     */
    int universalAlignment = 32 * 1024;
    return universalAlignment * (int) (Math.ceil((double) value / universalAlignment));
  }

  /**
   * This method returns the amount of bytes that segment command is taking. Segment's section bytes
   * are laid out in binary after the segment command header.
   *
   * @param segmentCommand Command which header size is being determined
   * @return The size of the segment command header, after which the sections are present.
   */
  @VisibleForTesting
  static int getSegmentCommandHeaderSize(SegmentCommand segmentCommand) {
    if (segmentCommand.getLoadCommandCommonFields().getCmd().equals(SegmentCommand.LC_SEGMENT_64)) {
      return SegmentCommand.SIZE_IN_BYTES_64_BIT;
    } else {
      return SegmentCommand.SIZE_IN_BYTES_32_BIT;
    }
  }

  /**
   * Updates the given command in the given buffer with the new given command.
   *
   * @param buffer The buffer which holds all data.
   * @param old The old command that needs to be updated with the contents of the new command in the
   *     given buffer.
   * @param updated The updated command, which bytes will be used to override the old commad.
   * @throws IOException
   */
  public static void updateSegmentCommand(
      ByteBuffer buffer, SegmentCommand old, SegmentCommand updated) {
    Preconditions.checkArgument(
        old.getLoadCommandCommonFields().getOffsetInBinary()
            == updated.getLoadCommandCommonFields().getOffsetInBinary());
    Preconditions.checkArgument(
        old.getLoadCommandCommonFields()
            .getCmd()
            .equals(updated.getLoadCommandCommonFields().getCmd()));
    Preconditions.checkArgument(
        old.getLoadCommandCommonFields()
            .getCmdsize()
            .equals(updated.getLoadCommandCommonFields().getCmdsize()));
    buffer.position(old.getLoadCommandCommonFields().getOffsetInBinary());
    writeCommandToBuffer(
        updated,
        buffer,
        updated.getLoadCommandCommonFields().getCmd().equals(SegmentCommand.LC_SEGMENT_64));
  }

  /**
   * Enumerates the sections in the given segment command by calling the given callback.
   *
   * @param buffer The buffer which holds all data.
   * @param magicInfo Mach Header Magic info.
   * @param segmentCommand The SegmentCommand which Sections should be enumerated.
   * @param callback The Function object which should be called on each Section. The argument of the
   *     function is the Section object. If Function returns Boolean.TRUE then enumeration will
   *     continue; otherwise enumeration will stop and callback will not be called anymore.
   * @throws IOException
   */
  public static void enumerateSectionsInSegmentLoadCommand(
      ByteBuffer buffer,
      MachoMagicInfo magicInfo,
      SegmentCommand segmentCommand,
      NulTerminatedCharsetDecoder decoder,
      Function<Section, Boolean> callback)
      throws IOException {
    int sectionHeaderSize = SectionUtils.sizeOfSectionHeader(magicInfo.is64Bit());
    int sectionsOffset =
        segmentCommand.getLoadCommandCommonFields().getOffsetInBinary()
            + SegmentCommandUtils.getSegmentCommandHeaderSize(segmentCommand);
    for (int i = 0; i < segmentCommand.getNsects().intValue(); i++) {
      int offsetInBinary = sectionsOffset + sectionHeaderSize * i;
      buffer.position(offsetInBinary);
      Section section = SectionUtils.createFromBuffer(buffer, magicInfo.is64Bit(), decoder);
      boolean shouldContinue = callback.apply(section);
      if (!shouldContinue) {
        break;
      }
    }
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  public static SegmentCommand createFromBuffer(
      ByteBuffer buffer, NulTerminatedCharsetDecoder decoder) {
    LoadCommandCommonFields fields = LoadCommandCommonFieldsUtils.createFromBuffer(buffer);
    Preconditions.checkArgument(SegmentCommand.VALID_CMD_VALUES.contains(fields.getCmd()));
    boolean is64Bit = fields.getCmd().equals(SegmentCommand.LC_SEGMENT_64);

    String segname;
    try {
      segname = decoder.decodeString(buffer);
    } catch (CharacterCodingException e) {
      throw new HumanReadableException(
          e, "Cannot read segname for SegmentCommand at %d", fields.getOffsetInBinary());
    }
    buffer.position(
        fields.getOffsetInBinary()
            + LoadCommandCommonFields.CMD_AND_CMDSIZE_SIZE
            + SegmentCommand.SEGNAME_SIZE_IN_BYTES);
    return SegmentCommand.of(
        fields,
        segname,
        UnsignedLong.fromLongBits(is64Bit ? buffer.getLong() : buffer.getInt() & 0xFFFFFFFFL),
        UnsignedLong.fromLongBits(is64Bit ? buffer.getLong() : buffer.getInt() & 0xFFFFFFFFL),
        UnsignedLong.fromLongBits(is64Bit ? buffer.getLong() : buffer.getInt() & 0xFFFFFFFFL),
        UnsignedLong.fromLongBits(is64Bit ? buffer.getLong() : buffer.getInt() & 0xFFFFFFFFL),
        buffer.getInt(),
        buffer.getInt(),
        UnsignedInteger.fromIntBits(buffer.getInt()),
        UnsignedInteger.fromIntBits(buffer.getInt()));
  }

  public static void writeCommandToBuffer(
      SegmentCommand command, ByteBuffer buffer, boolean is64Bit) {
    LoadCommandCommonFieldsUtils.writeCommandToBuffer(command.getLoadCommandCommonFields(), buffer);
    byte[] segnameStringBytes = command.getSegname().getBytes(StandardCharsets.UTF_8);
    buffer
        .put(segnameStringBytes)
        .get(new byte[SegmentCommand.SEGNAME_SIZE_IN_BYTES - segnameStringBytes.length]);
    if (is64Bit) {
      buffer
          .putLong(command.getVmaddr().longValue())
          .putLong(command.getVmsize().longValue())
          .putLong(command.getFileoff().longValue())
          .putLong(command.getFilesize().longValue());
    } else {
      buffer
          .putInt(command.getVmaddr().intValue())
          .putInt(command.getVmsize().intValue())
          .putInt(command.getFileoff().intValue())
          .putInt(command.getFilesize().intValue());
    }
    buffer
        .putInt(command.getMaxprot())
        .putInt(command.getInitprot())
        .putInt(command.getNsects().intValue())
        .putInt(command.getFlags().intValue());
  }
}
