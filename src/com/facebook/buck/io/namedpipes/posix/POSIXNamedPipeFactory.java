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

import com.facebook.buck.io.namedpipes.NamedPipe;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** POSIX named pipe factory. (Singleton With Enum Implementation). */
public enum POSIXNamedPipeFactory implements NamedPipeFactory {
  INSTANCE;

  private static final String TMP_DIR = System.getProperty("java.io.tmpdir");

  @Override
  public NamedPipe create() throws IOException {
    Path namedPathName = Paths.get(TMP_DIR, "pipe", UUID.randomUUID().toString());
    createNamedPipe(namedPathName);
    return new POSIXNamedPipe(namedPathName);
  }

  private static void createNamedPipe(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    String pathString = path.toString();
    int exitCode =
        POSIXNamedPipeLibrary.mkfifo(
            pathString, POSIXNamedPipeLibrary.OWNER_READ_WRITE_ACCESS_MODE);
    if (exitCode != 0) {
      throw new IOException(
          String.format(
              "Can't create named pipe: %s with `mkfifo` command. Exit code: %s",
              pathString, exitCode));
    }
  }
}
