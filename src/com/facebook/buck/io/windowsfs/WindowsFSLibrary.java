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

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.win32.W32APIOptions;

/** Utility class to bridge native windows FS calls to Java using JNA. */
public interface WindowsFSLibrary extends Library {
  WindowsFSLibrary INSTANCE =
      Native.loadLibrary("kernel32", WindowsFSLibrary.class, W32APIOptions.UNICODE_OPTIONS);

  int SYMBOLIC_LINK_FLAG_DIRECTORY = 1;
  int SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE = 2;

  boolean CreateSymbolicLinkW(String lpSymlinkFileName, String lpTargetFileName, int dwFlags);

  int GetLastError();
}
