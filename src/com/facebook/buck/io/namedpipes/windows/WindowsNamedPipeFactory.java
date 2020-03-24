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
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** Windows named pipe factory. */
public class WindowsNamedPipeFactory implements NamedPipeFactory {

  String TMP_DIR = System.getProperty("java.io.tmpdir");

  /** Returns a generated platform specific named pipe path. */
  Path generateNamedPathName() {
    return Paths.get(TMP_DIR, "Pipe", UUID.randomUUID().toString());
  }

  @Override
  public NamedPipe create() throws IOException {
    return new WindowsNamedPipe.OwnedWindowsNamedPipe(generateNamedPathName());
  }

  @Override
  public NamedPipe connect(Path path) throws IOException {
    return new WindowsNamedPipe(path);
  }
}
