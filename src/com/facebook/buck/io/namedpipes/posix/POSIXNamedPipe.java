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

package com.facebook.buck.io.namedpipes.posix;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.namedpipes.NamedPipe;
import com.google.common.base.MoreObjects;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** POSIX named pipe. */
class POSIXNamedPipe implements NamedPipe {

  private final AbsPath path;
  private final RandomAccessFile randomAccessFile;

  public POSIXNamedPipe(AbsPath path) throws IOException {
    this.path = path;
    this.randomAccessFile = new RandomAccessFile(path.toString(), "rw");
  }

  @Override
  public String getName() {
    return path.toString();
  }

  @Override
  public InputStream openInputStream() throws IOException {
    return new FileInputStream(randomAccessFile.getFD());
  }

  @Override
  public OutputStream openOutputStream() throws IOException {
    return new FileOutputStream(randomAccessFile.getFD());
  }

  @Override
  public void close() throws IOException {
    randomAccessFile.close();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("path", path).toString();
  }

  protected AbsPath getPath() {
    return path;
  }

  /** POSIX name pipe that creates and removes a physical file corresponding to the named pipe. */
  static class OwnedPOSIXNamedPipe extends POSIXNamedPipe {

    public OwnedPOSIXNamedPipe(AbsPath path) throws IOException {
      /**
       * The first call in the child class should be a call to a surer class constructor, but if
       * {@code RandomAccessFile} constructor creates a file first, then a native call to `mkfifo`
       * failed with `File exists` exception. The way how to invoke `mkfifo` before {@code
       * RandomAccessFile} constructor is to wrap `mkfifo` with a such static function.
       */
      super(createNamedPipe(path));
    }

    private static AbsPath createNamedPipe(AbsPath absPath) throws IOException {
      Path path = absPath.getPath();
      Files.createDirectories(path.getParent());
      String pathString = path.toString();
      int exitCode =
          POSIXNamedPipeLibrary.mkfifo(
              pathString, POSIXNamedPipeLibrary.OWNER_READ_WRITE_ACCESS_MODE);
      if (exitCode != 0) {
        throw new IOException(
            "Can't create named pipe: "
                + pathString
                + " with `mkfifo` command:"
                + " exit code = "
                + exitCode);
      }
      return absPath;
    }

    @Override
    public void close() throws IOException {
      super.close();
      Files.deleteIfExists(getPath().getPath());
    }
  }
}
