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

package com.facebook.buck.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Specialization of {@link ByteArrayOutputStream} which knows if it's been closed.
 */
public class FakeOutputStream extends ByteArrayOutputStream {
  private boolean isClosed = false;
  private int lastFlushSize = 0;

  @Override
  public void close() throws IOException {
    super.close();
    isClosed = true;
  }

  @Override
  public void flush() throws IOException {
    super.flush();
    lastFlushSize = size();
  }

  public boolean isClosed() {
    return isClosed;
  }

  public int getLastFlushSize() {
    return lastFlushSize;
  }
}
