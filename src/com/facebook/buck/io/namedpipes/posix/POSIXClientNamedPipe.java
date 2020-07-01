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

import com.facebook.buck.io.namedpipes.BaseNamedPipe;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Named pipe implementation based on {@code RandomAccessFile}. */
class POSIXClientNamedPipe extends BaseNamedPipe {

  private RandomAccessFileWrapper readFile = null;
  private RandomAccessFileWrapper writeFile = null;

  public POSIXClientNamedPipe(Path path) {
    super(path);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    synchronized (this) {
      if (readFile == null) {
        readFile = new RandomAccessFileWrapper(getName(), "rw");
      }
    }
    return readFile.getInputStream();
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    synchronized (this) {
      if (writeFile == null) {
        writeFile = new RandomAccessFileWrapper(getName(), "rw");
      }
    }
    return writeFile.getOutputStream();
  }

  @Override
  public void close() throws IOException {
    List<IOException> exceptionList = new ArrayList<>();

    closeFileWrapper(readFile, exceptionList);
    closeFileWrapper(writeFile, exceptionList);

    if (!exceptionList.isEmpty()) {
      String errorMessage =
          String.format(
              "Exception during closing named pipe's read/write random access files. Named pipe: %s",
              getName());
      IOException ioException = new IOException(errorMessage);
      exceptionList.forEach(ioException::addSuppressed);
      throw ioException;
    }
  }

  private void closeFileWrapper(
      RandomAccessFileWrapper fileWrapper, List<IOException> exceptionList) {
    if (fileWrapper != null) {
      try {
        fileWrapper.close();
      } catch (IOException e) {
        exceptionList.add(e);
      }
    }
  }

  private static class RandomAccessFileWrapper implements Closeable {

    final RandomAccessFile randomAccessFile;

    RandomAccessFileWrapper(String path, String mode) throws IOException {
      this.randomAccessFile = new RandomAccessFile(path, mode);
    }

    InputStream getInputStream() {
      return Channels.newInputStream(randomAccessFile.getChannel());
    }

    OutputStream getOutputStream() {
      return Channels.newOutputStream(randomAccessFile.getChannel());
    }

    @Override
    public void close() throws IOException {
      randomAccessFile.close();
    }
  }
}
