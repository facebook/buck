/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.io.windowspipe;

import com.facebook.buck.io.watchman.Transport;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Implements a {@link Transport} backed by a windows named pipe under the hood. */
public class WindowsNamedPipe implements Transport {
  /*
   * Implementation notice:
   * Blocking (or sync) win api for pipes doesn't allow to read and write at the same time from
   * different threads. This may lead to surprising deadlocks in java world.
   * So, this WindowsNamedPipe implements UNIX-style blocking IO (when reading and writing
   * at the same time is OK) atop of async (or overlapped) win api.
   * The main trick is to use `GetOverlappedResult` with wait=true.
   */
  private static final WindowsNamedPipeLibrary api = WindowsNamedPipeLibrary.INSTANCE;
  private final HANDLE pipeHandle;
  private final InputStream in;
  private final OutputStream out;
  // Reading and writing may happen in different threads in general.
  // So, each operation (read, write) has its own waitable object.
  private final HANDLE readerWaitable;
  private final HANDLE writerWaitable;

  /** Creates a Windows named pipe bound to a path */
  public static WindowsNamedPipe createPipeWithPath(String path) throws IOException {
    HANDLE pipeHandle =
        api.CreateFile(
            path,
            WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
            0,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_OVERLAPPED,
            null);
    if (WinNT.INVALID_HANDLE_VALUE.equals(pipeHandle)) {
      throw new IOException(
          "Failed to open a named pipe " + path + " error: " + api.GetLastError());
    }
    return new WindowsNamedPipe(pipeHandle, createEvent(), createEvent());
  }

  private WindowsNamedPipe(HANDLE pipeHandle, HANDLE readerWaitable, HANDLE writerWaitable) {
    this.pipeHandle = pipeHandle;
    this.readerWaitable = readerWaitable;
    this.writerWaitable = writerWaitable;
    this.in = new NamedPipeInputStream();
    this.out = new NamedPipeOutputStream();
  }

  @Override
  public void close() {
    api.CloseHandle(pipeHandle);
    api.CloseHandle(readerWaitable);
    api.CloseHandle(writerWaitable);
  }

  @Override
  public InputStream getInputStream() {
    return in;
  }

  @Override
  public OutputStream getOutputStream() {
    return out;
  }

  private class NamedPipeOutputStream extends OutputStream {
    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) (0xFF & b)});
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      Pointer lpOverlapped = createOverlapped(writerWaitable).getPointer();
      boolean immediate =
          api.WriteFile(pipeHandle, ByteBuffer.wrap(b, off, len), len, null, lpOverlapped);
      if (!immediate && api.GetLastError() != WinError.ERROR_IO_PENDING) {
        throw new IOException("WriteFile() failed. WinError: " + api.GetLastError());
      }
      IntByReference written = new IntByReference();
      // wait = true, blocked until data is written
      if (!api.GetOverlappedResult(pipeHandle, lpOverlapped, written, true)) {
        throw new IOException("GetOverlappedResult() failed for write operation");
      }
      if (written.getValue() != len) {
        throw new IOException("WriteFile() wrote less bytes than requested");
      }
    }
  }

  private class NamedPipeInputStream extends InputStream {
    @Override
    public int read() throws IOException {
      byte[] b = new byte[1];
      int read = read(b);
      return read == -1 ? -1 : 0xFF & b[0];
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      // Memory is used because the actual result is taken after the call to GetOverlappedResult.
      // ByteBuffer doesn't work here, since JNA ByteBuffer magic doesn't go beyond a single call.
      Memory readBuffer = new Memory(len);
      Pointer lpOverlapped = createOverlapped(readerWaitable).getPointer();
      boolean immediate = api.ReadFile(pipeHandle, readBuffer, len, null, lpOverlapped);
      if (!immediate && api.GetLastError() != WinError.ERROR_IO_PENDING) {
        throw new IOException("ReadFile() failed. WinError: " + api.GetLastError());
      }
      IntByReference read = new IntByReference();
      // wait = true, blocked until data is read
      if (!api.GetOverlappedResult(pipeHandle, lpOverlapped, read, true)) {
        throw new IOException("GetOverlappedResult() failed for read operation");
      }
      int readValue = read.getValue();
      if (readValue == 0) {
        return -1;
      }
      readBuffer.read(0, b, off, readValue);
      return readValue;
    }
  }

  private static HANDLE createEvent() throws IOException {
    HANDLE event = api.CreateEvent(null, true, false, null);
    if (event == null) {
      throw new IOException("CreateEvent() failed. Error: " + api.GetLastError());
    }
    return event;
  }

  private static WinBase.OVERLAPPED createOverlapped(WinNT.HANDLE event) {
    WinBase.OVERLAPPED olap = new WinBase.OVERLAPPED();
    olap.hEvent = event;
    olap.write();
    return olap;
  }
}
