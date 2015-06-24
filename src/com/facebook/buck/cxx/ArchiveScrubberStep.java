/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Scrub any non-deterministic meta-data from the given archive (e.g. timestamp, UID, GID).
 */
public class ArchiveScrubberStep implements Step {

  private final Path archive;
  private final ImmutableList<ArchiveScrubber> scrubbers;

  public ArchiveScrubberStep(
      Path archive,
      ImmutableList<ArchiveScrubber> scrubbers) {
    this.archive = archive;
    this.scrubbers = scrubbers;
  }

  private FileChannel readWriteChannel(Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    Path archivePath = context.getProjectFilesystem().resolve(archive);
    try {
      for (ArchiveScrubber scrubber : scrubbers) {
        try (FileChannel channel = readWriteChannel(archivePath)) {
          MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
          scrubber.scrubArchive(map);
        }
      }
    } catch (IOException | ArchiveScrubber.ScrubException e) {
      context.logError(e, "Error scrubbing non-deterministic metadata from %s", archivePath);
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "archive-scrub";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "archive-scrub";
  }

}
