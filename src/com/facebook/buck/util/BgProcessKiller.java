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

import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import java.io.IOException;

/**
 * Safely kill background processes on nailgun client exit.  All process creation must synchronize
 * on the class object's monitor lock to make sure children inherit the correct signal handler set.
 */
public class BgProcessKiller {

  private static boolean initialized;

  public static void init() {
    NativeLibrary libcLibrary = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);
    Libc.INSTANCE.signal(Libc.Constants.SIGHUP, libcLibrary.getFunction("getpid"));
    initialized = true;
  }

  public static synchronized void killBgProcesses() {
    if (initialized) {
      Libc.INSTANCE.kill(0 /* my process group */, Libc.Constants.SIGHUP);
    }
  }

  private BgProcessKiller() {}

  /**
   * Use this method instead of {@link ProcessBuilder#start} in order to properly synchronize with
   * signal handling.
   */
  public static synchronized Process startProcess(ProcessBuilder pb) throws IOException {
    return pb.start();
  }

  /**
   * Use this method instead of {@link NuProcessBuilder#start} in order to properly synchronize with
   * signal handling.
   */
  public static synchronized NuProcess startProcess(NuProcessBuilder pb) {
    return pb.start();
  }
}
