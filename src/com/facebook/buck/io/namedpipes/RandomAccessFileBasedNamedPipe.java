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

package com.facebook.buck.io.namedpipes;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/** Named pipe implementation based on {@code RandomAccessFile}. */
public class RandomAccessFileBasedNamedPipe extends BaseNamedPipe {

  private final RandomAccessFile randomAccessFile;

  public RandomAccessFileBasedNamedPipe(Path path) throws IOException {
    super(path);
    this.randomAccessFile = new RandomAccessFile(getName(), "rw");
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return new FileInputStream(randomAccessFile.getFD());
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return new FileOutputStream(randomAccessFile.getFD());
  }

  @Override
  public void close() throws IOException {
    randomAccessFile.close();
  }
}
