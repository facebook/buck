/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.logd.server;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.Writer;

/**
 * This class encapsulates all writers LogdServer might use to write to file-system and/or storage
 * into one.
 */
public class LogdMultiWriter extends Writer {
  private final ImmutableList<Writer> writers;

  /** Constructor for LogdMultiWriter */
  public LogdMultiWriter(ImmutableList<Writer> writers) {
    this.writers = writers;
  }

  @Override
  public void write(char[] cbuf, int off, int len) throws IOException {
    for (Writer writer : writers) {
      writer.write(cbuf, off, len);
    }
  }

  @Override
  public void flush() throws IOException {
    for (Writer writer : writers) {
      writer.flush();
    }
  }

  @Override
  public void close() throws IOException {
    for (Writer writer : writers) {
      writer.close();
    }
  }
}
