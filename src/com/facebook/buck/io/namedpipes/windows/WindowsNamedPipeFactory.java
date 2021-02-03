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

import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.io.namedpipes.windows.handle.DefaultWindowsHandleFactory;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandle;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandleFactory;
import com.google.common.annotations.VisibleForTesting;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.Consumer;

/** Windows named pipe factory. (Singleton With Enum Implementation). */
public enum WindowsNamedPipeFactory implements NamedPipeFactory {
  INSTANCE;

  private static final String WINDOWS_PATH_DELIMITER = "\\";

  private static final int CONNECT_TIMEOUT_IN_MILLIS = 1_000;

  @VisibleForTesting
  public static WindowsHandleFactory windowsHandleFactory = new DefaultWindowsHandleFactory();

  @Override
  public NamedPipeWriter createAsWriter() {
    return new WindowsNamedPipeServerWriter(createPath(), windowsHandleFactory);
  }

  @Override
  public NamedPipeReader createAsReader() {
    return new WindowsNamedPipeServerReader(createPath(), windowsHandleFactory);
  }

  private static Path createPath() {
    String namedPipePath =
        String.join(
            WINDOWS_PATH_DELIMITER,
            WINDOWS_PATH_DELIMITER,
            ".",
            "pipe",
            "buck-" + UUID.randomUUID());
    return Paths.get(namedPipePath);
  }

  @Override
  public NamedPipeWriter connectAsWriter(Path path) throws IOException {
    return new WindowsNamedPipeClientWriter(
        path, connectToPipe(path), getCloseHandleCallback(), windowsHandleFactory);
  }

  @Override
  public NamedPipeReader connectAsReader(Path path) throws IOException {
    return new WindowsNamedPipeClientReader(
        path, connectToPipe(path), getCloseHandleCallback(), windowsHandleFactory);
  }

  private static WindowsHandle connectToPipe(Path path) throws IOException {
    String namedPipeName = path.toString();
    Kernel32.INSTANCE.WaitNamedPipe(namedPipeName, CONNECT_TIMEOUT_IN_MILLIS);

    WindowsHandle handle =
        windowsHandleFactory.create(
            Kernel32.INSTANCE.CreateFile(
                namedPipeName,
                WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
                0, // no sharing
                null, // default security attributes
                WinNT.OPEN_EXISTING, // opens existing pipe
                WinNT.FILE_FLAG_OVERLAPPED,
                null // no template file
                ),
            "CreateFile() for " + namedPipeName);

    if (handle.isInvalidHandle()) {
      throw new IOException(
          String.format(
              "Could not create named pipe: %s, error code %s",
              namedPipeName, Kernel32.INSTANCE.GetLastError()));
    }

    return handle;
  }

  private static Consumer<WindowsHandle> getCloseHandleCallback() {
    return handle -> closeConnectedPipe(handle, true);
  }
}
