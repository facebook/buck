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
import com.facebook.buck.io.namedpipes.PipeNotConnectedException;
import com.google.common.io.Closer;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import javax.annotation.concurrent.NotThreadSafe;

/** Named pipe implementation based on {@code RandomAccessFile}. */
abstract class POSIXClientNamedPipeBase extends BaseNamedPipe {

  private boolean isClosed = false;

  public POSIXClientNamedPipeBase(Path path) {
    super(path);
  }

  @Override
  public void close() throws IOException {
    isClosed = true;
  }

  @Override
  public final boolean isClosed() {
    return isClosed;
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

    private final RandomAccessFile randomAccessFile;
    private final Closer closer = Closer.create();

    RandomAccessFileWrapper(String path, String mode) throws IOException {
      this.randomAccessFile = closer.register(new RandomAccessFile(path, mode));
    }

    InputStream getInputStream() {
      return closer.register(
          new POSIXNamedPipeInputStream(Channels.newInputStream(randomAccessFile.getChannel())));
    }

    OutputStream getOutputStream() {
      return closer.register(Channels.newOutputStream(randomAccessFile.getChannel()));
    }

    @Override
    public void close() throws IOException {
      closer.close();
    }
  }

  /**
   * Wrapper for an {@link InputStream} that replaces {@link ClosedChannelException} with {@link
   * PipeNotConnectedException}. This is to make the exceptions consistent with Windows.
   */
  private static class POSIXNamedPipeInputStream extends InputStream {

    private final InputStream inputStream;

    private POSIXNamedPipeInputStream(InputStream inputStream) {
      this.inputStream = inputStream;
    }

    @Override
    public int read() throws IOException {
      try {
        return inputStream.read();
      } catch (ClosedChannelException e) {
        throw new PipeNotConnectedException(e.getMessage());
      }
    }

    @Override
    public int read(byte[] b) throws IOException {
      try {
        return inputStream.read(b);
      } catch (ClosedChannelException e) {
        throw new PipeNotConnectedException(e.getMessage());
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      try {
        return inputStream.read(b, off, len);
      } catch (ClosedChannelException e) {
        throw new PipeNotConnectedException(e.getMessage());
      }
    }

    @Override
    public void close() throws IOException {
      inputStream.close();
    }
  }
}
