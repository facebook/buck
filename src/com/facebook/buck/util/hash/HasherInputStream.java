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

package com.facebook.buck.util.hash;

import com.google.common.hash.Hasher;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nonnull;

/**
 * An {@link InputStream} which appends the hash of the data read from it to a {@link Hasher}.
 * As opposed to {@link com.google.common.hash.HashingInputStream}, users can wrap an existing
 * {@link Hasher} which makes this more flexible when building more complex hashes.
 */
public class HasherInputStream extends FilterInputStream {

  private final Hasher hasher;

  public HasherInputStream(Hasher hasher, InputStream in) {
    super(in);
    this.hasher = hasher;
  }

  @Override
  public int read() throws IOException {
    int b = in.read();
    if (b != -1) {
      hasher.putByte((byte) b);
    }
    return b;
  }

  @Override
  public int read(@Nonnull byte[] bytes, int off, int len) throws IOException {
    int numOfBytesRead = in.read(bytes, off, len);
    if (numOfBytesRead != -1) {
      hasher.putBytes(bytes, off, numOfBytesRead);
    }
    return numOfBytesRead;
  }

  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public void mark(int readlimit) {}

  @Override
  public void reset() throws IOException {
    throw new IOException("reset not supported");
  }

}
