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

import com.facebook.buck.io.file.MorePosixFilePermissions;
import com.google.common.collect.Sets;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import java.nio.file.attribute.PosixFilePermission;

/** Utility class to bridge native POSIX named pipe calls to Java using JNA. */
class POSIXNamedPipeLibrary {

  static final int OWNER_READ_WRITE_ACCESS_MODE =
      (int)
          MorePosixFilePermissions.toMode(
              Sets.newHashSet(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

  static {
    Native.register(Platform.C_LIBRARY_NAME);
  }

  /**
   * Native wrapper around POSIX mkfifo(3) C library call.
   *
   * @param path the name of the pipe to create.
   * @param mode the mode with which to create the pipe.
   * @throws LastErrorException if the mkfifo failed.
   */
  static native int mkfifo(String path, int mode) throws LastErrorException;
}
