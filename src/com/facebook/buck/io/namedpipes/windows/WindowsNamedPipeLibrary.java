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

import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandle;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandleFactory;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Utility class to bridge native Windows named pipe calls to Java using JNA. *
 *
 * <p>Ported from {@link com.facebook.nailgun.NGWin32NamedPipeLibrary}
 */
public interface WindowsNamedPipeLibrary extends WinNT, Library {

  int CLOSE_WAIT_IN_MILLIS = 500;
  int WAIT_FOR_HANDLER_TIMEOUT_MILLIS = 5_000;

  WindowsNamedPipeLibrary INSTANCE =
      Native.load("kernel32", WindowsNamedPipeLibrary.class, W32APIOptions.UNICODE_OPTIONS);

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

  boolean GetOverlappedResult(
      HANDLE hFile, Pointer lpOverlapped, IntByReference lpNumberOfBytesTransferred, boolean wait);

  boolean CancelIoEx(HANDLE hObject, Pointer lpOverlapped);

  HANDLE CreateEvent(
      SECURITY_ATTRIBUTES lpEventAttributes,
      boolean bManualReset,
      boolean bInitialState,
      String lpName);

  int WaitForSingleObject(HANDLE hHandle, int dwMilliseconds);

  boolean FlushFileBuffers(HANDLE hFile);

  /**
   * Invokes {@link #disconnectAndCloseHandle}. If {@code shutdown} is {@code false} invokes {@link
   * #WaitForSingleObject} and only then dispatches to {@link #disconnectAndCloseHandle}.
   */
  static void closeConnectedPipe(WindowsHandle windowsHandle, boolean shutdown) {
    windowsHandle
        .getOptionalHandle()
        .ifPresent(
            handle -> {
              if (!shutdown) {
                INSTANCE.WaitForSingleObject(handle, CLOSE_WAIT_IN_MILLIS);
              }
              disconnectAndCloseHandle(windowsHandle);
            });
  }

  /** Invokes {@link #DisconnectNamedPipe} and then {@link WindowsHandle#close()}. */
  static void disconnectAndCloseHandle(WindowsHandle windowsHandle) {
    if (windowsHandle.isClosed()) {
      return;
    }
    INSTANCE.DisconnectNamedPipe(windowsHandle.getHandle());
    windowsHandle.close();
  }

  /** Creates {@link WindowsHandle} that wraps the result of {@link #CreateEvent} result. */
  static WindowsHandle createEvent(String description, WindowsHandleFactory windowsHandleFactory)
      throws IOException {
    HANDLE handle = INSTANCE.CreateEvent(null, true, false, null);
    if (handle == null) {
      throw new WindowsNamedPipeException(
          "CreateEvent() failed, error: %s", Kernel32Util.getLastErrorMessage());
    }

    return windowsHandleFactory.create(handle, "CreateEvent() for " + description);
  }
}
