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

package com.facebook.buck.zip;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;

public class ZipScrubberStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path zip;

  public ZipScrubberStep(ProjectFilesystem filesystem, Path zip) {
    this.filesystem = filesystem;
    this.zip = zip;
  }

  @Override
  public String getShortName() {
    return "zip-scrub";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "zip-scrub " + zip;
  }

  private static void check(boolean expression, String msg) throws IOException {
    if (!expression) {
      throw new IOException(msg);
    }
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws InterruptedException {
    Path zipPath = filesystem.resolve(zip);
    try (FileChannel channel =
             FileChannel.open(zipPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
      map.order(ByteOrder.LITTLE_ENDIAN);

      // Search backwards from the end of the ZIP file, searching for the EOCD signature, which
      // designates the start of the EOCD.
      int eocdOffset = (int) channel.size() - ZipEntry.ENDHDR;
      while (map.getInt(eocdOffset) != ZipEntry.ENDSIG) {
        eocdOffset--;
      }

      int cdEntries = map.getShort(eocdOffset + ZipEntry.ENDTOT);
      int cdOffset = map.getInt(eocdOffset + ZipEntry.ENDOFF);

      for (int idx = 0; idx < cdEntries; idx++) {
        // Wrap the central directory header and zero out it's timestamp.
        ByteBuffer entry = slice(map, cdOffset);
        check(entry.getInt(0) == ZipEntry.CENSIG, "expected central directory header signature");

        entry.putInt(ZipEntry.CENTIM, ZipConstants.DOS_FAKE_TIME);
        scrubLocalEntry(slice(map, entry.getInt(ZipEntry.CENOFF)));

        cdOffset +=
            46 +
            entry.getShort(ZipEntry.CENNAM) +
            entry.getShort(ZipEntry.CENEXT) +
            entry.getShort(ZipEntry.CENCOM);
      }

    } catch (IOException e) {
      context.logError(e, "Error scrubbing non-deterministic metadata from %s", zipPath);
      return StepExecutionResult.ERROR;
    }
    return StepExecutionResult.SUCCESS;
  }

  private static ByteBuffer slice(ByteBuffer map, int offset) {
    ByteBuffer result = map.duplicate();
    result.position(offset);
    result = result.slice();
    result.order(ByteOrder.LITTLE_ENDIAN);
    return result;
  }

  private static void scrubLocalEntry(ByteBuffer entry) throws IOException {
    check(entry.getInt(0) == ZipEntry.LOCSIG, "expected local header signature");
    entry.putInt(ZipEntry.LOCTIM, ZipConstants.DOS_FAKE_TIME);
  }

}
