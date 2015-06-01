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

package com.facebook.buck.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wraps a source {@link InputStream}, writing to a destination {@link OutputStream}
 * any bytes read from this object.
 *
 * Does not close either the InputStream or the OutputStream automatically.
 */
public class TeeInputStream extends FilterInputStream {
  private final OutputStream outputStream;

  public TeeInputStream(InputStream inputStream, OutputStream outputStream) {
    super(inputStream);
    this.outputStream = outputStream;
  }

  @Override
  public int read() throws IOException {
    // We don't call super.read() here because it's not documented which
    // other variant of read() it calls.
    int val = in.read();
    if (val != -1) {
      outputStream.write(val);
    }
    return val;
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    // Ditto on not using super.
    int numBytesRead = in.read(buffer);
    if (numBytesRead > 0) {
      outputStream.write(buffer, 0, numBytesRead);
    }
    return numBytesRead;
  }

  @Override
  public int read(byte[] buffer, int startIndex, int numBytesToRead) throws IOException {
    // Ditto on not using super.
    int numBytesRead = in.read(buffer, startIndex, numBytesToRead);
    if (numBytesRead > 0) {
      outputStream.write(buffer, startIndex, numBytesRead);
    }
    return numBytesRead;
  }
}
