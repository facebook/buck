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

import com.facebook.buck.io.namedpipes.NamedPipe;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** Windows named pipe factory. (Singleton With Enum Implementation). */
public enum WindowsNamedPipeFactory implements NamedPipeFactory {
  INSTANCE;

  static final Kernel32 API = Kernel32.INSTANCE;

  private static final int KB_IN_BYTES = 1024;
  // Linux has 64K buffer, MacOS 16K.
  // Set buffer size to 32K (the number in the middle of Linux and MacOs sizes).
  private static final int BUFFER_SIZE = 32 * KB_IN_BYTES;

  private static final String WINDOWS_PATH_DELIMITER = "\\";

  @Override
  public NamedPipe create() throws IOException {
    String namedPipePath =
        String.join(
            WINDOWS_PATH_DELIMITER,
            WINDOWS_PATH_DELIMITER,
            ".",
            "pipe",
            "buck-" + UUID.randomUUID());
    Path path = Paths.get(namedPipePath);
    return new WindowsServerNamedPipe(path, createNamedPipe(namedPipePath));
  }

  private static WinNT.HANDLE createNamedPipe(String namedPipePath) throws IOException {
    WinNT.HANDLE namedPipeHandler =
        API.CreateNamedPipe(
            /* lpName */ namedPipePath,
            /* dwOpenMode */ WinBase.PIPE_ACCESS_DUPLEX,
            /* dwPipeMode */ WinBase.PIPE_TYPE_BYTE
                | WinBase.PIPE_READMODE_BYTE
                // do not allow remote clients
                | WinBase.PIPE_REJECT_REMOTE_CLIENTS
                | WinBase.PIPE_WAIT,
            /* nMaxInstances */ WinBase.PIPE_UNLIMITED_INSTANCES,
            /* nOutBufferSize */ BUFFER_SIZE,
            /* nInBufferSize */ BUFFER_SIZE,
            /* nDefaultTimeOut */ 0,
            /* lpSecurityAttributes */ null);

    if (WinBase.INVALID_HANDLE_VALUE.equals(namedPipeHandler)) {
      throw new IOException(
          String.format(
              "Cannot create named pipe: %s with CreateNamedPipe() command. Error code: %s",
              namedPipePath, API.GetLastError()));
    }
    return namedPipeHandler;
  }

  @Override
  public NamedPipe connect(Path namedPipePath) {
    return new WindowsClientNamedPipe(namedPipePath);
  }
}
