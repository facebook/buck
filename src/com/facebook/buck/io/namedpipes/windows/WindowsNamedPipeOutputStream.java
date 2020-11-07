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

import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** {@link OutputStream} for writing to windows named pipes. */
public class WindowsNamedPipeOutputStream extends OutputStream {

  private static final WindowsNamedPipeLibrary API = WindowsNamedPipeLibrary.INSTANCE;

  private final WinNT.HANDLE namedPipeHandle;
  private final WinNT.HANDLE writerWaitable;
  private final String namedPipeName;

  protected WindowsNamedPipeOutputStream(
      WinNT.HANDLE namedPipeHandle, WinNT.HANDLE writerWaitable, String namedPipeName) {
    this.namedPipeHandle = namedPipeHandle;
    this.writerWaitable = writerWaitable;
    this.namedPipeName = namedPipeName;
  }

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

    boolean immediate = API.WriteFile(namedPipeHandle, data, len, null, olap.getPointer());
    if (!immediate) {
      int error = API.GetLastError();
      if (error != WinError.ERROR_IO_PENDING) {
        throw new IOException(
            String.format(
                "GetOverlappedResult() failed for write operation. Named pipe: %s, error code: %s",
                namedPipeName, error));
      }
    }
    IntByReference written = new IntByReference();
    if (!API.GetOverlappedResult(namedPipeHandle, olap.getPointer(), written, true)) {
      int error = API.GetLastError();
      throw new IOException(
          String.format(
              "GetOverlappedResult() failed for write operation. Named pipe: %s, error code: %s",
              namedPipeName, error));
    }
    if (written.getValue() != len) {
      throw new IOException(
          String.format(
              "WriteFile() wrote less bytes than requested. Named pipe: %s", namedPipeName));
    }
  }
}
