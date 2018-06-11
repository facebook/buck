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

import com.facebook.buck.log.Logger;
import com.google.common.base.Throwables;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import java.io.IOException;
import javax.annotation.concurrent.GuardedBy;

/**
 * Safely kill background processes on nailgun client exit. All process creation must synchronize on
 * the class object's monitor lock to make sure children inherit the correct signal handler set.
 */
public class BgProcessKiller {

  private static final Logger LOG = Logger.get(BgProcessKiller.class);

  @GuardedBy("BgProcessKiller.class")
  private static boolean initialized;

  @GuardedBy("BgProcessKiller.class")
  private static boolean armed;

  public static synchronized void init() {
    NativeLibrary libcLibrary = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);

    // We kill subprocesses by sending SIGINT to our process group; we want our subprocesses to die
    // on SIGINT while we ourselves stay around.  We can't just set SIGINT to SIG_IGN, because
    // subprocesses would inherit the SIG_IGN signal disposition and be immune to the SIGINT we
    // direct toward the process group.  We need _some_ signal handler that does nothing --- i.e.,
    // acts like SIG_IGN --- but that doesn't kill its host process.  When we spawn a subprocess,
    // the kernel replaces any signal handler that is not SIG_IGN with SIG_DFL, automatically
    // creating the signal handler configuration we want.
    //
    // getpid might seem like a strange choice of signal handler, but consider the following:
    //
    //   1) on all supported ABIs, calling a function that returns int as if it returned void is
    //      harmless --- the return value is stuffed into a register (for example, EAX on x86
    //      32-bit) that the caller ignores (EAX is caller-saved),
    //
    //   2) on all supported ABIs, calling a function with extra arguments is safe --- the callee
    //      ignores the extra arguments, which are passed either in caller-saved registers or in
    //      stack locations that the caller [1] cleans up upon return,
    //
    //   3) getpid is void(*)(int); signal handlers are void(*)(int), and
    //
    //   4) getpid is async-signal-safe according to POSIX.
    //
    // Therefore, it is safe to set getpid _as_ a signal handler.  It does nothing, exactly as we
    // want, and gets reset to SIG_DFL in subprocesses.  If we were a C program, we'd just define a
    // no-op function of the correct signature to use as a handler, but we're using JNA, and while
    // JNA does have the ability to C function pointer to a Java function, it cannot create an
    // async-signal-safe C function, since calls into Java are inherently signal-unsafe.
    //
    // [1] Win32 32-bit x86 stdcall is an exception to this general rule --- because the callee
    //      cleans up its stack --- but we don't run this code on Windows.
    //
    Libc.INSTANCE.signal(Libc.Constants.SIGINT, libcLibrary.getFunction("getpid"));
    initialized = true;
  }

  public static synchronized void disarm() {
    armed = false;
  }

  /** Sends SIGINT to all background processes */
  public static synchronized void interruptBgProcesses() {
    if (initialized) {
      armed = true;
      Libc.INSTANCE.kill(0 /* my process group */, Libc.Constants.SIGINT);
    }
  }

  private BgProcessKiller() {}

  @GuardedBy("BgProcessKiller.class")
  private static void checkArmedStatus() {
    if (armed) {
      BuckIsDyingException e =
          new BuckIsDyingException("process creation blocked due to pending nailgun exit");
      LOG.info(
          "BuckIsDyingException: process creation blocked due to pending nailgun exit at:"
              + Throwables.getStackTraceAsString(e));
      throw e;
    }
  }

  /**
   * Use this method instead of {@link ProcessBuilder#start} in order to properly synchronize with
   * signal handling.
   */
  public static synchronized Process startProcess(ProcessBuilder pb) throws IOException {
    checkArmedStatus();
    return pb.start();
  }

  /**
   * Use this method instead of {@link NuProcessBuilder#start} in order to properly synchronize with
   * signal handling.
   */
  public static synchronized NuProcess startProcess(NuProcessBuilder pb) {
    checkArmedStatus();
    return pb.start();
  }
}
