/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.io.windowsfs;

import java.io.IOException;
import java.nio.file.Path;

/** Utility class for working with windows FS */
public class WindowsFS {

  /**
   * Creates a symbolic link (using CreateSymbolicLink winapi call under the hood).
   *
   * @param symlink the path of the symbolic link to create
   * @param target the target of the symbolic link
   * @param dirLink whether the target is a directory
   * @throws IOException if an underlying system call fails
   */
  public void createSymbolicLink(Path symlink, Path target, boolean dirLink) throws IOException {
    int flags =
        (dirLink ? WindowsFSLibrary.SYMBOLIC_LINK_FLAG_DIRECTORY : 0)
            | WindowsFSLibrary.SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE;

    String symlinkPathString = (symlink.isAbsolute() ? "\\\\?\\" : "") + symlink;
    String targetPathString = (target.isAbsolute() ? "\\\\?\\" : "") + target;
    boolean created =
        WindowsFSLibrary.INSTANCE.CreateSymbolicLinkW(symlinkPathString, targetPathString, flags);
    int lastError = WindowsFSLibrary.INSTANCE.GetLastError();
    if (!created || lastError != 0) {
      throw new IOException(
          "Tried to link "
              + symlinkPathString
              + " to "
              + targetPathString
              + " (winapi error: "
              + lastError
              + ")");
    }
  }
}
