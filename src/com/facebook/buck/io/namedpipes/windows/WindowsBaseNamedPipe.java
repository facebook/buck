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

package com.facebook.buck.io.namedpipes.windows;

import static com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeFactory.API;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.namedpipes.BaseNamedPipe;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Base class for Windows Named Pipe implementations. */
abstract class WindowsBaseNamedPipe extends BaseNamedPipe {

  private static final Logger LOG = Logger.get(WindowsBaseNamedPipe.class);

  private final WinNT.HANDLE handle;
  private final AtomicBoolean isClosed = new AtomicBoolean(false);

  WindowsBaseNamedPipe(Path path, WinNT.HANDLE handle) {
    super(path);
    this.handle = handle;
  }

  @Override
  public void close() throws IOException {
    boolean isAlreadyClosed = isClosed.getAndSet(true);
    if (isAlreadyClosed) {
      LOG.info("Skipping closing the named pipe: %s as it has already been closed.", getName());
      return;
    }

    if (!API.CloseHandle(handle)) {
      LOG.error(
          "CloseHandle() failed for named pipe: %s. Error code: %s", getName(), API.GetLastError());
    }
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return new NamedPipeInputStream();
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return new NamedPipeOutputStream();
  }

  protected void disconnect() {
    if (!API.DisconnectNamedPipe(handle)) {
      int error = API.GetLastError();
      if (isPipeClosedOrNotAvailable(error)) {
        LOG.info(
            "DisconnectNamedPipe() failed as named pipe: %s has already been closed.", getName());
        return;
      }

      LOG.error(
          "DisconnectNamedPipe() failed for named pipe: %s. Error code: %s",
          getName(), API.GetLastError());
    }
  }

  protected WinNT.HANDLE getHandle() {
    return handle;
  }

  /** Input stream from Named Pipe */
  private class NamedPipeInputStream extends InputStream {

    @Override
    public int read() throws IOException {
      byte[] b = new byte[1];
      int read = read(b);
      return read == -1 ? -1 : 0xFF & b[0];
    }

    @Override
    public int read(byte[] b) throws IOException {
      IntByReference lpNumberOfBytesRead = new IntByReference(0);
      if (!API.ReadFile(handle, b, b.length, lpNumberOfBytesRead, null)) {
        int error = API.GetLastError();
        if (isPipeClosedOrNotAvailable(error)) {
          return -1;
        }
        throw new IOException(
            String.format(
                "Cannot read from named pipe %s input steam. Error code: %s", getName(), error));
      }
      return lpNumberOfBytesRead.getValue();
    }

    @Override
    public void close() {
      disconnect();
    }
  }

  /** Output stream from Named Pipe */
  private class NamedPipeOutputStream extends OutputStream {

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) (0xFF & b)});
    }

    @Override
    public void write(byte[] b) throws IOException {
      IntByReference lpNumberOfBytesWritten = new IntByReference(0);
      if (!API.WriteFile(handle, b, b.length, lpNumberOfBytesWritten, null)) {
        int error = API.GetLastError();
        if (isPipeClosedOrNotAvailable(error)) {
          throw new PipeNotConnectedException(String.format("Pipe %s is not connected", getName()));
        }
        throw new IOException(
            String.format(
                "Cannot write into named pipe %s output steam. Error code: %s", getName(), error));
      }
    }

    @Override
    public void flush() throws IOException {
      Optional<String> errorMessage = flushAndGetErrorMessageIfOccurred();
      if (errorMessage.isPresent()) {
        throw new IOException(errorMessage.get());
      }
    }

    @Override
    public void close() {
      Optional<String> errorMessage = flushAndGetErrorMessageIfOccurred();
      // don't want to throw an exception during a close() method.
      errorMessage.ifPresent(LOG::error);
      disconnect();
    }

    /**
     * Flushes file buffer and returns error message wrapped with {@code Optional} if error
     * occurred.
     */
    private Optional<String> flushAndGetErrorMessageIfOccurred() {
      if (!API.FlushFileBuffers(handle)) {
        String errorMessage =
            String.format(
                "FlushFile() failed for named pipe: %s. Error code: %s",
                getName(), API.GetLastError());
        return Optional.of(errorMessage);
      }
      return Optional.empty();
    }
  }

  private boolean isPipeClosedOrNotAvailable(int error) {
    return error == WinError.ERROR_PIPE_NOT_CONNECTED
        || error == WinError.ERROR_INVALID_HANDLE
        || error == WinError.ERROR_INVALID_PARAMETER;
  }
}
