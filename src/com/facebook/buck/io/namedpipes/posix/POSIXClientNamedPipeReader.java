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

package com.facebook.buck.io.namedpipes.posix;

import com.facebook.buck.io.namedpipes.NamedPipeReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Named pipe reader implementation based on {@code RandomAccessFile}. */
class POSIXClientNamedPipeReader extends POSIXClientNamedPipeBase implements NamedPipeReader {

  private RandomAccessFileWrapper readFile = null;

  public POSIXClientNamedPipeReader(Path path) {
    super(path);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    synchronized (this) {
      if (readFile == null) {
        readFile = new POSIXClientNamedPipeBase.RandomAccessFileWrapper(getName(), "rw");
      }
    }
    return readFile.getInputStream();
  }

  @Override
  public void close() throws IOException {
    closeFileWrapper(readFile);
  }
}
