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

import static com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeLibrary.closeConnectedPipe;
import static com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeLibrary.createEvent;

import com.facebook.buck.io.namedpipes.BaseNamedPipe;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Implements a {@link com.facebook.buck.io.namedpipes.NamedPipe} backed by a windows named pipe
 * under the hood.
 *
 * <p>Ported from {@link com.facebook.nailgun.NGWin32NamedPipeServerSocket}
 */
class WindowsNamedPipeServer extends BaseNamedPipe {

  private static final WindowsNamedPipeLibrary API = WindowsNamedPipeLibrary.INSTANCE;

  private static final int KB_IN_BYTES = 1024;
  // Linux has 64K buffer, MacOS 16K.
  // Set buffer size to 32K (the number in the middle of Linux and MacOs sizes).
  private static final int BUFFER_SIZE = 32 * KB_IN_BYTES;

  private final LinkedBlockingQueue<HANDLE> openHandles;
  private final LinkedBlockingQueue<HANDLE> connectedHandles;
  private final Consumer<HANDLE> closeCallback;

  public WindowsNamedPipeServer(Path path) {
    super(path);
    this.openHandles = new LinkedBlockingQueue<>();
    this.connectedHandles = new LinkedBlockingQueue<>();
    this.closeCallback =
        handle -> {
          if (connectedHandles.remove(handle)) {
            closeConnectedPipe(handle, false);
          }
          if (openHandles.remove(handle)) {
            closeOpenPipe(handle);
          }
        };
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return connect().getInputStream();
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return connect().getOutputStream();
  }

  private WindowsNamedPipeClient connect() throws IOException {
    HANDLE handle =
        API.CreateNamedPipe(
            /* lpName */ getName(),
            /* dwOpenMode */ WinNT.PIPE_ACCESS_DUPLEX | WinNT.FILE_FLAG_OVERLAPPED,
            /* dwPipeMode */ WinBase.PIPE_REJECT_REMOTE_CLIENTS,
            /* nMaxInstances */ WinBase.PIPE_UNLIMITED_INSTANCES,
            /* nOutBufferSize */ BUFFER_SIZE,
            /* nInBufferSize */ BUFFER_SIZE,
            /* nDefaultTimeOut */ 0,
            /* lpSecurityAttributes */ null);
    if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
      throw new IOException(
          String.format(
              "Could not create named pipe: %s, error %s", getName(), API.GetLastError()));
    }
    openHandles.add(handle);

    WinBase.OVERLAPPED overlapped = createOverlapped();
    if (API.ConnectNamedPipe(handle, overlapped.getPointer())) {
      openHandles.remove(handle);
      connectedHandles.add(handle);
      return new WindowsNamedPipeClient(getPath(), handle, closeCallback);
    }

    int connectError = API.GetLastError();
    if (connectError == WinError.ERROR_PIPE_CONNECTED) {
      openHandles.remove(handle);
      connectedHandles.add(handle);
      return new WindowsNamedPipeClient(getPath(), handle, closeCallback);
    }

    if (connectError == WinError.ERROR_NO_DATA) {
      // Client has connected and disconnected between CreateNamedPipe() and ConnectNamedPipe()
      // connection is broken, but it is returned it avoid loop here.
      // Actual error will happen when it will try to read/write from/to pipe.
      return new WindowsNamedPipeClient(getPath(), handle, closeCallback);
    }

    if (connectError == WinError.ERROR_IO_PENDING) {
      if (!API.GetOverlappedResult(handle, overlapped.getPointer(), new IntByReference(), true)) {
        openHandles.remove(handle);
        closeOpenPipe(handle);
        throw new PipeNotConnectedException(
            String.format(
                "GetOverlappedResult() failed for connect operation. Named pipe: %s, error: %s, previous error: %s",
                getName(), API.GetLastError(), connectError));
      }

      openHandles.remove(handle);
      connectedHandles.add(handle);
      return new WindowsNamedPipeClient(getPath(), handle, closeCallback);
    }

    throw new IOException(
        String.format(
            "ConnectNamedPipe() failed. Named pipe: %s, error: %s", getName(), connectError));
  }

  private WinBase.OVERLAPPED createOverlapped() {
    WinBase.OVERLAPPED overlapped = new WinBase.OVERLAPPED();
    overlapped.hEvent = createEvent();
    overlapped.write();
    return overlapped;
  }

  @Override
  public void close() {
    List<HANDLE> handlesToClose = new ArrayList<>();
    openHandles.drainTo(handlesToClose);
    handlesToClose.forEach(this::closeOpenPipe);

    List<HANDLE> handlesToDisconnect = new ArrayList<>();
    connectedHandles.drainTo(handlesToDisconnect);
    handlesToDisconnect.forEach(handle -> closeConnectedPipe(handle, true));
  }

  private void closeOpenPipe(HANDLE handle) {
    API.CancelIoEx(handle, null);
    API.CloseHandle(handle);
  }
}
