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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.log.Logger;
import com.google.common.io.ByteStreams;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStream} wrapper that fully reads the underlying stream on close. This is because
 * we need to fully read an InputStream for the corresponding connection to be reused.
 */
public class FullyReadOnCloseInputStream extends FilterInputStream {

  private static final Logger LOGGER = Logger.get(FullyReadOnCloseInputStream.class);
  private boolean closed = false;

  public FullyReadOnCloseInputStream(InputStream in) {
    super(in);
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      try {
        ByteStreams.copy(in, ByteStreams.nullOutputStream());
      } catch (IOException e) {
        LOGGER.info(e, "Exception when attempting to fully read the stream.");
      }
    }
    super.close();
  }
}
