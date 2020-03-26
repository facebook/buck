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

package com.facebook.buck.io.namedpipes;

import com.facebook.buck.io.namedpipes.posix.POSIXNamedPipeFactory;
import com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeFactory;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;

/** Factory interface that creates named pipe. */
public interface NamedPipeFactory {

  /** Creates platform specific named pipe and named pipe object. */
  NamedPipe create() throws IOException;

  /**
   * Connects to a given {@code namedPipePath}.
   *
   * @param namedPipePath - absolute path for the named pipe.
   */
  default NamedPipe connect(Path namedPipePath) throws IOException {
    return new RandomAccessFileBasedNamedPipe(namedPipePath);
  }

  /** Returns platform specific implementation of {@code NamedPipeFactory}. */
  static NamedPipeFactory getFactory() {
    if (Platform.detect() == Platform.WINDOWS) {
      return WindowsNamedPipeFactory.INSTANCE;
    }
    return POSIXNamedPipeFactory.INSTANCE;
  }
}
