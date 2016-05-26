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
package com.facebook.buck.util;

import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MoreStreams {
  private static final Logger LOG = Logger.get(MoreStreams.class);

  private static final int READ_BUFFER_SIZE_BYTES = 8 * 1024;

  private MoreStreams() {
    // Not to be instantiated.
  }

  public static void copyExactly(InputStream source, OutputStream destination, long bytesToRead)
      throws IOException {
    Preconditions.checkNotNull(source);
    Preconditions.checkNotNull(destination);
    byte[] buffer = new byte[READ_BUFFER_SIZE_BYTES];

    long totalReadByteCount = 0;
    while (totalReadByteCount < bytesToRead) {
      int maxBytesToReadNext = (int) Math.min(bytesToRead - totalReadByteCount, buffer.length);
      int lastReadByteCount = source.read(buffer, 0, maxBytesToReadNext);

      if (lastReadByteCount == -1) {
        String msg = String.format(
            "InputStream was missing [%d] bytes. Expected to read a total of [%d] bytes.",
            bytesToRead - totalReadByteCount,
            bytesToRead);
        LOG.error(msg);
        throw new IOException(msg);
      }

      destination.write(buffer, 0, lastReadByteCount);
      totalReadByteCount += lastReadByteCount;
    }
  }
}
