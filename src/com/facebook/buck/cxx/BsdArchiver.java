/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class BsdArchiver implements Archiver {

  private static final byte[] EXPECTED_GLOBAL_HEADER = "!<arch>\n".getBytes(Charsets.US_ASCII);
  private static final byte[] LONG_NAME_MARKER = "#1/".getBytes(Charsets.US_ASCII);

  private static final FileScrubber SYMBOL_NAME_TABLE_PADDING_SCRUBBER = new FileScrubber() {
    @Override
    public void scrubFile(ByteBuffer file) throws ScrubException {

      // Grab the global header chunk and verify it's accurate.
      byte[] globalHeader = ObjectFileScrubbers.getBytes(file, EXPECTED_GLOBAL_HEADER.length);
      ObjectFileScrubbers.checkArchive(
          Arrays.equals(EXPECTED_GLOBAL_HEADER, globalHeader),
          "invalid global header");

      byte[] marker = ObjectFileScrubbers.getBytes(file, 3);
      ObjectFileScrubbers.checkArchive(
          Arrays.equals(LONG_NAME_MARKER, marker),
          "unexpected short symbol table name");

      int nameLength = ObjectFileScrubbers.getDecimalStringAsInt(file, 13);

      /* File modification timestamp */ ObjectFileScrubbers.getDecimalStringAsInt(file, 12);
      /* Owner ID */ ObjectFileScrubbers.getDecimalStringAsInt(file, 6);
      /* Group ID */ ObjectFileScrubbers.getDecimalStringAsInt(file, 6);

      /* File mode */ ObjectFileScrubbers.getOctalStringAsInt(file, 8);
      /* File size */ ObjectFileScrubbers.getDecimalStringAsInt(file, 10);

      // Lastly, grab the file magic entry and verify it's accurate.
      byte[] fileMagic = ObjectFileScrubbers.getBytes(file, 2);
      ObjectFileScrubbers.checkArchive(
          Arrays.equals(ObjectFileScrubbers.END_OF_FILE_HEADER_MARKER, fileMagic),
          "invalid file magic");

      // Skip the file name
      file.position(file.position() + nameLength);

      int descriptorsSize = ObjectFileScrubbers.getLittleEndian32BitLong(file);

      if (descriptorsSize > 0) {

        // Skip to the last descriptor if there is more than one
        if (descriptorsSize > 8) {
          file.position(file.position() + descriptorsSize - 8);
        }

        int lastSymbolNameOffset = ObjectFileScrubbers.getLittleEndian32BitLong(file);
        // Skip the corresponding object offset
        ObjectFileScrubbers.getLittleEndian32BitLong(file);

        int symbolNameTableSize = ObjectFileScrubbers.getLittleEndian32BitLong(file);
        int endOfSymbolNameTableOffset = file.position() + symbolNameTableSize;

        // Skip to the last symbol name
        file.position(file.position() + lastSymbolNameOffset);

        // Skip to the terminating null
        while (file.get() != 0x00) { // NOPMD
        }

        while (file.position() < endOfSymbolNameTableOffset) {
          file.put((byte) 0x00);
        }
      } else {
        int symbolNameTableSize = ObjectFileScrubbers.getLittleEndian32BitLong(file);
        ObjectFileScrubbers.checkArchive(
            symbolNameTableSize == 0,
            "archive has no symbol descriptors but has symbol names");
      }
    }
  };

  private final Tool tool;

  public BsdArchiver(Tool tool) {
    this.tool = tool;
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers() {
    return ImmutableList.of(
        ObjectFileScrubbers.createDateUidGidScrubber(EXPECTED_GLOBAL_HEADER),
        SYMBOL_NAME_TABLE_PADDING_SCRUBBER);
  }

  @Override
  public ImmutableSortedSet<SourcePath> getInputs() {
    return tool.getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }

}
