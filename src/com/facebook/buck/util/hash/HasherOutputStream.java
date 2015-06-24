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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.annotation.Nonnull;

/**
 * An {@link OutputStream} which appends the hash of the data written to it to a {@link Hasher}.
 * As opposed to {@link com.google.common.hash.HashingOutputStream}, users can wrap an existing
 * {@link Hasher} which makes this more flexible when building more complex hashes.
 */
public class HasherOutputStream extends FilterOutputStream {

  private final Hasher hasher;

  public HasherOutputStream(Hasher hasher, OutputStream out) {
    super(out);
    this.hasher = hasher;
  }

  @Override
  public void write(int b) throws IOException {
    hasher.putByte((byte) b);
    out.write(b);
  }

  @Override
  public void write(@Nonnull byte[] bytes, int off, int len) throws IOException {
    hasher.putBytes(bytes, off, len);
    out.write(bytes, off, len);
  }

  @Override
  public void close() throws IOException {
    out.close();
  }

}
