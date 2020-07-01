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

import com.facebook.buck.core.util.log.Logger;
import com.sun.jna.platform.win32.WinNT;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Windows name pipe that operates over passed named pipe path. Named pipe should have already been
 * created.
 */
class WindowsClientNamedPipe extends WindowsBaseNamedPipe {

  private static final Logger LOG = Logger.get(WindowsClientNamedPipe.class);

  private static final int CONNECT_TIMEOUT_IN_MILLIS = (int) TimeUnit.SECONDS.toMillis(1L);

  public WindowsClientNamedPipe(Path path) {
    super(path, createPipeFileHandle(path));
  }

  private static WinNT.HANDLE createPipeFileHandle(Path path) {
    String namedPipeName = path.toString();
    API.WaitNamedPipe(namedPipeName, CONNECT_TIMEOUT_IN_MILLIS);
    LOG.info("Connecting to server named pipe: %s", namedPipeName);

    return API.CreateFile(
        namedPipeName,
        WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
        0, // no sharing
        null, // default security attributes
        WinNT.OPEN_EXISTING, // opens existing pipe
        0, // default attributes
        null // no template file
        );
  }
}
