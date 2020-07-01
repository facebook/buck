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

import static com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeFactory.API;

import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Windows name pipe that operates over passed {@code WinNT.HANDLE} and removes a physical file
 * corresponding to the named pipe.
 *
 * <p>https://docs.microsoft.com/en-us/dotnet/standard/io/how-to-use-named-pipes-for-network-interprocess-communication
 */
class WindowsServerNamedPipe extends WindowsBaseNamedPipe {

  private boolean awaitingConnection = false;

  WindowsServerNamedPipe(Path path, WinNT.HANDLE handle) {
    super(path, handle);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    awaitConnectedClients();
    return super.getInputStream();
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    awaitConnectedClients();
    return super.getOutputStream();
  }

  private void awaitConnectedClients() throws IOException {
    awaitingConnection = true;
    if (!API.ConnectNamedPipe(getHandle(), null)) {
      int lastError = API.GetLastError();
      if (lastError != WinError.ERROR_PIPE_CONNECTED) {
        throw new IOException(
            String.format(
                "ConnectNamedPipe() failed for named pipe: %s. Error code: %s",
                getName(), lastError));
      }
    }
    awaitingConnection = false;
  }

  @Override
  public void close() throws IOException {
    if (awaitingConnection) {
      // Server is still waiting for client to connect.
      // Creating a temporary client that would allow the server to disconnect from the pipe.
      try (WindowsClientNamedPipe ignore = new WindowsClientNamedPipe(getPath())) {
        disconnect();
      }
    }

    super.close();
    Files.deleteIfExists(getPath());
  }
}
