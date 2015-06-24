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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class BsdArchiver implements Archiver {

  private static final byte[] EXPECTED_GLOBAL_HEADER = "!<arch>\n".getBytes(Charsets.US_ASCII);
  private static final byte[] LONG_NAME_MARKER = "#1/".getBytes(Charsets.US_ASCII);

  private static final ArchiveScrubber SYMBOL_NAME_TABLE_PADDING_SCRUBBER = new ArchiveScrubber() {
    @Override
    public void scrubArchive(ByteBuffer archive) throws ScrubException {

      // Grab the global header chunk and verify it's accurate.
      byte[] globalHeader = ArchiveScrubbers.getBytes(archive, EXPECTED_GLOBAL_HEADER.length);
      ArchiveScrubbers.checkArchive(
          Arrays.equals(EXPECTED_GLOBAL_HEADER, globalHeader),
          "invalid global header");

      byte[] marker = ArchiveScrubbers.getBytes(archive, 3);
      ArchiveScrubbers.checkArchive(
          Arrays.equals(LONG_NAME_MARKER, marker),
          "unexpected short symbol table name");

      int nameLength = ArchiveScrubbers.getDecimalStringAsInt(archive, 13);

      /* File modification timestamp */ ArchiveScrubbers.getDecimalStringAsInt(archive, 12);
      /* Owner ID */ ArchiveScrubbers.getDecimalStringAsInt(archive, 6);
      /* Group ID */ ArchiveScrubbers.getDecimalStringAsInt(archive, 6);

      /* File mode */ ArchiveScrubbers.getOctalStringAsInt(archive, 8);
      /* File size */ ArchiveScrubbers.getDecimalStringAsInt(archive, 10);

      // Lastly, grab the file magic entry and verify it's accurate.
      byte[] fileMagic = ArchiveScrubbers.getBytes(archive, 2);
      ArchiveScrubbers.checkArchive(
          Arrays.equals(ArchiveScrubbers.END_OF_FILE_HEADER_MARKER, fileMagic),
          "invalid file magic");

      // Skip the file name
      archive.position(archive.position() + nameLength);

      int descriptorsSize = ArchiveScrubbers.getLittleEndian32BitLong(archive);

      if (descriptorsSize > 0) {

        // Skip to the last descriptor if there is more than one
        if (descriptorsSize > 8) {
          archive.position(archive.position() + descriptorsSize - 8);
        }

        int lastSymbolNameOffset = ArchiveScrubbers.getLittleEndian32BitLong(archive);
        // Skip the corresponding object offset
        ArchiveScrubbers.getLittleEndian32BitLong(archive);

        int symbolNameTableSize = ArchiveScrubbers.getLittleEndian32BitLong(archive);
        int endOfSymbolNameTableOffset = archive.position() + symbolNameTableSize;

        // Skip to the last symbol name
        archive.position(archive.position() + lastSymbolNameOffset);

        // Skip to the terminating null
        while (archive.get() != 0x00) { // NOPMD
        }

        while (archive.position() < endOfSymbolNameTableOffset) {
          archive.put((byte) 0x00);
        }
      } else {
        int symbolNameTableSize = ArchiveScrubbers.getLittleEndian32BitLong(archive);
        ArchiveScrubbers.checkArchive(
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
  public ImmutableList<ArchiveScrubber> getScrubbers() {
    return ImmutableList.of(
        ArchiveScrubbers.createDateUidGidScrubber(EXPECTED_GLOBAL_HEADER),
        SYMBOL_NAME_TABLE_PADDING_SCRUBBER);
  }

  @Override
  public ImmutableList<BuildRule> getBuildRules(SourcePathResolver resolver) {
    return tool.getBuildRules(resolver);
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
