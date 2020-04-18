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

import static com.sun.jna.platform.win32.Kernel32.INSTANCE;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.namedpipes.BaseNamedPipe;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Windows name pipe that operates over passed {@code WinNT.HANDLE} and removes a physical file
 * corresponding to the named pipe.
 *
 * <p>https://docs.microsoft.com/en-us/dotnet/standard/io/how-to-use-named-pipes-for-network-interprocess-communication
 */
class WindowsNamedPipe extends BaseNamedPipe {

  private static final Logger LOG = Logger.get(WindowsNamedPipe.class);

  private final WinNT.HANDLE namedPipeHandler;

  WindowsNamedPipe(Path path, WinNT.HANDLE namedPipeHandler) {
    super(path);
    this.namedPipeHandler = namedPipeHandler;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    awaitConnectedClients();
    return new NamedPipeInputStream(namedPipeHandler, getName());
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    awaitConnectedClients();
    return new NamedPipeOutputStream(namedPipeHandler, getName());
  }

  private void awaitConnectedClients() throws IOException {
    if (!INSTANCE.ConnectNamedPipe(namedPipeHandler, null)) {
      int lastError = INSTANCE.GetLastError();
      if (lastError != WinError.ERROR_PIPE_CONNECTED) {
        throw new IOException(
            String.format("ConnectNamedPipe() failed. Error code: %s", lastError));
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (!INSTANCE.CloseHandle(namedPipeHandler)) {
      LOG.error(
          "CloseHandle() failed for named pipe: %s. Error code: %s",
          getName(), INSTANCE.GetLastError());
    }
    Files.deleteIfExists(getPath());
  }

  private static class NamedPipeOutputStream extends OutputStream {

    private final WinNT.HANDLE pipeHandler;
    private final String namedPipeName;

    private NamedPipeOutputStream(WinNT.HANDLE pipeHandler, String namedPipe) {
      this.pipeHandler = pipeHandler;
      this.namedPipeName = namedPipe;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) (0xFF & b)});
    }

    @Override
    public void write(byte[] b) throws IOException {
      IntByReference lpNumberOfBytesWritten = new IntByReference(0);
      if (!INSTANCE.WriteFile(pipeHandler, b, b.length, lpNumberOfBytesWritten, null)) {
        throw new IOException(
            String.format("Can't write into a steam. Error code: %s", INSTANCE.GetLastError()));
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
      disconnect(pipeHandler, namedPipeName);
    }

    /**
     * Flushes file buffer and returns error message wrapped with {@code Optional} if error
     * occurred.
     */
    private Optional<String> flushAndGetErrorMessageIfOccurred() {
      if (!INSTANCE.FlushFileBuffers(pipeHandler)) {
        String errorMessage =
            String.format(
                "FlushFile() failed for named pipe: %s. Error code: %s",
                namedPipeName, INSTANCE.GetLastError());
        return Optional.of(errorMessage);
      }
      return Optional.empty();
    }
  }

  private static class NamedPipeInputStream extends InputStream {

    private final WinNT.HANDLE pipeHandler;
    private final String namedPipeName;

    private NamedPipeInputStream(WinNT.HANDLE pipeHandler, String namedPipeName) {
      this.pipeHandler = pipeHandler;
      this.namedPipeName = namedPipeName;
    }

    @Override
    public int read() throws IOException {
      byte[] b = new byte[1];
      int read = read(b);
      return read == -1 ? -1 : 0xFF & b[0];
    }

    @Override
    public int read(byte[] b) throws IOException {
      IntByReference lpNumberOfBytesRead = new IntByReference(0);
      if (!INSTANCE.ReadFile(pipeHandler, b, b.length, lpNumberOfBytesRead, null)) {
        int lastError = INSTANCE.GetLastError();
        if (lastError == WinError.ERROR_PIPE_NOT_CONNECTED) {
          return -1;
        }
        throw new IOException(String.format("Can't read from a steam. Error code: %s", lastError));
      }
      return lpNumberOfBytesRead.getValue();
    }

    @Override
    public void close() {
      disconnect(pipeHandler, namedPipeName);
    }
  }

  private static void disconnect(WinNT.HANDLE pipeHandler, String namedPipeName) {
    if (!INSTANCE.DisconnectNamedPipe(pipeHandler)) {
      LOG.error(
          "DisconnectNamedPipe() failed for named pipe: %s. Error code: %s",
          namedPipeName, INSTANCE.GetLastError());
    }
  }
}
