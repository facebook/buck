/*
 * Copyright 2015-present Facebook, Inc.
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

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

public interface Libc extends Library {

  Libc INSTANCE = Native.loadLibrary(Platform.C_LIBRARY_NAME, Libc.class);

  Pointer signal(int signal, Pointer function);

  int kill(int pid, int sig) throws LastErrorException;

  interface OpenPtyLibrary extends Library {
    int openpty(
        IntByReference master, IntByReference slave, Pointer name, Pointer terp, Pointer winp);
  }

  int setsid();

  int ioctl(int fd, Pointer request, Object... args);

  int fcntl(int fd, int cmd, Object... args);

  final class Constants {
    public static final int LINUX_TIOCSCTTY = 0x540E;
    public static final int DARWIN_TIOCSCTTY = 0x20007461;
    public static int rTIOCSCTTY;

    public static final int LINUX_FD_CLOEXEC = 0x1;
    public static final int DARWIN_FD_CLOEXEC = 0x1;
    public static int rFDCLOEXEC;

    public static final int LINUX_F_GETFD = 0x1;
    public static final int DARWIN_F_GETFD = 0x1;
    public static int rFGETFD;

    public static final int LINUX_F_SETFD = 0x2;
    public static final int DARWIN_F_SETFD = 0x2;
    public static int rFSETFD;

    public static final int SIGHUP = 1;
    public static final int SIGINT = 2;
  }
}
