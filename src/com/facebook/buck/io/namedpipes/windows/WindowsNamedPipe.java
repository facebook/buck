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

import static com.facebook.buck.io.windowspipe.WindowsNamedPipe.createPipeWithPath;

import com.facebook.buck.io.namedpipes.NamedPipe;
import com.google.common.base.MoreObjects;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Windows named pipe. */
class WindowsNamedPipe implements NamedPipe {

  private final Path path;
  private final com.facebook.buck.io.windowspipe.WindowsNamedPipe windowsNamedPipe;

  public WindowsNamedPipe(Path namedPipePath) throws IOException {
    this.path = namedPipePath;
    this.windowsNamedPipe = createPipeWithPath(namedPipePath.toString());
  }

  @Override
  public String getName() {
    return path.toString();
  }

  @Override
  public InputStream getInputStream() {
    return windowsNamedPipe.getInputStream();
  }

  @Override
  public OutputStream getOutputStream() {
    return windowsNamedPipe.getOutputStream();
  }

  @Override
  public void close() throws IOException {
    windowsNamedPipe.close();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("path", path).toString();
  }

  protected Path getPath() {
    return path;
  }

  /** Windows name pipe that creates and removes a physical file corresponding to the named pipe. */
  static class OwnedWindowsNamedPipe extends WindowsNamedPipe {

    public OwnedWindowsNamedPipe(Path path) throws IOException {
      super(path);
    }

    @Override
    public void close() throws IOException {
      super.close();
      Files.deleteIfExists(getPath());
    }
  }
}
