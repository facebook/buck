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
import com.google.common.io.Closer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Named pipe reader implementation based on {@code RandomAccessFile}. */
public class POSIXClientNamedPipeReader extends POSIXClientNamedPipeBase
    implements NamedPipeReader {

  private final RandomAccessFileWrapper readFile;
  private final Closer closer = Closer.create();

  protected POSIXClientNamedPipeReader(Path path) throws IOException {
    super(path);
    closer.register(super::close);
    this.readFile = closer.register(new RandomAccessFileWrapper(getName(), "rw"));
  }

  @Override
  public InputStream getInputStream() {
    return readFile.getInputStream();
  }

  @Override
  public void close() throws IOException {
    closer.close();
  }
}
