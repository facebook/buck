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

import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandle;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandleFactory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** {@link OutputStream} for writing to windows named pipes. */
public class WindowsNamedPipeOutputStream extends OutputStream {

  private static final WindowsNamedPipeLibrary API = WindowsNamedPipeLibrary.INSTANCE;

  private final WindowsHandle namedPipeHandle;
  private final WindowsHandle writerWaitable;
  private final String namedPipeName;

  protected WindowsNamedPipeOutputStream(
      WindowsHandle namedPipeHandle,
      String namedPipeName,
      WindowsHandleFactory windowsHandleFactory)
      throws IOException {
    this.namedPipeHandle = namedPipeHandle;
    this.writerWaitable = createEvent(namedPipeName, windowsHandleFactory);
    this.namedPipeName = namedPipeName;
  }

  @Override
  public void write(int b) throws IOException {
    write(new byte[] {(byte) (0xFF & b)});
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (namedPipeHandle.isClosed()) {
      return;
    }

    WinNT.HANDLE namedPipeRawHandle = namedPipeHandle.getHandle();
    ByteBuffer data = ByteBuffer.wrap(b, off, len);
    WindowsOverlapped overlapped = new WindowsOverlapped(writerWaitable);
    boolean immediate = API.WriteFile(namedPipeRawHandle, data, len, null, overlapped.getPointer());
    if (!immediate) {
      int error = Kernel32.INSTANCE.GetLastError();
      if (isEndOfThePipe(error)) {
        return;
      }
      if (error != WinError.ERROR_IO_PENDING) {
        throw new IOException(
            String.format(
                "Cannot write into named pipe %s output steam. Error: %s",
                namedPipeName, Kernel32Util.formatMessageFromLastErrorCode(error)));
      }
    }
    IntByReference written = new IntByReference();
    if (!API.GetOverlappedResult(namedPipeRawHandle, overlapped.getPointer(), written, true)) {
      int error = Kernel32.INSTANCE.GetLastError();
      if (isEndOfThePipe(error)) {
        return;
      }
      throw new IOException(
          String.format(
              "GetOverlappedResult() failed for write operation. Named pipe: %s, error: %s",
              namedPipeName, Kernel32Util.formatMessageFromLastErrorCode(error)));
    }
    if (written.getValue() != len) {
      throw new IOException(
          String.format(
              "WriteFile() wrote less bytes than requested. Named pipe: %s", namedPipeName));
    }
  }

  private boolean isEndOfThePipe(int error) {
    return error == WinError.ERROR_BROKEN_PIPE;
  }

  @Override
  public void close() {
    writerWaitable.close();
  }
}
