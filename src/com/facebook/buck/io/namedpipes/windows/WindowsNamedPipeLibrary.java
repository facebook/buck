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

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;
import java.nio.ByteBuffer;

/**
 * Utility class to bridge native Windows named pipe calls to Java using JNA. *
 *
 * <p>Ported from {@link com.facebook.nailgun.NGWin32NamedPipeLibrary}
 */
interface WindowsNamedPipeLibrary extends WinNT, Library {

  int CLOSE_WAIT_IN_MILLIS = 500;

  WindowsNamedPipeLibrary INSTANCE =
      Native.loadLibrary("kernel32", WindowsNamedPipeLibrary.class, W32APIOptions.UNICODE_OPTIONS);

  HANDLE CreateNamedPipe(
      String lpName,
      int dwOpenMode,
      int dwPipeMode,
      int nMaxInstances,
      int nOutBufferSize,
      int nInBufferSize,
      int nDefaultTimeOut,
      SECURITY_ATTRIBUTES lpSecurityAttributes);

  boolean ConnectNamedPipe(HANDLE hNamedPipe, Pointer lpOverlapped);

  boolean DisconnectNamedPipe(HANDLE hObject);

  boolean ReadFile(
      HANDLE hFile,
      Memory lpBuffer,
      int nNumberOfBytesToRead,
      IntByReference lpNumberOfBytesRead,
      Pointer lpOverlapped);

  boolean WriteFile(
      HANDLE hFile,
      ByteBuffer lpBuffer,
      int nNumberOfBytesToWrite,
      IntByReference lpNumberOfBytesWritten,
      Pointer lpOverlapped);

  boolean CloseHandle(HANDLE hObject);

  boolean GetOverlappedResult(
      HANDLE hFile, Pointer lpOverlapped, IntByReference lpNumberOfBytesTransferred, boolean wait);

  boolean CancelIoEx(HANDLE hObject, Pointer lpOverlapped);

  HANDLE CreateEvent(
      SECURITY_ATTRIBUTES lpEventAttributes,
      boolean bManualReset,
      boolean bInitialState,
      String lpName);

  int WaitForSingleObject(HANDLE hHandle, int dwMilliseconds);

  int GetLastError();

  static void closeConnectedPipe(HANDLE handle, boolean shutdown) {
    if (!shutdown) {
      INSTANCE.WaitForSingleObject(handle, CLOSE_WAIT_IN_MILLIS);
    }
    INSTANCE.DisconnectNamedPipe(handle);
    INSTANCE.CloseHandle(handle);
  }

  static HANDLE createEvent() {
    return INSTANCE.CreateEvent(null, true, false, null);
  }
}
