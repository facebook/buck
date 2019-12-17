/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.step.fs;

import java.io.IOException;
import java.nio.file.Path;

/** Interface used to encapsulate symlinks used by {@link SymlinkTreeMergeStep}. */
public interface SymlinkPaths {

  /**
   * Run {@code consumer} on all links. Meant to be called by {@link com.facebook.buck.step.Step}s
   * when actually creating the symlinks on disk.
   */
  void forEachSymlink(SymlinkConsumer consumer) throws IOException;

  /** Functional interface called above on all symlinks. */
  interface SymlinkConsumer {
    void accept(Path dst, Path src) throws IOException;
  }
}
