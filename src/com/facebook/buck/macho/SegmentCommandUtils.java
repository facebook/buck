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

import com.facebook.buck.charset.NulTerminatedCharsetDecoder;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

public class SegmentCommandUtils {

  private SegmentCommandUtils() {}

  /**
   * VM size field of the SegmentCommand needs to be aligned. This method returns the alignment
   * for the given value.
   * @param value The value that needs to be aligned.
   * @return Aligned value.
   */
  public static int alignValue(int value) {
    /**
     * According to lld sources, different CPU architectures have different code alignment (see
     * links below):
     *
     *   - x86_64 has 2Kb code alignment
     *   - x86 has 2Kb code alignment
     *   - arm64 has 4Kb code alignment
     *   - arm has 4Kb code alignment
     *
     * Since Mach O file does not have this information, but sections have to be aligned (even
     * though not all of them appear to be aligned), we will align to 32Kb. Thus, it potentially
     * should cover all possible alignments and even more that needed.
     *
     * https://github.com/llvm-mirror/lld/blob/master/lib/ReaderWriter/MachO/ArchHandler_x86_64.cpp
     * https://github.com/llvm-mirror/lld/blob/master/lib/ReaderWriter/MachO/ArchHandler_x86.cpp
     * https://github.com/llvm-mirror/lld/blob/master/lib/ReaderWriter/MachO/ArchHandler_arm64.cpp
     * https://github.com/llvm-mirror/lld/blob/master/lib/ReaderWriter/MachO/ArchHandler_arm.cpp
     */
    final int universalAlignment = 32 * 1024;
    return universalAlignment * (int) (Math.ceil((double) value / universalAlignment));
  }

  /**
   * Updates the given command in the given buffer with the new given command.
   * @param buffer The buffer which holds all data.
   * @param old The old command that needs to be updated with the contents of the new command
   *                in the given buffer.
   * @param updated The updated command, which bytes will be used to override the old commad.
   * @throws IOException
   */
  public static void updateSegmentCommand(
      ByteBuffer buffer,
      SegmentCommand old,
      SegmentCommand updated,
      boolean is64Bit) throws IOException {
    Preconditions.checkArgument(
        old.getLoadCommand().getOffsetInBinary() == updated.getLoadCommand().getOffsetInBinary());
    Preconditions.checkArgument(
        old.getLoadCommand().getCmd().equals(updated.getLoadCommand().getCmd()));
    Preconditions.checkArgument(
        old.getLoadCommand().getCmdsize().equals(updated.getLoadCommand().getCmdsize()));
    buffer.position(old.getLoadCommand().getOffsetInBinary());
    writeCommandToBuffer(updated, buffer, is64Bit);
  }

  /**
   * Enumerates the sections in the given segment command by calling the given callback.
   * @param buffer The buffer which holds all data.
   * @param magicInfo Mach Header Magic info.
   * @param segmentCommand The SegmentCommand which Sections should be enumerated.
   * @param callback The Function object which should be called on each Section. The argument of the
   *                 function is the Section object. If Function returns Boolean.TRUE then
   *                 enumeration will continue; otherwise enumeration will stop and callback will
   *                 not be called anymore.
   * @throws IOException
   */
  public static void enumerateSectionsInSegmentLoadCommand(
      ByteBuffer buffer,
      MachoMagicInfo magicInfo,
      SegmentCommand segmentCommand,
      Function<Section, Boolean> callback) throws IOException {
    final int sectionHeaderSize = SectionUtils.sizeOfSectionHeader(magicInfo.is64Bit());
    final int sectionsOffset = segmentCommand.getLoadCommand().getOffsetInBinary() +
        segmentCommand.getLoadCommand().getCmdsize().intValue();
    for (int i = 0; i < segmentCommand.getNsects().intValue(); i++) {
      int offsetInBinary = sectionsOffset + sectionHeaderSize * i;
      buffer.position(offsetInBinary);
      Section section = SectionUtils.createFromBuffer(buffer, magicInfo.is64Bit());
      boolean shouldContinue = callback.apply(section);
      if (!shouldContinue) {
        break;
      }
    }
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  public static SegmentCommand createFromBuffer(ByteBuffer buffer, boolean is64Bit) {
    int offset = buffer.position();
    UnsignedInteger cmd = UnsignedInteger.fromIntBits(buffer.getInt());
    UnsignedInteger cmdsize = UnsignedInteger.fromIntBits(buffer.getInt());

    String segname = null;
    try {
      segname = NulTerminatedCharsetDecoder.decodeUTF8String(buffer);
    } catch (CharacterCodingException e) {
      throw new HumanReadableException(
          e,
          "Cannot read segname for SegmentCommand at %d", offset);
    }
    buffer.position(
        offset +
        LoadCommand.CMD_AND_CMDSIZE_SIZE +
        SegmentCommand.SEGNAME_SIZE_IN_BYTES);
    return SegmentCommand.of(
        LoadCommand.of(
            offset,
            cmd,
            cmdsize),
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
      SegmentCommand command,
      ByteBuffer buffer,
      boolean is64Bit) {
    LoadCommandUtils.writeCommandToBuffer(command.getLoadCommand(), buffer);
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
