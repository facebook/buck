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
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Windows name pipe that operates over passed named pipe path. Named pipe should have already been
 * created.
 *
 * <p>Ported from {@link com.facebook.nailgun.NGWin32NamedPipeSocket}.
 */
abstract class WindowsNamedPipeClientBase extends BaseNamedPipe {

  private static final WindowsNamedPipeLibrary API = WindowsNamedPipeLibrary.INSTANCE;

  /**
   * Handle to the named pipe that this client communicates to. Either the handle from the server's
   * {@link WindowsNamedPipeLibrary#CreateNamedPipe(String, int, int, int, int, int, int,
   * WinBase.SECURITY_ATTRIBUTES)} or the handle from the client's Kernel32#CreateFile.
   */
  private final HANDLE handle;

  private final HANDLE writerWaitable;
  private final Consumer<HANDLE> closeCallback;

  public WindowsNamedPipeClientBase(Path path, HANDLE handle, Consumer<HANDLE> closeCallback)
      throws IOException {
    super(path);
    this.handle = handle;
    this.closeCallback = closeCallback;

    this.writerWaitable = createEvent();
    if (writerWaitable == null) {
      throw new IOException(String.format("CreateEvent() failed. For named pipe: %s", getName()));
    }
  }

  @Override
  public void close() throws IOException {
    closeCallback.accept(handle);
  }

  protected HANDLE getNamedPipeHandle() {
    return handle;
  }

  /** Output stream from Named Pipe */
  protected class NamedPipeOutputStream extends OutputStream {

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
