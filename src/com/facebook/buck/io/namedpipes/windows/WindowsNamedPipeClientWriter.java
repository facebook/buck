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

import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandle;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandleFactory;
import com.google.common.io.Closer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Client implementation of Windows name pipe reader. */
class WindowsNamedPipeClientWriter extends WindowsNamedPipeClientBase implements NamedPipeWriter {

  private final Closer closer = Closer.create();

  WindowsNamedPipeClientWriter(
      Path path,
      WindowsHandle handle,
      Consumer<WindowsHandle> closeCallback,
      WindowsHandleFactory windowsHandleFactory) {
    super(path, handle, closeCallback, windowsHandleFactory);
    closer.register(super::close);
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return closer.register(
        new WindowsNamedPipeOutputStream(
            getNamedPipeHandle(), getName(), getWindowsHandleFactory()));
  }

  @Override
  public void close() throws IOException {
    closer.close();
  }
}
