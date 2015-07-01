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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;

public class ZipScrubberStep implements Step {

  private final Path zip;

  public ZipScrubberStep(Path zip) {
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

  private void check(boolean expression, String msg) throws IOException {
    if (!expression) {
      throw new IOException(msg);
    }
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    Path zipPath = context.getProjectFilesystem().resolve(zip);
    try (FileChannel channel =
             FileChannel.open(zipPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
      map.order(ByteOrder.LITTLE_ENDIAN);

      // Search backwards from the end of the ZIP file, searching for the EOCD signature, which
      // designates the start of the EOCD.
      map.position((int) channel.size() - ZipEntry.ENDCOM);
      while (map.getInt() != ZipEntry.ENDSIG) {

        // If we didn't find the magic header, back out the 4 bytes we just consumed for the `int`
        // and an additional byte to continue the search going backwards.
        map.position(map.position() - Integer.SIZE / Byte.SIZE - 1);
      }

      // Skip over unneeded info, grabbing just the number of central directory entries and
      // it's starting location.
      map.getShort();  // Number of this disk.
      map.getShort();  // Disk where central directory starts.
      map.getShort();  // Number of central directory records on this disk.
      int cdEntries = map.getShort();  // Total number of central directory records;
      map.getInt();  // Size of central directory (bytes).
      int cdOffset = map.getInt();  // Offset of start of central directory.

      // Position ourselves at the beginning of the central directory headers.  We'll iterate over
      // these, sanitizing their mtimes (and the mtimes of their corresponding local headers).
      map.position(cdOffset);
      for (int idx = 0; idx < cdEntries; idx++) {

        // Wrap the central directory header and zero out it's timestamp.
        ByteBuffer entry = map.slice();
        entry.order(ByteOrder.LITTLE_ENDIAN);
        check(entry.getInt() == ZipEntry.CENSIG, "expected central directory header signature");
        entry.putInt(ZipEntry.CENTIM, ZipConstants.DOS_EPOCH_START);

        // Find the local file header and zero it's timestamp out.
        int locOff = entry.getInt(ZipEntry.CENOFF);
        check(map.getInt(locOff) == ZipEntry.LOCSIG, "expected local header signature");
        map.putInt(locOff + ZipEntry.LOCTIM, ZipConstants.DOS_EPOCH_START);

        // Advance to the next entry.
        map.position(map.position() + 46);
        map.position(map.position() + entry.getShort(ZipEntry.CENNAM));
        map.position(map.position() + entry.getShort(ZipEntry.CENEXT));
        map.position(map.position() + entry.getShort(ZipEntry.CENCOM));
      }

    } catch (IOException e) {
      context.logError(e, "Error scrubbing non-deterministic metadata from %s", zipPath);
      return 1;
    }
    return 0;
  }

}
