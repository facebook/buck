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
import com.facebook.buck.log.Logger;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;

public class CompDirReplacer {

  private static final Logger LOG = Logger.get(CompDirReplacer.class);

  private final ByteBuffer buffer;

  public CompDirReplacer(ByteBuffer byteBuffer) {
    this.buffer = byteBuffer;
  }

  private void processThinBinary(
      final MachoMagicInfo magicInfo,
      final String oldCompDir,
      final String updatedCompDir) throws IOException {
    buffer.position(0);
    LoadCommandUtils.enumerateLoadCommandsInFile(
        buffer,
        new Function<LoadCommand, Boolean>() {
          @Override
          public Boolean apply(LoadCommand input) {
            boolean isSegmentCommand = input instanceof SegmentCommand;
            if (isSegmentCommand) {
              processSectionsInSegmentCommand(
                  (SegmentCommand) input,
                  magicInfo,
                  oldCompDir,
                  updatedCompDir);
            }
            return !isSegmentCommand;
          }
        });
  }

  private void processSectionsInSegmentCommand(
      SegmentCommand segmentCommand,
      MachoMagicInfo magicInfo,
      final String oldCompDir,
      final String updatedCompDir) {
    try {
      SegmentCommandUtils.enumerateSectionsInSegmentLoadCommand(
          buffer,
          magicInfo,
          segmentCommand,
          new Function<Section, Boolean>() {
            @Override
            public Boolean apply(Section input) {
              return updateCompDirInSection(input, oldCompDir, updatedCompDir);
            }
          });
    } catch (IOException e) {
      LOG.error(e, "Unable to process __DWARF.__debug_str section");
    }
  }

  private Boolean updateCompDirInSection(
      Section section,
      String oldCompDir,
      String updatedCompDir) {
    if (section.getSegname().equals(CommandSegmentSectionNames.SEGMENT_NAME_DWARF) &&
        section.getSectname().equals(CommandSegmentSectionNames.SECTION_NAME_DEBUG_STR)) {
      findAndUpdateCompDirInDebugSection(section, oldCompDir, updatedCompDir);
      return false;
    }
    return true;
  }

  private void findAndUpdateCompDirInDebugSection(
      Section section,
      String oldCompDir,
      String updatedCompDir) {
    final long maximumValidOffset = section.getOffset().longValue() + section.getSize().longValue();
    int offset = section.getOffset().intValue();
    while (offset < maximumValidOffset) {
      buffer.position(offset);
      String string = null;
      try {
        string = NulTerminatedCharsetDecoder.decodeUTF8String(buffer);
      } catch (CharacterCodingException e) {
        LOG.error(e, "Unable to read read string from debug string table, offset %d", offset);
        break;
      }
      if (string.equals(oldCompDir)) {
        LOG.verbose("Found comp dir at %d, overwriting it with %s", offset, updatedCompDir);
        buffer.position(offset);
        buffer.put(updatedCompDir.getBytes(Charsets.UTF_8));
        buffer.put((byte) 0x00);
        break;
      }
      offset += SymTabCommandUtils.sizeOfStringTableEntryWithContents(string);
    }
  }

  public void replaceCompDir(String oldCompDir, String updatedCompDir) throws IOException {
    Preconditions.checkArgument(
        oldCompDir.length() >= updatedCompDir.length(),
        "Updated compdir length must be less or equal to old compdir length as replace is " +
            "performed in place");
    Preconditions.checkArgument(
        !oldCompDir.equals(updatedCompDir),
        "Updated compdir must be different from old compdir");

    MachoMagicInfo magicInfo = MachoMagicInfoUtils.getMachMagicInfo(buffer);
    if (!magicInfo.isValidMachMagic()) {
      throw new IOException("Cannot locate magic for Mach O binary.");
    }
    if (magicInfo.isFatBinaryHeaderMagic()) {
      throw new IOException("Fat binaries are not supported at this level.");
    }
    buffer.order(magicInfo.isSwapped() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    processThinBinary(magicInfo, oldCompDir, updatedCompDir);
  }
}
