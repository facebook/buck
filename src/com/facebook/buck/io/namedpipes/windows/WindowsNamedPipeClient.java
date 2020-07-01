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

import static com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeLibrary.createEvent;

import com.facebook.buck.io.namedpipes.BaseNamedPipe;
import com.sun.jna.Memory;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Windows name pipe that operates over passed named pipe path. Named pipe should have already been
 * created.
 *
 * <p>Ported from {@link com.facebook.nailgun.NGWin32NamedPipeSocket}
 */
class WindowsNamedPipeClient extends BaseNamedPipe {

  private static final WindowsNamedPipeLibrary API = WindowsNamedPipeLibrary.INSTANCE;

  private final HANDLE handle;
  private final Consumer<HANDLE> closeCallback;
  private final InputStream is;
  private final OutputStream os;
  private final HANDLE readerWaitable;
  private final HANDLE writerWaitable;

  public WindowsNamedPipeClient(Path path, HANDLE handle, Consumer<HANDLE> closeCallback)
      throws IOException {
    super(path);
    this.handle = handle;
    this.closeCallback = closeCallback;

    this.readerWaitable = createEvent();
    if (readerWaitable == null) {
      throw new IOException(String.format("CreateEvent() failed. For named pipe: %s", getName()));
    }

    this.writerWaitable = createEvent();
    if (writerWaitable == null) {
      throw new IOException(String.format("CreateEvent() failed. For named pipe: %s", getName()));
    }

    this.is = new NamedPipeInputStream();
    this.os = new NamedPipeOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return is;
  }

  @Override
  public OutputStream getOutputStream() {
    return os;
  }

  @Override
  public void close() throws IOException {
    closeCallback.accept(handle);
  }

  private class NamedPipeInputStream extends InputStream {

    @Override
    public int read() throws IOException {
      int result;
      byte[] b = new byte[1];
      if (read(b) == 0) {
        result = -1;
      } else {
        result = 0xFF & b[0];
      }
      return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      Memory readBuffer = new Memory(len);

      WinBase.OVERLAPPED olap = new WinBase.OVERLAPPED();
      olap.hEvent = readerWaitable;
      olap.write();

      boolean immediate = API.ReadFile(handle, readBuffer, len, null, olap.getPointer());
      if (!immediate) {
        int error = API.GetLastError();
        if (error != WinError.ERROR_IO_PENDING) {
          throw new IOException(
              String.format(
                  "Cannot read from named pipe %s input steam. Error code: %s", getName(), error));
        }
      }

      IntByReference r = new IntByReference();
      if (!API.GetOverlappedResult(handle, olap.getPointer(), r, true)) {
        int error = API.GetLastError();
        throw new IOException(
            String.format(
                "GetOverlappedResult() failed for read operation. Named pipe: %s, error code: %s",
                getName(), error));
      }
      int actualLen = r.getValue();
      byte[] byteArray = readBuffer.getByteArray(0, actualLen);
      System.arraycopy(byteArray, 0, b, off, actualLen);
      return actualLen;
    }
  }

  private class NamedPipeOutputStream extends OutputStream {

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) (0xFF & b)});
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      ByteBuffer data = ByteBuffer.wrap(b, off, len);

      WinBase.OVERLAPPED olap = new WinBase.OVERLAPPED();
      olap.hEvent = writerWaitable;
      olap.write();

      boolean immediate = API.WriteFile(handle, data, len, null, olap.getPointer());
      if (!immediate) {
        int error = API.GetLastError();
        if (error != WinError.ERROR_IO_PENDING) {
          throw new IOException(
              String.format(
                  "GetOverlappedResult() failed for write operation. Named pipe: %s, error code: %s",
                  getName(), error));
        }
      }
      IntByReference written = new IntByReference();
      if (!API.GetOverlappedResult(handle, olap.getPointer(), written, true)) {
        int error = API.GetLastError();
        throw new IOException(
            String.format(
                "GetOverlappedResult() failed for write operation. Named pipe: %s, error code: %s",
                getName(), error));
      }
      if (written.getValue() != len) {
        throw new IOException(
            String.format(
                "WriteFile() wrote less bytes than requested. Named pipe: %s", getName()));
      }
    }
  }
}
