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
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.concurrent.NotThreadSafe;

/** Named pipe implementation based on {@code RandomAccessFile}. */
abstract class POSIXClientNamedPipeBase extends BaseNamedPipe {

  public POSIXClientNamedPipeBase(Path path) {
    super(path);
  }

  protected void closeFileWrapper(RandomAccessFileWrapper fileWrapper) throws IOException {
    if (fileWrapper != null) {
      fileWrapper.close();
    }
  }

  /**
   * Wrapper class around an instance of {@link RandomAccessFile}. Multiple input and output streams
   * may be open to the underlying {@link RandomAccessFile} concurrently.
   *
   * <p>Note that after calling {@link #close()}, the streams already obtained may throw {@link
   * ClosedChannelException} upon further operations (e.g. reads or writes). Users are expected to
   * to synchronize between {@link #close()} and further operations.
   *
   * <p>Also note that closing an associated {@link OutputStream} does not write an end of file
   * token in the {@link RandomAccessFile}. Users should not rely on end of file tokens when using
   * {@link RandomAccessFileWrapper}.
   */
  @NotThreadSafe
  protected static class RandomAccessFileWrapper implements Closeable {

    final RandomAccessFile randomAccessFile;
    private final Collection<Closeable> closeables;

    RandomAccessFileWrapper(String path, String mode) throws IOException {
      this.randomAccessFile = new RandomAccessFile(path, mode);
      closeables = ConcurrentHashMap.newKeySet();
    }

    InputStream getInputStream() {
      InputStream inputStream = Channels.newInputStream(randomAccessFile.getChannel());
      closeables.add(inputStream);
      return inputStream;
    }

    OutputStream getOutputStream() {
      OutputStream outputStream = Channels.newOutputStream(randomAccessFile.getChannel());
      closeables.add(outputStream);
      return outputStream;
    }

    @Override
    public void close() throws IOException {
      for (Closeable closeable : closeables) {
        closeable.close();
      }
      randomAccessFile.close();
    }
  }
}
