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
import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.InputStream;

/** {@link InputStream} for reading from windows named pipes. */
class WindowsNamedPipeInputStream extends InputStream {

  private static final WindowsNamedPipeLibrary API = WindowsNamedPipeLibrary.INSTANCE;

  private final WindowsHandle namedPipeHandle;
  private final WindowsHandle readerWaitable;
  private final String namedPipeName;

  protected WindowsNamedPipeInputStream(
      WindowsHandle namedPipeHandle,
      String namedPipeName,
      WindowsHandleFactory windowsHandleFactory)
      throws IOException {
    this.namedPipeHandle = namedPipeHandle;
    this.readerWaitable = createEvent(namedPipeName, windowsHandleFactory);
    this.namedPipeName = namedPipeName;
  }

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
    if (namedPipeHandle.isClosed()) {
      return 0;
    }

    WinNT.HANDLE namedPipeRawHandle = namedPipeHandle.getHandle();
    Memory readBuffer = new Memory(len);
    WindowsOverlapped overlapped = new WindowsOverlapped(readerWaitable);
    boolean immediate =
        API.ReadFile(namedPipeRawHandle, readBuffer, len, null, overlapped.getPointer());
    if (!immediate) {
      int error = Kernel32.INSTANCE.GetLastError();
      if (isEndOfThePipe(error)) {
        return 0;
      }
      if (error != WinError.ERROR_IO_PENDING) {
        throw new WindowsNamedPipeException(
            "Cannot read from named pipe %s input steam. Error: %s",
            namedPipeName, Kernel32Util.formatMessageFromLastErrorCode(error));
      }
    }

    IntByReference r = new IntByReference();
    if (!API.GetOverlappedResult(namedPipeRawHandle, overlapped.getPointer(), r, true)) {
      int error = Kernel32.INSTANCE.GetLastError();
      if (isEndOfThePipe(error)) {
        return 0;
      }
      throw new WindowsNamedPipeException(
          "GetOverlappedResult() failed for read operation. Named pipe: %s, error: %s",
          namedPipeName, Kernel32Util.formatMessageFromLastErrorCode(error));
    }
    int actualLen = r.getValue();
    byte[] byteArray = readBuffer.getByteArray(0, actualLen);
    System.arraycopy(byteArray, 0, b, off, actualLen);
    return actualLen;
  }

  private boolean isEndOfThePipe(int error) {
    return error == WinError.ERROR_BROKEN_PIPE;
  }

  @Override
  public void close() {
    readerWaitable.close();
  }
}
