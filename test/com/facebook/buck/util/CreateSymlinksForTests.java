/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.windowsfs.WindowsFS;
import java.io.IOException;
import java.nio.file.Path;

public class CreateSymlinksForTests {
  private static final WindowsFS winFS;

  static {
    winFS = new WindowsFS();
  }

  /**
   * Creates a symlink using platform specific implementations, if there are some.
   *
   * @param symLink symlink to create.
   * @param realFile target of the symlink.
   * @throws IOException
   */
  public static void createSymLink(Path symLink, Path realFile) throws IOException {
    MorePaths.createSymLink(winFS, symLink, realFile);
  }
}
